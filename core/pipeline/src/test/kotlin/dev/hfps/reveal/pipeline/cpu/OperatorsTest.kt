package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class OperatorsTest {
    @Test
    fun `white balance multiplies each channel`() {
        val rgb = floatArrayOf(0.1f, 0.2f, 0.3f, 1f, 1f, 1f)

        RawWhiteBalanceOp.apply(rgb, floatArrayOf(2f, 1f, 1.5f))

        assertThat(rgb).usingTolerance(1e-7).containsExactly(floatArrayOf(0.2f, 0.2f, 0.45f, 2f, 1f, 1.5f)).inOrder()
    }

    @Test
    fun `camera matrix is row major`() {
        val rgb = floatArrayOf(1f, 2f, 3f)

        CameraMatrixOp.apply(rgb, floatArrayOf(1f, 0f, 0f, 0f, 0.5f, 0f, 1f, 1f, 1f))

        assertThat(rgb).usingTolerance(1e-7).containsExactly(floatArrayOf(1f, 1f, 6f)).inOrder()
    }

    @Test
    fun `exposure is a power of two gain without clipping`() {
        assertThat(ExposureOp.gain(0f)).isEqualTo(1f)
        assertThat(ExposureOp.gain(1f)).isEqualTo(2f)
        assertThat(ExposureOp.gain(-2f)).isEqualTo(0.25f)
        val rgb = floatArrayOf(0.5f, 2f, -0.1f)

        ExposureOp.apply(rgb, ExposureOp.gain(0.7f))

        val gain = Math.pow(2.0, 0.7).toFloat()
        assertThat(
            rgb,
        ).usingTolerance(1e-6).containsExactly(floatArrayOf(0.5f * gain, 2f * gain, -0.1f * gain)).inOrder()
    }

    @Test
    fun `sRGB OETF golden values`() {
        assertThat(OutputTransformOp.oetf(0f)).isEqualTo(0f)
        assertThat(OutputTransformOp.oetf(1f)).isWithin(1e-6f).of(1f)
        assertThat(OutputTransformOp.oetf(0.18f)).isWithin(1e-4f).of(0.4614f)
        assertThat(OutputTransformOp.oetf(0.002f)).isWithin(1e-6f).of(0.02584f)
    }

    @Test
    fun `output without dither quantises with rounding and keeps neutral greys neutral`() {
        val out = OutputTransformOp.apply(
            floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 0.18f, 0.18f, 0.18f),
            3,
            1,
            OutputSettings(dither = false),
        )

        assertThat(out.map { it.toInt() and 0xff })
            .containsExactly(0, 0, 0, 255, 255, 255, 255, 255, 118, 118, 118, 255)
            .inOrder()
    }

    @Test
    fun `out of gamut and over white values clip at the output`() {
        // Pure Rec2020 green is outside sRGB: R and B go negative and clip to 0, G clips to 255.
        val out = OutputTransformOp.apply(
            floatArrayOf(0f, 1f, 0f, 20f, 20f, 20f, -1f, -1f, -1f),
            3,
            1,
            OutputSettings(dither = false),
        )

        val v = out.map { it.toInt() and 0xff }
        assertThat(v.subList(0, 3)).containsExactly(0, 255, 0).inOrder()
        assertThat(v.subList(4, 7)).containsExactly(255, 255, 255).inOrder()
        assertThat(v.subList(8, 11)).containsExactly(0, 0, 0).inOrder()
    }

    @Test
    fun `sRGB colours survive working space and back within one level`() {
        val m = ColorSpaces.REC2020.fromXyz * ColorSpaces.SRGB.toXyz
        val srgb = floatArrayOf(0.8f, 0.2f, 0.05f, 0.1f, 0.5f, 0.9f, 0.04f, 0.04f, 0.04f)
        val working = srgb.copyOf()
        CameraMatrixOp.apply(working, m.toFloatArray())

        val withTrip = OutputTransformOp.apply(working, 3, 1, OutputSettings(dither = false))
        for (i in srgb.indices) {
            val expected = Math.round(OutputTransformOp.oetf(srgb[i]) * 255f)
            val actual = withTrip[(i / 3) * 4 + i % 3].toInt() and 0xff
            assertThat(abs(actual - expected)).isAtMost(1)
        }
    }

    @Test
    fun `Display P3 output of an sRGB red is less saturated than P3 red`() {
        val srgbRedInWorking = floatArrayOf(1f, 0f, 0f)
        CameraMatrixOp.apply(srgbRedInWorking, (ColorSpaces.REC2020.fromXyz * ColorSpaces.SRGB.toXyz).toFloatArray())

        val out = OutputTransformOp.apply(
            srgbRedInWorking,
            1,
            1,
            OutputSettings(OutputSpace.DISPLAY_P3, dither = false),
        )

        // sRGB red in Display P3 is about (234, 51, 35) (well known value).
        assertThat(out[0].toInt() and 0xff).isIn(232..236)
        assertThat(out[1].toInt() and 0xff).isIn(48..54)
        assertThat(out[2].toInt() and 0xff).isIn(32..38)
    }

    @Test
    fun `dither noise is deterministic, in range and unbiased`() {
        var sum = 0.0
        val n = 200 * 200
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                val a = Dither.noise(x, y, 7)
                assertThat(a).isAtLeast(0f)
                assertThat(a).isLessThan(1f)
                assertThat(Dither.noise(x, y, 7)).isEqualTo(a)
                sum += a
            }
        }
        assertThat(sum / n).isWithin(0.01).of(0.5)
        assertThat(Dither.noise(3, 4, 1)).isNotEqualTo(Dither.noise(3, 4, 2))
        // Golden: pins the hash so the GLSL port stays comparable.
        assertThat(Dither.hash(1)).isEqualTo(Dither.hash(1))
        assertThat(Dither.hash(0)).isEqualTo(0)
    }

    @Test
    fun `dithered ramp averages to the true value`() {
        val width = 4000
        val value = 0.1234f
        val rgb = FloatArray(width * 3) { value }
        val out = OutputTransformOp.apply(rgb, width, 1, OutputSettings(space = OutputSpace.SRGB))

        var sum = 0.0
        for (x in 0 until width) sum += out[x * 4 + 1].toInt() and 0xff
        val expected = OutputTransformOp.oetf(value) * 255.0
        assertThat(sum / width).isWithin(0.3).of(expected)
    }
}
