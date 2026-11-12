@file:Suppress("MagicNumber", "TooManyFunctions")

package dev.hfps.reveal.ml.refine

import android.opengl.GLES30
import dev.hfps.reveal.ml.Mask
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.tensor.Resample
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The whole guided filter on the GPU (docs/adr/0021 "Edge refinement", "M4/C"): the coefficients at the reduced
 * size ([coefficients]: explicit-weight resampling, the window statistics, separable clamped box means, the 3 x 3
 * solve, the box mean of the coefficients) and the full-resolution apply pass ([apply]), the same maths as
 * [GuidedRefiner] (the CPU reference) pass for pass.
 *
 * Every method must run on a thread with a current OpenGL ES 3.0 context that can render to RGBA32F
 * (`EXT_color_buffer_float`, which Reveal's renderer requires anyway). Memory: the coefficient grid is `guide /
 * subsample`; its passes hold about 9 RGBA32F textures of that size at once (16 bytes a pixel: 600 x 400 = 35 MB,
 * 1500 x 1000 = 216 MB), plus the two one-axis resample intermediates while the inputs shrink. So the coefficients are
 * computed at the preview's scale and applied at any size (export included): `apply` upsamples them.
 */
class GpuGuidedRefiner : AutoCloseable {
    /** Coefficients on the GPU: an RGBA32F texture (a.rgb, b) of [width] x [height], owned by the caller. */
    class CoefTexture(val texture: Int, val width: Int, val height: Int)

    private var programs: Programs? = null
    private val fbo = IntArray(1)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).flip() }

    private class Programs(
        val resample: Int,
        val products: Int,
        val box4: Int,
        val box1: Int,
        val solve: Int,
        val apply: Int,
    )

    private fun programs(): Programs = programs ?: Programs(
        resample = program(GuidedFilterShader.RESAMPLE_FRAG),
        products = program(GuidedFilterShader.PRODUCTS_FRAG),
        box4 = program(GuidedFilterShader.boxFrag(4)),
        box1 = program(GuidedFilterShader.boxFrag(1)),
        solve = program(GuidedFilterShader.SOLVE_FRAG),
        apply = program(GuidedFilterShader.APPLY_FRAG),
    ).also {
        programs = it
        GLES30.glGenFramebuffers(1, fbo, 0)
    }

    /**
     * The coefficients of the mask texture [mask] ([maskWidth] x [maskHeight], coverage in `.r`) against the guide
     * texture [guide] ([guideWidth] x [guideHeight], display RGB in 0..1), at `guide / subsample` like
     * [GuidedRefiner.coefficients]. The caller deletes the returned texture.
     */
    @Suppress("LongParameterList")
    fun coefficients(
        guide: Int,
        guideWidth: Int,
        guideHeight: Int,
        mask: Int,
        maskWidth: Int,
        maskHeight: Int,
        params: GuidedRefiner.Params,
    ): CoefTexture {
        val p = programs()
        val w = max(1, (guideWidth.toFloat() / params.subsample).roundToInt())
        val h = max(1, (guideHeight.toFloat() / params.subsample).roundToInt())
        val small = resample(p, guide, guideWidth, guideHeight, w, h)
        val maskSmall = resample(p, mask, maskWidth, maskHeight, w, h)
        val t = IntArray(4) { float4(w, h) }
        val u = IntArray(4) { float4(w, h) }
        draw(p.products, t, w, h) {
            bind(p.products, "uGuideSmall", 0, small)
            bind(p.products, "uMaskSmall", 1, maskSmall)
        }
        delete(small, maskSmall)
        box(p.box4, t, u, w, h, params.radius, axis = 0)
        box(p.box4, u, t, w, h, params.radius, axis = 1)
        val ab = float4(w, h)
        draw(p.solve, intArrayOf(ab), w, h) {
            for (k in 0 until 4) bind(p.solve, "uM$k", k, t[k])
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p.solve, "uEps"), params.eps)
        }
        box(p.box1, intArrayOf(ab), intArrayOf(u[0]), w, h, params.radius, axis = 0)
        box(p.box1, intArrayOf(u[0]), intArrayOf(ab), w, h, params.radius, axis = 1)
        deleteAll(t)
        deleteAll(u)
        return CoefTexture(ab, w, h)
    }

    /**
     * Draws the refined coverage of the guide texture [guide] ([width] x [height]) with [coef] into the currently
     * bound framebuffer (viewport [width] x [height]; coverage in every channel, alpha 1).
     */
    fun apply(guide: Int, width: Int, height: Int, coef: CoefTexture) {
        val p = programs()
        GLES30.glUseProgram(p.apply)
        bind(p.apply, "uGuide", 0, guide)
        bind(p.apply, "uCoef", 1, coef.texture)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(p.apply, "uSize"), width, height)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(p.apply, "uCoefSize"), coef.width, coef.height)
        GLES30.glViewport(0, 0, width, height)
        triangle()
    }

    /** [guide] and [mask] uploaded, refined on the GPU at the guide's size, read back (tests, offline use). */
    fun refine(guide: MlImage, mask: Mask, params: GuidedRefiner.Params): Mask {
        val g = uploadRgb(guide)
        val m = uploadPlane(mask.values, mask.width, mask.height)
        val coef = coefficients(g, guide.width, guide.height, m, mask.width, mask.height, params)
        val out = float4(guide.width, guide.height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        attach(intArrayOf(out))
        apply(g, guide.width, guide.height, coef)
        val rgba = read(guide.width, guide.height)
        delete(g, m, coef.texture, out)
        return Mask(guide.width, guide.height, FloatArray(guide.width * guide.height) { rgba[it * 4] })
    }

    /** The coefficients read back (`(a.rgb, b)` per pixel), for the parity test against [GuidedRefiner]. */
    fun read(coef: CoefTexture): GuidedRefiner.Coefficients {
        programs()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        attach(intArrayOf(coef.texture))
        return GuidedRefiner.Coefficients(coef.width, coef.height, read(coef.width, coef.height))
    }

    /** An RGBA32F texture of [image] (alpha 1). */
    fun uploadRgb(image: MlImage): Int {
        val n = image.width * image.height
        val data = FloatArray(n * 4)
        for (i in 0 until n) {
            data[i * 4] = image.rgb[i * 3]
            data[i * 4 + 1] = image.rgb[i * 3 + 1]
            data[i * 4 + 2] = image.rgb[i * 3 + 2]
            data[i * 4 + 3] = 1f
        }
        return float4(image.width, image.height, data)
    }

    /** An RGBA32F texture with [values] in `.r`. */
    fun uploadPlane(values: FloatArray, width: Int, height: Int): Int {
        val data = FloatArray(width * height * 4)
        for (i in values.indices) data[i * 4] = values[i]
        return float4(width, height, data)
    }

    override fun close() {
        programs?.let { p ->
            for (id in intArrayOf(p.resample, p.products, p.box4, p.box1, p.solve, p.apply)) GLES30.glDeleteProgram(id)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
        }
        programs = null
    }

    /** [src] resampled to [dw] x [dh] with `Resample.taps` (x then y, like `Resample.interleaved`). */
    private fun resample(p: Programs, src: Int, w: Int, h: Int, dw: Int, dh: Int): Int {
        val mid = float4(dw, h)
        resampleAxis(p, src, mid, Resample.taps(w, dw), dw, h, axis = 0)
        val out = float4(dw, dh)
        resampleAxis(p, mid, out, Resample.taps(h, dh), dw, dh, axis = 1)
        delete(mid)
        return out
    }

    @Suppress("LongParameterList")
    private fun resampleAxis(p: Programs, src: Int, dst: Int, taps: Resample.Taps, w: Int, h: Int, axis: Int) {
        val n = taps.start.size - 1
        var most = 1
        for (i in 0 until n) most = max(most, taps.start[i + 1] - taps.start[i])
        val table = FloatArray(n * most * 4)
        for (i in 0 until n) {
            for (k in taps.start[i] until taps.start[i + 1]) {
                val o = ((k - taps.start[i]) * n + i) * 4
                table[o] = taps.index[k].toFloat()
                table[o + 1] = taps.weight[k]
            }
        }
        val tapTex = float4(n, most, table)
        draw(p.resample, intArrayOf(dst), w, h) {
            bind(p.resample, "uSrc", 0, src)
            bind(p.resample, "uTapTex", 1, tapTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p.resample, "uTaps"), most)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p.resample, "uAxis"), axis)
        }
        delete(tapTex)
    }

    @Suppress("LongParameterList")
    private fun box(program: Int, src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, axis: Int) {
        draw(program, dst, w, h) {
            for (k in src.indices) bind(program, "uSrc$k", k, src[k])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uRadius"), radius)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uAxis"), axis)
            GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uSize"), w, h)
        }
    }

    private inline fun draw(program: Int, targets: IntArray, w: Int, h: Int, uniforms: () -> Unit) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        attach(targets)
        GLES30.glUseProgram(program)
        uniforms()
        GLES30.glViewport(0, 0, w, h)
        triangle()
    }

    private fun attach(targets: IntArray) {
        for (k in 0 until MAX_TARGETS) {
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0 + k,
                GLES30.GL_TEXTURE_2D,
                if (k < targets.size) targets[k] else 0,
                0,
            )
        }
        GLES30.glDrawBuffers(targets.size, IntArray(targets.size) { GLES30.GL_COLOR_ATTACHMENT0 + it }, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "guided filter target incomplete (RGBA32F rendering unsupported?)"
        }
    }

    private fun bind(program: Int, name: String, unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
    }

    private fun triangle() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun read(w: Int, h: Int): FloatArray {
        val buf = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder())
        GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "guided filter readback failed" }
        val out = FloatArray(w * h * 4)
        buf.asFloatBuffer().get(out)
        return out
    }

    private fun float4(w: Int, h: Int, data: FloatArray? = null): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val buf = data?.let {
            ByteBuffer.allocateDirect(it.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(it).flip()
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        return id[0]
    }

    private fun delete(vararg textures: Int) = deleteAll(textures)

    private fun deleteAll(textures: IntArray) = GLES30.glDeleteTextures(textures.size, textures, 0)

    private fun program(fragment: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val vs = shader(GLES30.GL_VERTEX_SHADER, GuidedFilterShader.VERTEX)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val MAX_TARGETS = 4
    }
}
