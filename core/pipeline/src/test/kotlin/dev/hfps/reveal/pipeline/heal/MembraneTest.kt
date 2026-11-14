package dev.hfps.reveal.pipeline.heal

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

/** The cascadic multigrid membrane (docs/adr/0023, "Heal"). */
class MembraneTest {
    private fun disk(n: Int, r: Float): ByteArray = ByteArray(n * n) {
        val x = it % n + 0.5f - n / 2f
        val y = it / n + 0.5f - n / 2f
        if (x * x + y * y < r * r) Membrane.INSIDE else Membrane.FIXED
    }

    @Test
    fun `a linear boundary gives the plane`() {
        val n = 101
        val state = disk(n, 45f)
        val values = FloatArray(n * n * 3)
        for (i in 0 until n * n) {
            val x = (i % n).toFloat()
            val y = (i / n).toFloat()
            for (c in 0..2) values[i * 3 + c] = if (state[i] == Membrane.FIXED) 0.01f * x - 0.02f * y + c else 0f
        }
        Membrane.solve(n, n, state, values)
        var worst = 0f
        for (i in 0 until n * n) {
            if (state[i] != Membrane.INSIDE) continue
            val x = (i % n).toFloat()
            val y = (i / n).toFloat()
            for (c in 0..2) worst = maxOf(worst, abs(values[i * 3 + c] - (0.01f * x - 0.02f * y + c)))
        }
        // The boundary spans 1.4 units; the cascade gets within 2 % of it.
        assertThat(worst).isLessThan(0.03f)
    }

    @Test
    fun `the result is nearly harmonic`() {
        val n = 64
        val state = disk(n, 28f)
        val values = FloatArray(n * n * 3) { if (state[it / 3] == Membrane.FIXED) ((it * 7919) % 101) / 100f else 0f }
        Membrane.solve(n, n, state, values)
        assertThat(Membrane.residual(n, n, state, values)).isLessThan(0.02f)
    }

    @Test
    fun `absent cells reflect`() {
        // A strip: fixed 0 on the left, 1 on the right, absent above and below: a linear ramp across.
        val w = 40
        val h = 6
        val state = ByteArray(w * h) { if (it % w == 0 || it % w == w - 1) Membrane.FIXED else Membrane.INSIDE }
        val values = FloatArray(w * h * 3) { if ((it / 3) % w == w - 1) 1f else 0f }
        Membrane.solve(w, h, state, values)
        val mid = (3 * w + w / 2) * 3
        assertThat(values[mid]).isWithin(0.03f).of((w / 2).toFloat() / (w - 1))
    }
}
