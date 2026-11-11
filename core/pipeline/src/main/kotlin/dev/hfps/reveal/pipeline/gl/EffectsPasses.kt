package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.cpu.EffectsGrid
import dev.hfps.reveal.pipeline.cpu.LowResGrid

/**
 * The low-resolution grid of clarity and dehaze on the GL thread (docs/adr/0014, `cpu/EffectsGrid`): row segments of
 * the develop output (OkLab, before Detail) -> cells -> dark channel erosion -> atmospheric light (16 x 16 block
 * reductions down to 1 x 1) -> guided-filter inputs -> two separable box means with the coefficient step between them.
 * All targets are RGBA32F textures of the grid size (the rows: grid width x image height) read with `texelFetch`.
 *
 * Tiles (ADR 0007's seam): the rows pass reads one grid cell's row segment of a region, so a tiled render fills it from
 * cell-aligned pre-pass tiles ([rows]) and finishes once ([finish]); the final pass then reads only the grid. Nothing
 * here depends on the Effects amounts.
 */
@Suppress("TooManyFunctions")
internal class EffectsPasses {
    private var rowsProgram = 0
    private var cellsProgram = 0
    private var erodeProgram = 0
    private var airProgram = 0
    private var momentsProgram = 0
    private var boxProgram = 0

    /** The grid of the last [allocate]. */
    var grid: LowResGrid? = null
        private set
    private var rows = Mrt.NONE
    private var cells = Mrt.NONE
    private var eroded = Target.NONE
    private val air = mutableListOf<Target>()
    private val airSum = mutableListOf<Target>()
    private var moments = Target.NONE
    private var tmpA = Target.NONE
    private var tmpB = Target.NONE
    private var coef = Target.NONE

    private class Target(val texture: Int, val fbo: Int, val width: Int, val height: Int) {
        companion object {
            val NONE = Target(0, 0, 0, 0)
        }
    }

    /** Two RGBA32F textures behind one framebuffer (two colour attachments). */
    private class Mrt(val first: Int, val second: Int, val fbo: Int, val width: Int, val height: Int) {
        companion object {
            val NONE = Mrt(0, 0, 0, 0, 0)
        }
    }

    /** Cell means `(L, L^2, a, b)` after [finish]. */
    val cellsTexture: Int get() = cells.first

    /** Coefficients `(aClarity, bClarity, aHaze, bHaze)` after [finish]. */
    val coefTexture: Int get() = coef.texture

    /** The atmospheric light `(sum L, sum a, sum b, cells)` (1 x 1) after [finish]. */
    val airTexture: Int get() = airSum.last().texture

    fun init() {
        rowsProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, EffectsShaders.ROWS_FRAG)
        cellsProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, EffectsShaders.CELLS_FRAG)
        erodeProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, EffectsShaders.ERODE_FRAG)
        airProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, EffectsShaders.AIR_FRAG)
        momentsProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, EffectsShaders.MOMENTS_FRAG)
        boxProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, EffectsShaders.BOX_FRAG)
        sampler(rowsProgram, "uSrc", 0)
        sampler(cellsProgram, "uRows", 0)
        sampler(cellsProgram, "uRowMins", 1)
        sampler(erodeProgram, "uSrc", 0)
        sampler(airProgram, "uSrc", 0)
        sampler(airProgram, "uMeans", 1)
        sampler(airProgram, "uMax", 2)
        sampler(momentsProgram, "uMeans", 0)
        sampler(momentsProgram, "uEroded", 1)
        sampler(momentsProgram, "uAir", 2)
        sampler(boxProgram, "uSrc", 0)
    }

    /** Allocates the grid textures for an image of [width] x [height] (kept when the size is unchanged). */
    fun allocate(width: Int, height: Int): LowResGrid {
        grid?.let { if (it.width == width && it.height == height) return it }
        releaseTargets()
        val g = LowResGrid(width, height)
        grid = g
        rows = mrt(g.gridWidth, height)
        cells = mrt(g.gridWidth, g.gridHeight)
        eroded = target(g.gridWidth, g.gridHeight)
        var w = g.gridWidth
        var h = g.gridHeight
        while (w > 1 || h > 1) {
            w = (w + EffectsGrid.AIR_BLOCK - 1) / EffectsGrid.AIR_BLOCK
            h = (h + EffectsGrid.AIR_BLOCK - 1) / EffectsGrid.AIR_BLOCK
            air += target(w, h)
            airSum += target(w, h)
        }
        if (air.isEmpty()) {
            air += target(1, 1)
            airSum += target(1, 1)
        }
        moments = target(g.gridWidth, g.gridHeight)
        tmpA = target(g.gridWidth, g.gridHeight)
        tmpB = target(g.gridWidth, g.gridHeight)
        coef = target(g.gridWidth, g.gridHeight)
        return g
    }

    /** The whole grid from [lab] (the develop output in OkLab, the whole image). */
    fun build(lab: Int) {
        val g = checkNotNull(grid)
        rows(lab, 0, 0, g.width, g.height)
        finish()
    }

    /**
     * Tiled pre-pass: the row segments of the image region ([x], [y], [width] x [height]) whose OkLab develop output
     * [lab] holds at (0, 0). [x] and [width] must be multiples of the cell size (or reach the image's right edge).
     */
    fun rows(lab: Int, x: Int, y: Int, width: Int, height: Int) {
        val g = checkNotNull(grid)
        require(x % g.cell == 0 && (width % g.cell == 0 || x + width == g.width)) { "region not aligned to cells" }
        GLES30.glUseProgram(rowsProgram)
        GLES30.glUniform4i(GLES30.glGetUniformLocation(rowsProgram, "uGrid"), g.cell, g.width, g.height, 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(rowsProgram, "uOrigin"), x, y)
        bind(0, lab)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rows.fbo)
        val gx0 = x / g.cell
        val gx1 = (x + width + g.cell - 1) / g.cell
        GLES30.glViewport(gx0, y, gx1 - gx0, height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, FULLSCREEN_VERTICES)
        GlSupport.checkError("effects rows")
    }

    /** Once all rows are in: cells, erosion, atmospheric light, moments, box means and coefficients. */
    fun finish() {
        val g = checkNotNull(grid)
        GLES30.glUseProgram(cellsProgram)
        GLES30.glUniform4i(GLES30.glGetUniformLocation(cellsProgram, "uGrid"), g.cell, g.width, g.height, 0)
        bind(1, rows.second)
        bind(0, rows.first)
        drawInto(cells.fbo, cells.width, cells.height)
        GLES30.glUseProgram(erodeProgram)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(erodeProgram, "uSize"), g.gridWidth, g.gridHeight)
        bind(0, cells.second)
        drawInto(eroded.fbo, eroded.width, eroded.height)
        GLES30.glUseProgram(airProgram)
        val size = GLES30.glGetUniformLocation(airProgram, "uSize")
        val mode = GLES30.glGetUniformLocation(airProgram, "uMode")
        bind(1, cells.first)
        for (sum in listOf(false, true)) {
            val chain = if (sum) airSum else air
            var srcTex = eroded.texture
            var srcW = g.gridWidth
            var srcH = g.gridHeight
            if (sum) bind(2, air.last().texture)
            for ((i, level) in chain.withIndex()) {
                GLES30.glUniform2i(size, srcW, srcH)
                GLES30.glUniform1i(mode, (if (sum) 2 else 0) + (if (i == 0) 0 else 1))
                bind(0, srcTex)
                drawInto(level.fbo, level.width, level.height)
                srcTex = level.texture
                srcW = level.width
                srcH = level.height
            }
        }
        GLES30.glUseProgram(momentsProgram)
        bind(2, airTexture)
        bind(1, eroded.texture)
        bind(0, cells.first)
        drawInto(moments.fbo, moments.width, moments.height)
        GLES30.glUseProgram(boxProgram)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(boxProgram, "uSize"), g.gridWidth, g.gridHeight)
        val dir = GLES30.glGetUniformLocation(boxProgram, "uDir")
        val coefMode = GLES30.glGetUniformLocation(boxProgram, "uCoef")
        box(dir, coefMode, 1, 0, false, moments, tmpA)
        box(dir, coefMode, 0, 1, true, tmpA, tmpB)
        box(dir, coefMode, 1, 0, false, tmpB, tmpA)
        box(dir, coefMode, 0, 1, false, tmpA, coef)
        GlSupport.checkError("effects grid")
    }

    /** Binds the grid's textures to units 12-14 and sets the apply uniforms of [program] (`EffectsShaders.APPLY`). */
    fun bindApply(program: Int, clarityGain: Float, omega: Float, beta: Float) {
        val g = checkNotNull(grid)
        GLES30.glUniform3i(GLES30.glGetUniformLocation(program, "uEffectsGrid"), g.cell, g.gridWidth, g.gridHeight)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uEffects"), clarityGain, omega, beta)
        bind(UNIT_CELLS, cells.first)
        bind(UNIT_COEF, coef.texture)
        bind(UNIT_AIR, airTexture)
    }

    @Suppress("LongParameterList")
    private fun box(dir: Int, coefMode: Int, dx: Int, dy: Int, coefStep: Boolean, src: Target, dst: Target) {
        GLES30.glUniform2i(dir, dx, dy)
        GLES30.glUniform1i(coefMode, if (coefStep) 1 else 0)
        bind(0, src.texture)
        drawInto(dst.fbo, dst.width, dst.height)
    }

    private fun drawInto(fbo: Int, width: Int, height: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, FULLSCREEN_VERTICES)
    }

    private fun bind(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        if (unit != 0) GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun texture(w: Int, h: Int): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        return tex[0]
    }

    private fun framebuffer(vararg textures: Int): Int {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        for ((i, t) in textures.withIndex()) {
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0 + i,
                GLES30.GL_TEXTURE_2D,
                t,
                0,
            )
        }
        if (textures.size > 1) {
            GLES30.glDrawBuffers(textures.size, IntArray(textures.size) { GLES30.GL_COLOR_ATTACHMENT0 + it }, 0)
        }
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw GlesRequirementException("RGBA32F is not renderable (framebuffer status 0x${status.toString(HEX)})")
        }
        return fbo[0]
    }

    private fun target(w: Int, h: Int): Target {
        val t = texture(w, h)
        return Target(t, framebuffer(t), w, h)
    }

    private fun mrt(w: Int, h: Int): Mrt {
        val a = texture(w, h)
        val b = texture(w, h)
        return Mrt(a, b, framebuffer(a, b), w, h)
    }

    private fun releaseTargets() {
        val targets = air + airSum + listOf(eroded, moments, tmpA, tmpB, coef)
        val textures = targets.map { it.texture } + listOf(rows.first, rows.second, cells.first, cells.second)
        val fbos = targets.map { it.fbo } + listOf(rows.fbo, cells.fbo)
        val liveTextures = textures.filter { it != 0 }
        val liveFbos = fbos.filter { it != 0 }
        if (liveTextures.isNotEmpty()) GLES30.glDeleteTextures(liveTextures.size, liveTextures.toIntArray(), 0)
        if (liveFbos.isNotEmpty()) GLES30.glDeleteFramebuffers(liveFbos.size, liveFbos.toIntArray(), 0)
        air.clear()
        airSum.clear()
        rows = Mrt.NONE
        cells = Mrt.NONE
        eroded = Target.NONE
        moments = Target.NONE
        tmpA = Target.NONE
        tmpB = Target.NONE
        coef = Target.NONE
        grid = null
    }

    fun release() {
        releaseTargets()
        for (p in intArrayOf(rowsProgram, cellsProgram, erodeProgram, airProgram, momentsProgram, boxProgram)) {
            GLES30.glDeleteProgram(p)
        }
    }

    private fun sampler(program: Int, name: String, unit: Int) {
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
    }

    companion object {
        /** Texture units of the grid in the final pass (`DetailPasses`). */
        const val UNIT_CELLS: Int = 12
        const val UNIT_COEF: Int = 13
        const val UNIT_AIR: Int = 14

        private const val FULLSCREEN_VERTICES = 3
        private const val HEX = 16
    }
}
