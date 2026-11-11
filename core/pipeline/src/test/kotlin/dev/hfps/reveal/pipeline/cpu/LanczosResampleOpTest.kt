package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

class LanczosResampleOpTest {
    @Test
    fun `kernel is one at zero, zero at the integers and outside three lobes`() {
        assertThat(LanczosResampleOp.kernel(0f)).isEqualTo(1f)
        for (k in 1..2) assertThat(abs(LanczosResampleOp.kernel(k.toFloat()))).isLessThan(1e-6f)
        assertThat(LanczosResampleOp.kernel(3f)).isEqualTo(0f)
        assertThat(LanczosResampleOp.kernel(-4f)).isEqualTo(0f)
        // Golden value: L(0.5) = 3 sin(pi/2) sin(pi/6) / (pi/2)^2 = 6 / pi^2.
        assertThat(LanczosResampleOp.kernel(0.5f).toDouble()).isWithin(1e-6).of(6.0 / (Math.PI * Math.PI))
    }

    @Test
    fun `a flat image stays flat`() {
        val rgb = FloatArray(40 * 30 * 3) { 0.25f }
        val out = LanczosResampleOp.resample(rgb, 40, 30, 0, 0, 40, 30, 13, 7)
        out.forEach { assertThat(it.toDouble()).isWithin(1e-6).of(0.25) }
    }

    @Test
    fun `a linear ramp keeps its mean and slope`() {
        val w = 300
        val rgb = FloatArray(w * 3 * 3)
        for (y in 0 until 3) for (x in 0 until w) for (c in 0 until 3) rgb[(y * w + x) * 3 + c] = x / (w - 1f)
        val out = LanczosResampleOp.resample(rgb, w, 3, 0, 0, w, 3, 100, 1)
        // Away from the edges, output pixel o samples the source centre 3o + 1.
        for (o in 10 until 90) {
            assertThat(out[o * 3].toDouble()).isWithin(1e-4).of((3 * o + 1) / (w - 1.0))
        }
    }

    @Test
    fun `downscaling removes frequencies the output cannot hold`() {
        // A sine at 0.4 cycles per source pixel cannot survive a 4x reduction (output Nyquist 0.125): no aliasing.
        val w = 400
        val rgb = FloatArray(w * 3)
        for (x in 0 until w) for (c in 0 until 3) rgb[x * 3 + c] = 0.5f + 0.4f * sin(2 * Math.PI * 0.4 * x).toFloat()
        val out = LanczosResampleOp.resample(rgb, w, 1, 0, 0, w, 1, 100, 1)
        for (o in 5 until 95) assertThat(out[o * 3].toDouble()).isWithin(0.02).of(0.5)
    }
}
