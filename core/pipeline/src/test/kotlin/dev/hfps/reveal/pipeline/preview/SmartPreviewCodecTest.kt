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
    private fun roundTrip(
        input: PipelineInput,
        jpeg: ByteArray = byteArrayOf(1, 2, 3),
        version: Int = SmartPreviewCodec.VERSION,
    ): SmartPreviewCodec.Decoded {
        val bytes = SmartPreviewCodec.encode(input, jpeg, input.width * 2, input.height * 2, version)
        assertThat(SmartPreviewCodec.isSmartPreview(bytes.copyOf(8))).isTrue()
        assertThat(SmartPreviewCodec.version(ByteArrayInputStream(bytes))).isEqualTo(version)
        return SmartPreviewCodec.decode(ByteArrayInputStream(bytes))
    }

    /** A flat grey field with Gaussian noise of relative [sigma] (a photo's grain), and a smooth clean gradient. */
    private fun noisy(width: Int, height: Int, level: Float, sigma: Double, seed: Long): PipelineInput {
        val random = java.util.Random(seed)
        val ramp = SyntheticImages.grayRamp(width, height)
        val px = FloatArray(width * height * 3) { (level * (1 + sigma * random.nextGaussian())).toFloat() }
        return PipelineInput(ramp.kind, width, height, px, ramp.cameraToXyz, ramp.asShotMultipliers)
    }

    @Test
    fun `previews written by 0_5_x (version 1) still open, with their metadata`() {
        val scene = SyntheticImages.scene(64, 40)
        val input = PipelineInput(
            scene.kind,
            scene.width,
            scene.height,
            scene.linearRgb,
            scene.cameraToXyz,
            scene.asShotMultipliers,
            saturation = 0.9f,
            noise = NoiseInfo(iso = 800, make = "Sony", model = "ZV-E10"),
        )
        val old = roundTrip(input, byteArrayOf(4, 4), SmartPreviewCodec.VERSION_1)
        assertThat(old.jpeg).isEqualTo(byteArrayOf(4, 4))
        assertThat(old.input.noise).isEqualTo(input.noise)
        val bytes = SmartPreviewCodec.encode(input, byteArrayOf(4, 4), 64, 40, SmartPreviewCodec.VERSION_1)
        assertThat(SmartPreviewCodec.readJpeg(ByteArrayInputStream(bytes))).isEqualTo(byteArrayOf(4, 4))
        assertThat(SmartPreviewCodec.size(ByteArrayInputStream(bytes))).isEqualTo(64 to 40)
        // Both versions develop alike on a clean scene.
        val recipe = Recipe(light = Light(exposureEv = 0.7f, contrast = 20f))
        val a = CpuDevelop.render(old.input, recipe)
        val b = CpuDevelop.render(roundTrip(input).input, recipe)
        var sum = 0L
        for (i in a.indices) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        assertThat(sum.toDouble() / a.size).isLessThan(0.5)
    }

    @Test
    fun `a smooth clean gradient keeps fine steps, no banding`() {
        // 2 x 1024: green from 2^-10 to 1 over ten stops, nothing to hide a coarse step in.
        val w = 1024
        val ramp = SyntheticImages.grayRamp(w, 2)
        val px = FloatArray(w * 2 * 3) { i ->
            val x = (i / 3) % w
            Math.pow(2.0, -10.0 + 10.0 * x / (w - 1)).toFloat()
        }
        val input = PipelineInput(ramp.kind, w, 2, px, ramp.cameraToXyz, ramp.asShotMultipliers)
        val out = roundTrip(input).input
        var worst = 0.0
        for (i in 0 until w * 2) {
            for (c in 0..2) worst = maxOf(worst, abs(ln(out.linearRgb[i * 3 + c] / px[i * 3 + c].toDouble()) / ln(2.0)))
        }
        // Within half of the finest green step and the finest chroma step (stops).
        assertThat(worst).isLessThan(0.0045)
        // Monotone: a gradient never steps back.
        for (x in 1 until w) assertThat(out.linearRgb[x * 3 + 1]).isAtLeast(out.linearRgb[(x - 1) * 3 + 1])
    }

    @Test
    fun `steps follow the photo's own noise and the rounding stays a fraction of it`() {
        val sigma = 0.02
        val input = noisy(256, 256, 0.18f, sigma, seed = 7)
        val v1 = SmartPreviewCodec.encode(input, ByteArray(0), 256, 256, SmartPreviewCodec.VERSION_1)
        val v2 = SmartPreviewCodec.encode(input, ByteArray(0), 256, 256)
        val out = SmartPreviewCodec.decode(ByteArrayInputStream(v2)).input
        var sq = 0.0
        for (i in 0 until 256 * 256) {
            val e = ln(out.linearRgb[i * 3 + 1] / input.linearRgb[i * 3 + 1].toDouble())
            sq += e * e
        }
        val rms = kotlin.math.sqrt(sq / (256 * 256))
        // The added error is well under the grain (a uniform rounding of step s has s / sqrt(12)).
        assertThat(rms).isLessThan(sigma / 2)
        assertThat(rms).isGreaterThan(sigma / 100) // coarser than the exact 16-bit codes of version 1
        assertThat(v2.size).isLessThan(v1.size * 6 / 10)
    }

    @Test
    fun `damaged quantiser tables are refused, never misread`() {
        val bytes = SmartPreviewCodec.encode(SyntheticImages.grayRamp(9, 7), ByteArray(0), 9, 7)
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("\"luma\":[") + "\"luma\":[".length
        // A table entry of 0 codes (same header length): refused.
        val zero = bytes.copyOf().also { it[at] = '0'.code.toByte() }
        if (bytes[at + 1] == ','.code.toByte()) {
            assertThrows(IOException::class.java) { SmartPreviewCodec.decode(ByteArrayInputStream(zero)) }
        }
        // Not JSON any more: refused as a bad header, not a parser crash.
        val broken = bytes.copyOf().also { it[at] = '}'.code.toByte() }
        assertThrows(IOException::class.java) { SmartPreviewCodec.decode(ByteArrayInputStream(broken)) }
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

    /**
     * What made folder photos fail as "No smart preview ...: IllegalArgumentException" on 0.5.0: JSON has no NaN or
     * infinity, and `roundToInt` refuses NaN. A sample that is not a number encodes as black; a lens profile with a
     * non-finite term is left out; a non-finite matrix is an `IOException` (a plain "cannot", never a crash).
     */
    @Test
    fun `non-finite samples and lens terms never fail an encode`() {
        val scene = SyntheticImages.scene(8, 6)
        val pixels = scene.linearRgb.copyOf().also {
            it[0] = Float.NaN
            it[4] = Float.POSITIVE_INFINITY
            it[8] = Float.NEGATIVE_INFINITY
        }
        val lens = LensfunProfile(
            "Lens", LensfunDistortion.PTLENS, listOf(0.01, Double.NaN, 0.003), LensfunTca.LINEAR, listOf(1.0, 1.0),
            listOf(-0.3, 0.1, -0.01), 1.5, 1.5, Double.POSITIVE_INFINITY,
        )
        val input = PipelineInput(
            scene.kind,
            scene.width,
            scene.height,
            pixels,
            scene.cameraToXyz,
            scene.asShotMultipliers,
            lens = lens,
        )
        val out = roundTrip(input).input
        assertThat(out.lens).isNull()
        assertThat(out.linearRgb[1]).isFinite()
        assertThat(out.linearRgb.all { it.isFinite() && it >= 0f }).isTrue()

        val bad = PipelineInput(
            scene.kind,
            scene.width,
            scene.height,
            scene.linearRgb,
            dev.hfps.reveal.pipeline.cpu.Mat3(Double.NaN, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            scene.asShotMultipliers,
        )
        assertThrows(IOException::class.java) { SmartPreviewCodec.encode(bad, ByteArray(0), 8, 6) }
    }

    @Test
    fun `the pixel size is read from the header alone`() {
        val bytes = SmartPreviewCodec.encode(SyntheticImages.grayRamp(9, 7), byteArrayOf(5, 6), 18, 14)
        assertThat(SmartPreviewCodec.size(ByteArrayInputStream(bytes))).isEqualTo(9 to 7)
        assertThrows(IOException::class.java) { SmartPreviewCodec.size(ByteArrayInputStream(ByteArray(64))) }
    }
}
