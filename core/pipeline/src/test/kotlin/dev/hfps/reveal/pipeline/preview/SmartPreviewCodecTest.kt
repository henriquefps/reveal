package dev.hfps.reveal.pipeline.preview

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.NoiseInfo
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.optics.LensfunDistortion
import dev.hfps.reveal.pipeline.optics.LensfunProfile
import dev.hfps.reveal.pipeline.optics.LensfunTca
import dev.hfps.reveal.pipeline.optics.SonyLensProfile
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertThrows
import org.junit.Test

class SmartPreviewCodecTest {
    private fun roundTrip(input: PipelineInput, jpeg: ByteArray = byteArrayOf(1, 2, 3)): SmartPreviewCodec.Decoded {
        val bytes = SmartPreviewCodec.encode(input, jpeg, input.width * 2, input.height * 2)
        assertThat(SmartPreviewCodec.isSmartPreview(bytes.copyOf(8))).isTrue()
        return SmartPreviewCodec.decode(ByteArrayInputStream(bytes))
    }

    @Test
    fun `metadata, matrix, white balance, noise and the jpeg survive`() {
        val scene = SyntheticImages.scene(64, 40)
        val input = PipelineInput(
            scene.kind,
            scene.width,
            scene.height,
            scene.linearRgb,
            scene.cameraToXyz,
            scene.asShotMultipliers,
            saturation = 0.93f,
            noise = NoiseInfo(iso = 3200, make = "Sony", model = "ZV-E10"),
        )
        val decoded = roundTrip(input, byteArrayOf(9, 8, 7))
        assertThat(decoded.jpeg).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(decoded.fullWidth).isEqualTo(128)
        assertThat(decoded.fullHeight).isEqualTo(80)
        val out = decoded.input
        assertThat(out.kind).isEqualTo(input.kind)
        assertThat(out.width).isEqualTo(64)
        assertThat(out.asShotMultipliers!!.toList()).isEqualTo(input.asShotMultipliers!!.toList())
        assertThat(out.saturation).isEqualTo(0.93f)
        assertThat(out.noise).isEqualTo(input.noise)
        for (r in 0..2) for (c in 0..2) assertThat(out.cameraToXyz[r, c]).isEqualTo(input.cameraToXyz[r, c])
    }

    @Test
    fun `green is kept to a fraction of a percent and colour to about one percent on a smooth scene`() {
        val input = SyntheticImages.scene(160, 100)
        val out = roundTrip(input).input
        var worstGreen = 0.0
        var sumColour = 0.0
        var n = 0
        for (i in 0 until input.width * input.height) {
            val g = input.linearRgb[i * 3 + 1]
            if (g > 0.01f) worstGreen = maxOf(worstGreen, abs(ln(out.linearRgb[i * 3 + 1] / g.toDouble())))
            for (c in intArrayOf(0, 2)) {
                val v = input.linearRgb[i * 3 + c]
                if (v > 0.01f) {
                    sumColour += abs(ln(out.linearRgb[i * 3 + c] / v.toDouble()))
                    n++
                }
            }
        }
        assertThat(worstGreen).isLessThan(0.001)
        // Half-resolution chroma: edges of the scene's rings blur the colour a little, the mean stays small.
        assertThat(sumColour / n).isLessThan(0.02)
    }

    @Test
    fun `a developed preview matches the developed original closely`() {
        val input = SyntheticImages.scene(96, 64)
        val recipe = Recipe(light = Light(exposureEv = 0.7f, contrast = 20f))
        val a = CpuDevelop.render(input, recipe)
        val b = CpuDevelop.render(roundTrip(input).input, recipe)
        var sum = 0L
        for (i in a.indices) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        // Mean absolute difference in 8-bit output levels.
        assertThat(sum.toDouble() / a.size).isLessThan(1.5)
    }

    @Test
    fun `out of range values clamp to zero below black and keep headroom above white`() {
        val input = SyntheticImages.outOfRange()
        val out = roundTrip(input).input
        assertThat(out.linearRgb.all { it >= 0f && it.isFinite() }).isTrue()
        val brightest = input.linearRgb.indices.filter { it % 3 == 1 }.maxBy { input.linearRgb[it] }
        assertThat(
            out.linearRgb[brightest],
        ).isWithin(input.linearRgb[brightest] * 0.001f).of(input.linearRgb[brightest])
    }

    @Test
    fun `lens profiles of both kinds survive`() {
        val scene = SyntheticImages.scene(8, 6)
        val sony =
            SonyLensProfile("E 16-50", intArrayOf(1, 2, 3), intArrayOf(4, 5), intArrayOf(6, 7), intArrayOf(8, 9, 10))
        assertThat(roundTrip(scene.withLens(sony)).input.lens).isEqualTo(sony)
        val lensfun = LensfunProfile(
            "Lens", LensfunDistortion.PTLENS, listOf(0.01, -0.02, 0.003), LensfunTca.LINEAR, listOf(1.0001, 0.9998),
            listOf(-0.3, 0.1, -0.01), 1.5, 1.5, 1.5,
        )
        assertThat(roundTrip(scene.withLens(lensfun)).input.lens).isEqualTo(lensfun)
    }

    @Test
    fun `the jpeg alone is read without the pixels, and junk is refused`() {
        val bytes = SmartPreviewCodec.encode(SyntheticImages.grayRamp(9, 7), byteArrayOf(5, 6), 9, 7)
        assertThat(SmartPreviewCodec.readJpeg(ByteArrayInputStream(bytes))).isEqualTo(byteArrayOf(5, 6))
        assertThrows(IOException::class.java) { SmartPreviewCodec.decode(ByteArrayInputStream(ByteArray(64))) }
        assertThrows(IOException::class.java) {
            SmartPreviewCodec.decode(ByteArrayInputStream(bytes.copyOf(bytes.size - 20)))
        }
    }
}
