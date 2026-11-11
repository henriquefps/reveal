package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.cpu.HighlightParams
import dev.hfps.reveal.pipeline.cpu.LowResGrid

/**
 * The low-resolution passes of the Light group on the GL thread (docs/adr/0007): the highlight-reconstruction chroma
 * pyramid (stats, push, pull) and the guided-filter guide of highlights/shadows (cell moments, two separable box
 * means with the coefficient step in between). All targets are small RGBA32F textures read with `texelFetch`.
 *
 * Tile seam (ADR 0002): every pass here reads either one grid cell of the full-resolution image or the grid itself,
 * and the full-resolution apply steps (in the scene and develop stages) are pointwise given the grid. A tiled export
 * therefore needs **no tile overlap** for the Light operators, only tiles aligned to [LowResGrid.cell] and a pre-pass
 * that fills the grid from all tiles ([REQUIRED_OVERLAP_PX]).
 */
@Suppress("TooManyFunctions")
internal class LightPasses {
    private var reconRowsProgram = 0
    private var cellMeanProgram = 0
    private var pushProgram = 0
    private var pullProgram = 0
    private var guideRowsProgram = 0
    private var boxProgram = 0

    private var grid: LowResGrid? = null
    private val pushLevels = mutableListOf<Target>()
    private val pullLevels = mutableListOf<Target>()
    private var rows = Target.NONE
    private var moments = Target.NONE
    private var tmpA = Target.NONE
    private var tmpB = Target.NONE
    private var coef = Target.NONE

    private class Target(val texture: Int, val fbo: Int, val width: Int, val height: Int) {
        companion object {
            val NONE = Target(0, 0, 0, 0)
        }
    }

    /** The filled chroma grid after [reconstruct]. */
    val chromaTexture: Int get() = pullLevels.first().texture

    /** The guide coefficients `(a', b')` after [guide]. */
    val coefTexture: Int get() = coef.texture

    /** The cell means of `l` and `l^2` after [guide] (the range weights of the coefficient upsampling). */
    val momentsTexture: Int get() = moments.texture

    fun init() {
        reconRowsProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, LightShaders.RECON_ROWS_FRAG)
        cellMeanProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, LightShaders.CELL_MEAN_FRAG)
        pushProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, LightShaders.PUSH_FRAG)
        pullProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, LightShaders.PULL_FRAG)
        guideRowsProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, LightShaders.GUIDE_ROWS_FRAG)
        boxProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, LightShaders.BOX_FRAG)
        sampler(reconRowsProgram, "uSource", 0)
        sampler(cellMeanProgram, "uRows", 0)
        sampler(pushProgram, "uLevel", 0)
        sampler(pullProgram, "uLevel", 0)
        sampler(pullProgram, "uParent", 1)
        sampler(guideRowsProgram, "uScene", 0)
        sampler(boxProgram, "uSrc", 0)
    }

    /** Allocates the grid textures for an image of [width] x [height]; returns the grid. */
    fun allocate(width: Int, height: Int): LowResGrid {
        releaseTargets()
        val g = LowResGrid(width, height)
        grid = g
        var w = g.gridWidth
        var h = g.gridHeight
        while (true) {
            pushLevels += target(w, h)
            pullLevels += target(w, h)
            if (w == 1 && h == 1) break
            w = (w + 1) / 2
            h = (h + 1) / 2
        }
        rows = target(g.gridWidth, height)
        moments = target(g.gridWidth, g.gridHeight)
        tmpA = target(g.gridWidth, g.gridHeight)
        tmpB = target(g.gridWidth, g.gridHeight)
        coef = target(g.gridWidth, g.gridHeight)
        return g
    }

    /** Builds the filled chroma grid from the raw [source] texture balanced by [mul]. */
    fun reconstruct(source: Int, mul: FloatArray, p: HighlightParams) {
        val g = checkNotNull(grid)
        GLES30.glUseProgram(reconRowsProgram)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(reconRowsProgram, "uMul"), mul[0], mul[1], mul[2], 1f)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(reconRowsProgram, "uClip"), p.clipR, p.clipG, p.clipB, 1f)
        GLES30.glUniform4i(GLES30.glGetUniformLocation(reconRowsProgram, "uGrid"), g.cell, g.width, g.height, 0)
        draw(rows, source)
        cellMean(g, pushLevels[0])
        GLES30.glUseProgram(pushProgram)
        val size = GLES30.glGetUniformLocation(pushProgram, "uSize")
        for (l in 1 until pushLevels.size) {
            GLES30.glUniform2i(size, pushLevels[l - 1].width, pushLevels[l - 1].height)
            draw(pushLevels[l], pushLevels[l - 1].texture)
        }
        GLES30.glUseProgram(pullProgram)
        val top = GLES30.glGetUniformLocation(pullProgram, "uTop")
        val parentSize = GLES30.glGetUniformLocation(pullProgram, "uParentSize")
        for (l in pullLevels.indices.reversed()) {
            val isTop = l == pullLevels.lastIndex
            val parent = if (isTop) pushLevels[l] else pullLevels[l + 1]
            GLES30.glUniform1i(top, if (isTop) 1 else 0)
            GLES30.glUniform2i(parentSize, parent.width, parent.height)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, parent.texture)
            draw(pullLevels[l], pushLevels[l].texture)
        }
        GlSupport.checkError("highlight reconstruction passes")
    }

    /** Builds the guided-filter coefficients from the scene-linear [scene] texture. */
    fun guide(scene: Int) {
        val g = checkNotNull(grid)
        GLES30.glUseProgram(guideRowsProgram)
        GLES30.glUniform4i(GLES30.glGetUniformLocation(guideRowsProgram, "uGrid"), g.cell, g.width, g.height, 0)
        draw(rows, scene)
        cellMean(g, moments)
        GLES30.glUseProgram(boxProgram)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(boxProgram, "uSize"), g.gridWidth, g.gridHeight)
        val dir = GLES30.glGetUniformLocation(boxProgram, "uDir")
        val coefMode = GLES30.glGetUniformLocation(boxProgram, "uCoef")
        box(dir, coefMode, 1, 0, coefStep = false, moments, tmpA)
        box(dir, coefMode, 0, 1, coefStep = true, tmpA, tmpB)
        box(dir, coefMode, 1, 0, coefStep = false, tmpB, tmpA)
        box(dir, coefMode, 0, 1, coefStep = false, tmpA, coef)
        GlSupport.checkError("local tone guide passes")
    }

    private fun cellMean(g: LowResGrid, dst: Target) {
        GLES30.glUseProgram(cellMeanProgram)
        GLES30.glUniform4i(GLES30.glGetUniformLocation(cellMeanProgram, "uGrid"), g.cell, g.width, g.height, 0)
        draw(dst, rows.texture)
    }

    @Suppress("LongParameterList")
    private fun box(dir: Int, coefMode: Int, dx: Int, dy: Int, coefStep: Boolean, src: Target, dst: Target) {
        GLES30.glUniform2i(dir, dx, dy)
        GLES30.glUniform1i(coefMode, if (coefStep) 1 else 0)
        draw(dst, src.texture)
    }

    private fun draw(dst: Target, input: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dst.fbo)
        GLES30.glViewport(0, 0, dst.width, dst.height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, FULLSCREEN_VERTICES)
    }

    private fun target(w: Int, h: Int): Target {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tex[0],
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw GlesRequirementException("RGBA32F is not renderable (framebuffer status 0x${status.toString(HEX)})")
        }
        return Target(tex[0], fbo[0], w, h)
    }

    private fun releaseTargets() {
        val all = pushLevels + pullLevels + listOf(rows, moments, tmpA, tmpB, coef)
        val live = all.filter { it.texture != 0 }
        if (live.isNotEmpty()) {
            GLES30.glDeleteTextures(live.size, live.map { it.texture }.toIntArray(), 0)
            GLES30.glDeleteFramebuffers(live.size, live.map { it.fbo }.toIntArray(), 0)
        }
        pushLevels.clear()
        pullLevels.clear()
        rows = Target.NONE
        moments = Target.NONE
        tmpA = Target.NONE
        tmpB = Target.NONE
        coef = Target.NONE
        grid = null
    }

    fun release() {
        releaseTargets()
        val programs =
            intArrayOf(reconRowsProgram, cellMeanProgram, pushProgram, pullProgram, guideRowsProgram, boxProgram)
        for (p in programs) {
            GLES30.glDeleteProgram(p)
        }
    }

    private fun sampler(program: Int, name: String, unit: Int) {
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
    }

    companion object {
        /**
         * Full-resolution overlap a tile needs for the Light operators: none (see the class comment), given tiles
         * aligned to the grid cell and the grid built from the whole image in a pre-pass.
         */
        const val REQUIRED_OVERLAP_PX: Int = 0

        private const val FULLSCREEN_VERTICES = 3
        private const val HEX = 16
    }
}
