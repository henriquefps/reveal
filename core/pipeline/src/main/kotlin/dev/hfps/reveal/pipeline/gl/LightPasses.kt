package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.cpu.HighlightParams
import dev.hfps.reveal.pipeline.cpu.LocalToneOp
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

    /** The grid of the last [allocate]. */
    var grid: LowResGrid? = null
        private set
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

    /** Builds the filled chroma grid from the raw [source] texture (the whole image) balanced by [mul]. */
    fun reconstruct(source: Int, mul: FloatArray, p: HighlightParams) {
        val g = checkNotNull(grid)
        reconstructRows(source, mul, p, 0, 0, g.width, g.height)
        reconstructFinish()
    }

    /**
     * Tiled pre-pass, step 1 of the chroma grid: the row segments of the image region ([x], [y], [width] x [height])
     * whose pixels [source] holds at (0, 0). [x] and [width] must be multiples of the cell size (or reach the
     * image's right edge), so each row segment lies in one region and the sums keep the whole-image order.
     */
    @Suppress("LongParameterList")
    fun reconstructRows(source: Int, mul: FloatArray, p: HighlightParams, x: Int, y: Int, width: Int, height: Int) {
        val g = checkNotNull(grid)
        GLES30.glUseProgram(reconRowsProgram)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(reconRowsProgram, "uMul"), mul[0], mul[1], mul[2], 1f)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(reconRowsProgram, "uClip"), p.clipR, p.clipG, p.clipB, 1f)
        GLES30.glUniform4i(GLES30.glGetUniformLocation(reconRowsProgram, "uGrid"), g.cell, g.width, g.height, 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(reconRowsProgram, "uOrigin"), x, y)
        drawRows(g, source, x, y, width, height)
    }

    /** Step 2 of the chroma grid, once all rows are in: cell means, push, pull. */
    fun reconstructFinish() {
        val g = checkNotNull(grid)
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

    /** Builds the guided-filter coefficients from the scene-linear [scene] texture (the whole image). */
    fun guide(scene: Int) {
        val g = checkNotNull(grid)
        guideRows(scene, 0, 0, g.width, g.height)
        guideFinish()
    }

    /**
     * The guide after the scene changed only inside [rect] (x0, y0, x1, y1; healing, docs/adr/0023): the row segments
     * of the cells it touches, then [guideFinish]. Equal to [guide] on the whole image.
     */
    fun guidePartial(scene: Int, rect: IntArray) {
        val g = checkNotNull(grid)
        val (left, top, right) = rect
        val bottom = rect.last()
        val x0 = (left.coerceIn(0, g.width) / g.cell) * g.cell
        val x1 = minOf(((right.coerceIn(0, g.width) + g.cell - 1) / g.cell) * g.cell, g.width)
        val y0 = top.coerceIn(0, g.height)
        val y1 = bottom.coerceIn(0, g.height)
        // The scene texture is the whole image: no tile origin.
        if (x1 > x0 && y1 > y0) guideRows(scene, x0, y0, x1 - x0, y1 - y0, origin = false)
        guideFinish()
    }

    /** Tiled pre-pass, step 1 of the guide: row segments of a region (same contract as [reconstructRows]). */
    fun guideRows(scene: Int, x: Int, y: Int, width: Int, height: Int, origin: Boolean = true) {
        val g = checkNotNull(grid)
        GLES30.glUseProgram(guideRowsProgram)
        GLES30.glUniform4i(GLES30.glGetUniformLocation(guideRowsProgram, "uGrid"), g.cell, g.width, g.height, 0)
        GLES30.glUniform2i(
            GLES30.glGetUniformLocation(guideRowsProgram, "uOrigin"),
            if (origin) x else 0,
            if (origin) y else 0,
        )
        drawRows(g, scene, x, y, width, height)
    }

    /** Step 2 of the guide, once all rows are in: cell moments, box means and coefficients. */
    fun guideFinish() {
        val g = checkNotNull(grid)
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

    /** Draws the bound rows program into the part of the rows target that covers the image region. */
    @Suppress("LongParameterList")
    private fun drawRows(g: LowResGrid, input: Int, x: Int, y: Int, width: Int, height: Int) {
        require(x % g.cell == 0 && (width % g.cell == 0 || x + width == g.width)) { "region not aligned to cells" }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rows.fbo)
        val gx0 = x / g.cell
        val gx1 = (x + width + g.cell - 1) / g.cell
        GLES30.glViewport(gx0, y, gx1 - gx0, height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, FULLSCREEN_VERTICES)
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

        /**
         * How far (pixels) a change of the scene inside a rectangle reaches in the develop output through the guide:
         * the cells it touches, two box means of [LocalToneOp.RADIUS] cells, and the
         * bilinear upsampling's neighbour cell.
         */
        fun guideReach(grid: LowResGrid): Int = (2 * LocalToneOp.RADIUS + GUIDE_EXTRA_CELLS) * grid.cell

        /** The cell the rectangle touches, the alignment of the partial rows to cells and the bilinear neighbour. */
        private const val GUIDE_EXTRA_CELLS = 3
    }
}
