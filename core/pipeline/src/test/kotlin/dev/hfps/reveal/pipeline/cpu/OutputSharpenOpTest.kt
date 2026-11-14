package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.export.OutputSharpening
import dev.hfps.reveal.pipeline.export.SharpenAmount
import dev.hfps.reveal.pipeline.export.SharpenMedium
import kotlin.math.abs
import org.junit.Test

class OutputSharpenOpTest {
    private val standard = OutputSharpenOp.params(OutputSharpening())

    @Test
    fun `radius and amount follow the medium and the level`() {
        val screen = OutputSharpenOp.params(OutputSharpening(SharpenMedium.SCREEN, SharpenAmount.STANDARD))
        val glossy = OutputSharpenOp.params(OutputSharpening(SharpenMedium.GLOSSY, SharpenAmount.STANDARD))
        val matte = OutputSharpenOp.params(OutputSharpening(SharpenMedium.MATTE, SharpenAmount.STANDARD))
        assertThat(screen.radius).isEqualTo(2)
        assertThat(glossy.radius).isEqualTo(3)
        assertThat(matte.radius).isEqualTo(3)
        assertThat(matte.radius).isAtMost(OutputSharpenOp.MAX_RADIUS)
        assertThat(screen.amount).isLessThan(glossy.amount)
        assertThat(glossy.amount).isLessThan(matte.amount)
        for (medium in SharpenMedium.entries) {
            val amounts = SharpenAmount.entries.map { OutputSharpenOp.params(OutputSharpening(medium, it)).amount }
            assertThat(amounts).isInStrictOrder()
        }
        assertThat(OutputSharpenOp.radius(null)).isEqualTo(0)
    }

    @Test
    fun `weights are a normalised gaussian (golden values)`() {
        for (medium in SharpenMedium.entries) {
            val p = OutputSharpenOp.params(OutputSharpening(medium))
            val sum = p.weights[0] + 2 * (1..p.radius).sumOf { p.weights[it].toDouble() }
            assertThat(sum).isWithin(1e-6).of(1.0)
            for (k in 1..p.radius) assertThat(p.weights[k]).isLessThan(p.weights[k - 1])
        }
        // Screen: sigma 0.6, w(k) ~ exp(-k^2 / 0.72); w0 = 1 / (1 + 2 (e^-1.3889 + e^-5.5556)).
        val screen = OutputSharpenOp.params(OutputSharpening(SharpenMedium.SCREEN))
        val e1 = Math.exp(-1 / 0.72)
        val e2 = Math.exp(-4 / 0.72)
        assertThat(screen.weights[0].toDouble()).isWithin(1e-6).of(1 / (1 + 2 * (e1 + e2)))
    }

    @Test
    fun `a flat image stays flat`() {
        val rgb = FloatArray(20 * 10 * 3) { i -> floatArrayOf(0.2f, 0.4f, 0.1f)[i % 3] }
        val out = OutputSharpenOp.apply(rgb, 20, 10, standard)
        for (i in rgb.indices) assertThat(out[i].toDouble()).isWithin(1e-6).of(rgb[i].toDouble())
    }

    @Test
    fun `an edge gains contrast, limited, and keeps its hue`() {
        val w = 24
        val h = 4
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (x < w / 2) 0.1f else 0.4f
                val i = (y * w + x) * 3
                rgb[i] = v
                rgb[i + 1] = v * 0.8f
                rgb[i + 2] = v * 0.5f
            }
        }
        val out = OutputSharpenOp.apply(rgb, w, h, standard)
        val dark = (1 * w + w / 2 - 1) * 3
        val light = (1 * w + w / 2) * 3
        // Darker side darker, lighter side lighter.
        assertThat(out[dark]).isLessThan(rgb[dark])
        assertThat(out[light]).isGreaterThan(rgb[light])
        // Ratios between channels are unchanged (lightness only).
        assertThat((out[light + 1] / out[light]).toDouble()).isWithin(1e-5).of(0.8)
        assertThat((out[dark + 2] / out[dark]).toDouble()).isWithin(1e-5).of(0.5)
        // The change in cube-root lightness never exceeds the limit.
        for (i in 0 until w * h) {
            val before = OutputSharpenOp.lightness(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2])
            val after = OutputSharpenOp.lightness(out[i * 3], out[i * 3 + 1], out[i * 3 + 2])
            assertThat(abs(after - before)).isAtMost(OutputSharpenOp.LIMIT + 1e-5f)
        }
        // Far from the edge nothing moves.
        assertThat(out[(1 * w + 2) * 3].toDouble()).isWithin(1e-5).of(rgb[(1 * w + 2) * 3].toDouble())
    }

    @Test
    fun `black pixels stay black`() {
        val rgb = FloatArray(8 * 8 * 3)
        rgb[(4 * 8 + 4) * 3] = 0.5f
        val out = OutputSharpenOp.apply(rgb, 8, 8, standard)
        assertThat(out[0]).isEqualTo(0f)
        assertThat(out[(4 * 8 + 3) * 3 + 1]).isEqualTo(0f)
    }
}
