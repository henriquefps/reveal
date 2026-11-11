package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.cpu.DetailParams
import dev.hfps.reveal.pipeline.cpu.EffectsParams

/** A colour target: a texture and the framebuffer that renders into it. */
internal data class GlTarget(val texture: Int, val fbo: Int)

/**
 * The passes of slots 11 and 12 on the GL thread (docs/adr/0012, docs/adr/0014): the luminance levels (two separable
 * passes each), the colour path on a half-resolution copy, the two blurs (sharpening and texture in one pair of
 * passes) and the final pass (sharpening, texture, clarity, colour, dehaze, back to linear Rec.2020). The caller owns
 * every texture ([Targets]): the preview keeps its own, the tiled renderer reuses textures that are free at that point
 * of a tile. All passes cover one region of `width x height` texels whose texel (0, 0) is image position `origin`.
 *
 * **Caching** (the preview, `cache = true`): a slider drag re-runs only what depends on it. The luminance levels are
 * kept while their parameters are, the colour levels likewise, the blurs while the denoised luminance and the blur
 * kernels are: a sharpening amount, texture, clarity or dehaze drag is one final pass; a colour drag is the
 * half-resolution levels and the final pass. [invalidate] drops everything (a new develop output).
 */
internal class DetailPasses {
    private var lumHProgram = 0
    private var lumVProgram = 0
    private var downProgram = 0
    private var chromaProgram = 0
    private var blurProgram = 0
    private val finalPrograms = HashMap<Int, Int>()

    private var lumKey: Any? = null
    private var lumResult: GlTarget? = null
    private var chromaKey: Any? = null
    private var chromaResult: GlTarget? = null
    private var blurKey: Any? = null

    private val locations = HashMap<Int, HashMap<String, Int>>()

    /** A uniform's location, looked up once per program (a lookup per frame costs a driver round trip). */
    private fun uniform(program: Int, name: String): Int =
        locations.getOrPut(program) { HashMap() }.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    /** Called after each section when measuring (`GpuDevelopRenderer.profilePasses`). */
    var profile: ((String) -> Unit)? = null

    /** Passes run by the last [run] (for the benchmark logs). */
    var lastPasses: Int = 0
        private set

    /**
     * The textures of one region: [lumA] / [lumB] (at least RG16F) take the luminance levels in turn, [lumH] (at least
     * R16F) the horizontal axis; [halfD] / [halfA] / [halfB] (RGBA16F, half the region rounded up, plus one) the colour
     * path; [blurA] / [blurB] (at least RG32F) the two blur axes.
     */
    data class Targets(
        val lumA: GlTarget,
        val lumB: GlTarget,
        val lumH: GlTarget,
        val halfD: GlTarget,
        val halfA: GlTarget,
        val halfB: GlTarget,
        val blurA: GlTarget,
        val blurB: GlTarget,
    )

    fun init() {
        lumHProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.LUM_H_FRAG)
        lumVProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.LUM_V_FRAG)
        downProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.DOWN_FRAG)
        chromaProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.CHROMA_FRAG)
        blurProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.BLUR_FRAG)
        for (p in intArrayOf(lumHProgram, lumVProgram, downProgram, chromaProgram, blurProgram)) {
            GLES30.glUseProgram(p)
            GLES30.glUniform1i(uniform(p, "uSrc"), 0)
        }
        GLES30.glUseProgram(lumVProgram)
        GLES30.glUniform1i(uniform(lumVProgram, "uPrev"), 1)
    }

    /**
     * The final pass specialised for one set of [flags] (compile-time constants: a variant holds only the features it
     * runs, so the general shader's registers do not slow every pixel down on mobile GPUs), compiled on first use.
     */
    private fun finalProgram(flags: Int): Int = finalPrograms.getOrPut(flags) {
        val program = GlSupport.compile(Shaders.FULLSCREEN_VERT, DetailShaders.finalFrag(flags))
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(uniform(program, "uSrc"), 0)
        for ((name, unit) in listOf(
            "uLum" to UNIT_LUM,
            "uBlur" to UNIT_BLUR,
            "uHalfS" to UNIT_HALF_S,
            "uHalfD" to UNIT_HALF_D,
            "uCells" to EffectsPasses.UNIT_CELLS,
            "uCoef" to EffectsPasses.UNIT_COEF,
            "uAir" to EffectsPasses.UNIT_AIR,
        )) {
            GLES30.glUniform1i(uniform(program, name), unit)
        }
        program
    }

    /** Forgets every cached intermediate (the OkLab input or the targets changed). */
    fun invalidate() {
        lumKey = null
        lumResult = null
        chromaKey = null
        chromaResult = null
        blurKey = null
    }

    /**
     * Runs [p] and the local Effects of [e] on [lab] (the develop output in OkLab, `(L, a, b, 0)`, never written) and
     * writes linear Rec.2020 into [output], or when it is null into whichever luminance target is free at the end;
     * returns the target written. [grid] holds the Effects grid when clarity or dehaze runs. A given [output] must not
     * be [lab] or one of [t]. [cache] keeps intermediates between calls (see the class comment).
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun run(
        p: DetailParams,
        e: EffectsParams,
        grid: EffectsPasses?,
        lab: Int,
        t: Targets,
        output: GlTarget?,
        region: Region,
        showMask: Boolean = false,
        cache: Boolean = false,
    ): GlTarget {
        if (!cache) invalidate()
        lastPasses = 0
        // 1. Luminance levels.
        val lumParams = if (p.lumActive) Triple(p.lumStep, p.lumTau, p.lumEdge) else null
        if (lumParams != lumKey) {
            lumResult = if (lumParams != null) lumLevels(p, lab, t, region) else null
            if (lumParams != null) profile?.invoke("detail luminance levels")
            lumKey = lumParams
            blurKey = null
        }
        val lum = lumResult
        // 2. Colour levels on the half-resolution copy.
        val chromaParams = if (p.chromaActive) {
            listOf(p.chromaStep, p.chromaRange, p.chromaMix, p.chromaLuma)
        } else {
            null
        }
        if (chromaParams != chromaKey) {
            chromaResult = if (chromaParams != null) chromaLevels(p, lab, t, region) else null
            if (chromaParams != null) profile?.invoke("detail colour levels")
            chromaKey = chromaParams
        }
        // 3. The blurs of the denoised luminance.
        val sharpen = p.sharpenActive || showMask
        val blurOn = sharpen || e.textureActive
        if (blurOn) {
            val key = listOf(lum?.texture, p.blur, e.textureBlur, e.textureRange)
            if (key != blurKey) {
                blur(p, e, if (lum != null) lum.texture else lab, lum != null, t, region)
                blurKey = key
                profile?.invoke("detail blurs")
            }
        }
        // 4. The final pass.
        val out = output ?: if (lum == t.lumA) t.lumB else t.lumA
        var flags = 0
        if (lum != null) flags = flags or DetailShaders.FLAG_LUM
        if (chromaResult != null) flags = flags or DetailShaders.FLAG_CHROMA
        if (p.sharpenActive) flags = flags or DetailShaders.FLAG_SHARPEN
        if (showMask) flags = flags or DetailShaders.FLAG_SHOW_MASK
        if (e.textureActive) flags = flags or DetailShaders.FLAG_TEXTURE
        if (grid != null && e.clarityActive) flags = flags or DetailShaders.FLAG_CLARITY
        if (grid != null && e.dehazeActive) flags = flags or DetailShaders.FLAG_DEHAZE
        val finalProgram = finalProgram(flags)
        GLES30.glUseProgram(finalProgram)
        bindRegion(finalProgram, region)
        fun loc(name: String) = uniform(finalProgram, name)
        GLES30.glUniform4f(loc("uSharp"), p.sharpenGain, p.coring2, p.haloInv, p.gradientScale)
        GLES30.glUniform2f(loc("uMask"), p.maskLow, p.maskHigh)
        GLES30.glUniform2f(loc("uTexture"), e.textureGain, e.textureEdge2)
        val half = HalfRegion.of(region)
        GLES30.glUniform2i(loc("uHalfOrigin"), half.x, half.y)
        GLES30.glUniform2i(loc("uHalfImage"), half.imageWidth, half.imageHeight)
        GLES30.glUniform2i(loc("uHalfSize"), half.width, half.height)
        GLES30.glUniform1f(loc("uFineRange"), p.fineRange)
        if (grid != null && e.gridActive) {
            grid.bindApply(finalProgram, e.clarityGain, e.dehazeOmega, e.dehazeBeta)
        } else {
            for (unit in intArrayOf(EffectsPasses.UNIT_CELLS, EffectsPasses.UNIT_COEF, EffectsPasses.UNIT_AIR)) {
                bindUnit(unit, lab)
            }
        }
        bindUnit(UNIT_LUM, lum?.texture ?: lab)
        bindUnit(UNIT_BLUR, if (blurOn) t.blurB.texture else lab)
        bindUnit(UNIT_HALF_S, chromaResult?.texture ?: lab)
        bindUnit(UNIT_HALF_D, if (chromaResult != null) t.halfD.texture else lab)
        draw(out.fbo, lab, region.width, region.height)
        lastPasses++
        profile?.invoke("detail final pass")
        GlSupport.checkError("detail passes")
        return out
    }

    /** The luminance levels into [t]'s luminance targets; returns the one holding `(L, acc)` of the last level. */
    private fun lumLevels(p: DetailParams, lab: Int, t: Targets, region: Region): GlTarget {
        var src = lab
        var first = true
        var dst = t.lumA
        var spare = t.lumB
        var result = t.lumA
        for (i in 0 until DetailParams.LEVELS) {
            if (!p.lumLevelActive(i)) continue
            GLES30.glUseProgram(lumHProgram)
            bindRegion(lumHProgram, region)
            GLES30.glUniform1i(uniform(lumHProgram, "uStep"), p.lumStep[i])
            GLES30.glUniform1f(uniform(lumHProgram, "uEdge"), p.lumEdge[i])
            draw(t.lumH.fbo, src, region.width, region.height)
            GLES30.glUseProgram(lumVProgram)
            bindRegion(lumVProgram, region)
            GLES30.glUniform1i(uniform(lumVProgram, "uStep"), p.lumStep[i])
            GLES30.glUniform1f(uniform(lumVProgram, "uEdge"), p.lumEdge[i])
            GLES30.glUniform1f(uniform(lumVProgram, "uTau"), p.lumTau[i])
            GLES30.glUniform1i(uniform(lumVProgram, "uFirst"), if (first) 1 else 0)
            bindUnit(1, src)
            draw(dst.fbo, t.lumH.texture, region.width, region.height)
            lastPasses += 2
            result = dst
            src = dst.texture
            first = false
            val swap = dst
            dst = spare
            spare = swap
        }
        return result
    }

    /** The binned copy and the colour levels; returns the target holding the last level (the copy when none runs). */
    private fun chromaLevels(p: DetailParams, lab: Int, t: Targets, region: Region): GlTarget {
        val half = HalfRegion.of(region)
        GLES30.glUseProgram(downProgram)
        bindRegion(downProgram, region)
        GLES30.glUniform2i(uniform(downProgram, "uHalfOrigin"), half.x, half.y)
        draw(t.halfD.fbo, lab, half.width, half.height)
        lastPasses++
        var src = t.halfD
        var dst = t.halfA
        GLES30.glUseProgram(chromaProgram)
        GLES30.glUniform2i(uniform(chromaProgram, "uOrigin"), half.x, half.y)
        GLES30.glUniform2i(uniform(chromaProgram, "uImage"), half.imageWidth, half.imageHeight)
        GLES30.glUniform2i(uniform(chromaProgram, "uSize"), half.width, half.height)
        val step = uniform(chromaProgram, "uStep")
        val chroma = uniform(chromaProgram, "uChroma")
        for (i in 0 until DetailParams.LEVELS) {
            if (!p.chromaLevelActive(i)) continue
            GLES30.glUniform1i(step, p.chromaStep[i])
            GLES30.glUniform3f(chroma, p.chromaRange, p.chromaMix[i], p.chromaLuma)
            draw(dst.fbo, src.texture, half.width, half.height)
            lastPasses++
            src = dst
            dst = if (src == t.halfA) t.halfB else t.halfA
        }
        return src
    }

    @Suppress("LongParameterList")
    private fun blur(p: DetailParams, e: EffectsParams, input: Int, fromLum: Boolean, t: Targets, region: Region) {
        GLES30.glUseProgram(blurProgram)
        bindRegion(blurProgram, region)
        val radius = maxOf(p.blur.size, e.textureBlur.size) - 1
        val ws = FloatArray(radius + 1) { p.blur.getOrElse(it) { 0f } }
        val wt = FloatArray(radius + 1) { e.textureBlur.getOrElse(it) { 0f } }
        GLES30.glUniform1fv(uniform(blurProgram, "uWs"), ws.size, ws, 0)
        GLES30.glUniform1fv(uniform(blurProgram, "uWt"), wt.size, wt, 0)
        GLES30.glUniform1i(uniform(blurProgram, "uRadius"), radius)
        GLES30.glUniform1f(uniform(blurProgram, "uRange"), e.textureRange)
        val dir = uniform(blurProgram, "uDir")
        val mode = uniform(blurProgram, "uMode")
        GLES30.glUniform2i(dir, 1, 0)
        GLES30.glUniform1i(mode, if (fromLum) 0 else 1)
        draw(t.blurA.fbo, input, region.width, region.height)
        GLES30.glUniform2i(dir, 0, 1)
        GLES30.glUniform1i(mode, 2)
        draw(t.blurB.fbo, t.blurA.texture, region.width, region.height)
        lastPasses += 2
    }

    private fun bindRegion(program: Int, region: Region) {
        GLES30.glUniform2i(uniform(program, "uOrigin"), region.x, region.y)
        GLES30.glUniform2i(uniform(program, "uImage"), region.imageWidth, region.imageHeight)
        GLES30.glUniform2i(uniform(program, "uSize"), region.width, region.height)
    }

    private fun bindUnit(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun draw(fbo: Int, input: Int, width: Int, height: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, FULLSCREEN_VERTICES)
    }

    fun release() {
        locations.clear()
        for (p in intArrayOf(lumHProgram, lumVProgram, downProgram, chromaProgram, blurProgram)) {
            GLES30.glDeleteProgram(p)
        }
        for (p in finalPrograms.values) GLES30.glDeleteProgram(p)
        finalPrograms.clear()
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

    /** The half-resolution region of a [Region]: texel `J` covers image pixels `2J` and `2J + 1`. */
    data class HalfRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val imageWidth: Int,
        val imageHeight: Int,
    ) {
        companion object {
            fun of(r: Region): HalfRegion = HalfRegion(
                r.x / 2,
                r.y / 2,
                (r.x + r.width - 1) / 2 - r.x / 2 + 1,
                (r.y + r.height - 1) / 2 - r.y / 2 + 1,
                (r.imageWidth + 1) / 2,
                (r.imageHeight + 1) / 2,
            )

            /** The largest half-resolution region of a region of at most [size] x [size]. */
            fun maxSize(size: Int): Int = size / 2 + 1
        }
    }

    private companion object {
        const val FULLSCREEN_VERTICES = 3

        // Units 8-11 (and the Effects grid's 12-14): clear of the develop stage's (0-2, the curve table on
        // ColourToolShaders.CURVE_UNIT), which the tiled renderer binds once per render, not per tile.
        const val UNIT_LUM = 8
        const val UNIT_BLUR = 9
        const val UNIT_HALF_S = 10
        const val UNIT_HALF_D = 11
    }
}
