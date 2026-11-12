package dev.hfps.reveal.ml

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.refine.GuidedFilterShader
import dev.hfps.reveal.ml.refine.GuidedRefiner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full-resolution half of the guided filter on the GPU (docs/adr/0021 "Edge refinement"): the fragment pass
 * matches the CPU reference (6 MP), and its cost is logged (`REVEAL_ML refine ...`), next to the CPU cost
 * of the reduced-resolution coefficients.
 */
@RunWith(AndroidJUnit4::class)
class GuidedFilterGpuTest {
    /** A guide with soft gradients and thin dark strands against a bright "sky" (hair or branches). */
    private fun guide(w: Int, h: Int): MlImage {
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                val strand = (x / 7 + y / 40) % 9 == 0
                val sky = y < h / 2
                val base = if (sky) 0.85f else 0.25f + 0.1f * sin(x / 300f)
                val v = if (strand && sky) 0.15f else base
                // Quantised like the 8-bit texture the GPU reads.
                rgb[i] = q8(v)
                rgb[i + 1] = q8(v * 0.95f)
                rgb[i + 2] = q8(if (sky) 0.95f else v * 0.8f)
            }
        }
        return MlImage(w, h, rgb)
    }

    private fun q8(v: Float) = (v * 255f).roundToInt() / 255f

    /** A model-like mask: soft, coarse, roughly on the sky line. */
    private fun coarseMask() = Mask(64, 64, FloatArray(64 * 64) { i -> ((i / 64 - 30f) / 3f).coerceIn(0f, 1f) })

    @Test
    fun applyPassMatchesTheCpu() {
        val guide = guide(3000, 2000)
        val k = GuidedRefiner.coefficients(guide, coarseMask(), GuidedRefiner.Params(subsample = 4))
        val cpu = GuidedRefiner.apply(guide, k)
        val gl = Gl()
        try {
            val (out, _) = gl.apply(guide.width, guide.height, bytes(guide), k, 1)
            var maxErr = 0f
            for (i in cpu.values.indices) maxErr = maxOf(maxErr, abs(out[i] - cpu.values[i]))
            Log.i(TAG, "refine parity 3000x2000 maxErr=${"%.4f".format(Locale.ROOT, maxErr)}")
            // 8-bit readback: within one step plus fp32 differences.
            assertThat(maxErr).isLessThan(2.5f / 255f)
        } finally {
            gl.release()
        }
    }

    /**
     * The cost at 24 MP: the CPU coefficients at the reduced size (6000 x 4000 / 4 = 1500 x 1000) and the GPU pass at
     * full size.
     */
    @Test
    fun costAt24Mp() {
        val small = guide(1500, 1000)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val k = GuidedRefiner.coefficients(small, coarseMask(), GuidedRefiner.Params(subsample = 1))
        val t1 = SystemClock.elapsedRealtimeNanos()
        val w = 6000
        val h = 4000
        val full = ByteBuffer.allocateDirect(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val src = ((y / 4) * 1500 + x / 4) * 3
                full.put((small.rgb[src] * 255f).roundToInt().toByte())
                full.put((small.rgb[src + 1] * 255f).roundToInt().toByte())
                full.put((small.rgb[src + 2] * 255f).roundToInt().toByte())
                full.put(0xff.toByte())
            }
        }
        full.flip()
        val gl = Gl()
        try {
            val (_, gpuMs) = gl.apply(w, h, full, k, RUNS, readBack = false)
            Log.i(
                TAG,
                "refine 6000x4000 coefficients(1500x1000, CPU)=${ms(
                    t1 - t0,
                )} ms applyGpu=${"%.1f".format(Locale.ROOT, gpuMs)} ms",
            )
        } finally {
            gl.release()
        }
    }

    private fun bytes(guide: MlImage): ByteBuffer {
        val b = ByteBuffer.allocateDirect(guide.width * guide.height * 4)
        for (i in 0 until guide.width * guide.height) {
            for (c in 0 until 3) b.put((guide.rgb[i * 3 + c] * 255f).roundToInt().coerceIn(0, 255).toByte())
            b.put(0xff.toByte())
        }
        return b.flip() as ByteBuffer
    }

    private fun ms(n: Long) = "%.0f".format(Locale.ROOT, n / 1e6)

    /** A minimal offscreen GLES 3 context for the pass. */
    private class Gl {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val surface: android.opengl.EGLSurface

        init {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1))
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0)
            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            surface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
        }

        fun apply(
            w: Int,
            h: Int,
            bytes: ByteBuffer,
            k: GuidedRefiner.Coefficients,
            runs: Int,
            readBack: Boolean = true,
        ): Pair<FloatArray, Double> {
            val program = program(GuidedFilterShader.VERTEX, GuidedFilterShader.APPLY_FRAG)
            val tex = IntArray(3)
            GLES30.glGenTextures(3, tex, 0)
            // Guide: RGBA8 (display values, not linearised).
            texture(tex[0], GLES30.GL_RGBA8, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            val coef = ByteBuffer.allocateDirect(k.rgba.size * 4).order(ByteOrder.nativeOrder())
            coef.asFloatBuffer().put(k.rgba)
            texture(tex[1], GLES30.GL_RGBA32F, k.width, k.height, GLES30.GL_RGBA, GLES30.GL_FLOAT, coef)
            texture(tex[2], GLES30.GL_RGBA8, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            val fbo = IntArray(1)
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                tex[2],
                0,
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[1])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uGuide"), 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCoef"), 1)
            GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uSize"), w, h)
            GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uCoefSize"), k.width, k.height)
            val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            quad.put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f, -1f, -1f)).flip()
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glFinish()
            val t0 = SystemClock.elapsedRealtimeNanos()
            repeat(runs) { GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3) }
            GLES30.glFinish()
            val perRun = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 / runs
            val out = if (readBack) {
                val read = ByteBuffer.allocateDirect(w * h * 4)
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, read)
                FloatArray(w * h) { (read.get(it * 4).toInt() and 0xff) / 255f }
            } else {
                FloatArray(0)
            }
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(3, tex, 0)
            GLES30.glDeleteProgram(program)
            return out to perRun
        }

        private fun texture(id: Int, internal: Int, w: Int, h: Int, format: Int, type: Int, data: ByteBuffer?) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, data)
        }

        private fun program(vs: String, fs: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src)
                GLES30.glCompileShader(s)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { GLES30.glGetShaderInfoLog(s) }
                return s
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { GLES30.glGetProgramInfoLog(p) }
            return p
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }

        private companion object {
            const val EGL_OPENGL_ES3_BIT = 0x40
        }
    }

    private companion object {
        const val TAG = "REVEAL_ML"
        const val RUNS = 10
    }
}
