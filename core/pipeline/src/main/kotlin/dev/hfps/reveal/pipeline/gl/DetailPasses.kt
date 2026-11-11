package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.cpu.DetailParams

/** A colour target: a texture and the framebuffer that renders into it. */
internal data class GlTarget(val texture: Int, val fbo: Int)

/**
 * The Detail operator's passes on the GL thread (slot 11, docs/adr/0012): the wavelet levels that run, then (when
 * sharpening or the mask overlay is on) the two blur passes and the final pass back to linear Rec.2020. The caller
 * owns every texture: the preview keeps its own, the tiled renderer reuses textures that are free at that point of a
 * tile. All passes cover one region of `width x height` texels whose texel (0, 0) is image position `origin`.
 */
internal class DetailPasses {
    private var levelProgram = 0
    private var blurProgram = 0
    private var finalProgram = 0

    fun init() {
        levelProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.LEVEL_FRAG)
        blurProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.BLUR_FRAG)
        finalProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.FINAL_FRAG)
        GLES30.glUseProgram(finalProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(finalProgram, "uSrc"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(finalProgram, "uBlur"), 1)
        for (p in intArrayOf(levelProgram, blurProgram)) {
            GLES30.glUseProgram(p)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uSrc"), 0)
        }
    }

    /**
     * Runs [p] on [lab] (the develop output in OkLab, `(L, a, b, acc)`, never written) and writes linear Rec.2020 into
     * [output], or when it is null into whichever level target is free at the end; returns the target written.
     * [levelA] / [levelB] take the wavelet levels in turn (RGBA16F, at least the region), [blurA] / [blurB] the two
     * blur axes (at least one float channel). A given [output] must not be [lab] or a level target.
     */
    @Suppress("LongParameterList")
    fun run(
        p: DetailParams,
        lab: Int,
        levelA: GlTarget,
        levelB: GlTarget,
        blurA: GlTarget,
        blurB: GlTarget,
        output: GlTarget?,
        region: Region,
        showMask: Boolean = false,
    ): GlTarget {
        var src = lab
        var dst = levelA
        var spare = levelB
        GLES30.glUseProgram(levelProgram)
        bindRegion(levelProgram, region)
        val step = GLES30.glGetUniformLocation(levelProgram, "uStep")
        val level = GLES30.glGetUniformLocation(levelProgram, "uLevel")
        GLES30.glUniform1f(GLES30.glGetUniformLocation(levelProgram, "uGuard"), p.chromaLuma)
        for (i in 0 until DetailParams.LEVELS) {
            if (!p.levelActive(i)) continue
            GLES30.glUniform1i(step, p.step[i])
            GLES30.glUniform4f(level, p.lumTau[i], p.lumEdge[i], p.chromaRange[i], p.chromaMix[i])
            draw(dst.fbo, src, region)
            src = dst.texture
            val t = dst
            dst = spare
            spare = t
        }
        val blur = p.sharpenActive || showMask
        if (blur) {
            GLES30.glUseProgram(blurProgram)
            bindRegion(blurProgram, region)
            val weights = p.blur.toFloatArray()
            GLES30.glUniform1fv(GLES30.glGetUniformLocation(blurProgram, "uWeights"), weights.size, weights, 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(blurProgram, "uRadius"), p.blurRadius)
            val dir = GLES30.glGetUniformLocation(blurProgram, "uDir")
            val fromLab = GLES30.glGetUniformLocation(blurProgram, "uFromLab")
            GLES30.glUniform2i(dir, 1, 0)
            GLES30.glUniform1i(fromLab, 1)
            draw(blurA.fbo, src, region)
            GLES30.glUniform2i(dir, 0, 1)
            GLES30.glUniform1i(fromLab, 0)
            draw(blurB.fbo, blurA.texture, region)
        }
        GLES30.glUseProgram(finalProgram)
        bindRegion(finalProgram, region)
        fun loc(name: String) = GLES30.glGetUniformLocation(finalProgram, name)
        GLES30.glUniform1i(loc("uSharpen"), if (p.sharpenActive) 1 else 0)
        GLES30.glUniform1i(loc("uShowMask"), if (showMask) 1 else 0)
        GLES30.glUniform4f(loc("uSharp"), p.sharpenGain, p.coring2, p.haloInv, p.gradientScale)
        GLES30.glUniform2f(loc("uMask"), p.maskLow, p.maskHigh)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (blur) blurB.texture else src)
        val out = output ?: dst
        draw(out.fbo, src, region)
        GlSupport.checkError("detail passes")
        return out
    }

    private fun bindRegion(program: Int, region: Region) {
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uOrigin"), region.x, region.y)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uImage"), region.imageWidth, region.imageHeight)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uSize"), region.width, region.height)
    }

    private fun draw(fbo: Int, input: Int, region: Region) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, region.width, region.height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, FULLSCREEN_VERTICES)
    }

    fun release() {
        for (p in intArrayOf(levelProgram, blurProgram, finalProgram)) GLES30.glDeleteProgram(p)
    }

    /** The region the passes cover: image position ([x], [y]), size, and the whole image's size (tap clamping). */
    data class Region(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val imageWidth: Int,
        val imageHeight: Int,
    )

    private companion object {
        const val FULLSCREEN_VERTICES = 3
    }
}
