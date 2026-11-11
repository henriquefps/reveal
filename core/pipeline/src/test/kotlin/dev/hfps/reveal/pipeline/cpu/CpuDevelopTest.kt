package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PIPELINE_VERSION
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import java.util.zip.CRC32
import kotlin.math.abs
import org.junit.Test

class CpuDevelopTest {
    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xff

    @Test
    fun `gradient with default recipe encodes as sRGB below the knee`() {
        val input = SyntheticImages.grayRamp(256, 1, max = 0.8f)

        val out = CpuDevelop.render(input, Recipe(), OutputSettings(dither = false))

        for (x in 0 until 256) {
            val linear = 0.8f * x / 255f
            val expected = Math.round(OutputTransformOp.oetf(linear) * 255f)
            assertThat(abs(out.u(x * 4) - expected)).isAtMost(1)
            assertThat(out.u(x * 4 + 1)).isEqualTo(out.u(x * 4))
            assertThat(out.u(x * 4 + 2)).isEqualTo(out.u(x * 4))
            assertThat(out.u(x * 4 + 3)).isEqualTo(255)
        }
    }

    @Test
    fun `one stop of exposure raises mid grey by one stop before the curve`() {
        val input = SyntheticImages.grayRamp(2, 1, max = 0.18f)
        val plain = CpuDevelop.renderLinear(input, Recipe())
        val plusOne = CpuDevelop.renderLinear(input, Recipe(light = Light(1f)))

        assertThat(plain[3]).isWithin(1e-5f).of(0.18f)
        assertThat(plusOne[3]).isWithin(1e-5f).of(0.36f)
    }

    @Test
    fun `colour checker neutral patches stay neutral and match the sRGB source`() {
        val input = SyntheticImages.colorChecker(cell = 1)

        val out = CpuDevelop.render(input, Recipe(), OutputSettings(dither = false))

        // Row 3 holds the grey ramp: white 243 ... black 52 (source 8-bit values), within one level.
        val expected = listOf(243, 200, 160, 122, 85, 52)
        for (i in expected.indices) {
            val o = (18 + i) * 4
            assertThat(abs(out.u(o) - out.u(o + 1))).isAtMost(1)
            assertThat(abs(out.u(o + 2) - out.u(o + 1))).isAtMost(1)
            // Patches below the curve's knee return the source values; 243 (0.896 linear) is on the shoulder.
            if (expected[i] < 200) assertThat(abs(out.u(o) - expected[i])).isAtMost(1)
        }
        // A saturated patch (patch 15, "yellow") keeps its hue ordering R > G > B.
        val y = 15 * 4
        assertThat(out.u(y)).isGreaterThan(out.u(y + 1))
        assertThat(out.u(y + 1)).isGreaterThan(out.u(y + 2))
    }

    @Test
    fun `out of range input is handled without NaN and clips only at the output`() {
        val input = SyntheticImages.outOfRange()

        val linear = CpuDevelop.renderLinear(input, Recipe())
        val out = CpuDevelop.render(input, Recipe(), OutputSettings(dither = false))

        for (v in linear) assertThat(v.isFinite()).isTrue()
        // 16x over white stays below 1.0 after the shoulder; negative values stay negative in the working space.
        assertThat(linear[5 * 3 + 1]).isLessThan(1.0001f)
        assertThat(linear[5 * 3 + 1]).isGreaterThan(0.999f)
        assertThat(linear.min()).isLessThan(0f)
        // The negative-red pixel clips to 0 at the output; white and 16x white are both 252-255.
        assertThat(out.u(0)).isEqualTo(0)
        assertThat(out.u(5 * 4)).isAtLeast(250)
        assertThat(out.u(3 * 4)).isEqualTo(0)
        assertThat(out.u(4 * 4)).isEqualTo(0)
    }

    @Test
    fun `temperature changes warm and cool the render and tint moves green magenta`() {
        val input = SyntheticImages.rawNoise(1, 1, 3).let {
            // A neutral grey under the as-shot light.
            dev.hfps.reveal.pipeline.PipelineInput(
                1,
                1,
                SyntheticImages.TEST_AS_SHOT.map { m ->
                    0.25f / m
                }.toFloatArray(),
                it.cameraToXyz,
                it.asShotMultipliers,
            )
        }
        val asShot = CpuDevelop.renderLinear(input, Recipe())
        // Telling the model the light was 3500 K means the picture is corrected towards blue.
        val corrected = CpuDevelop.renderLinear(input, Recipe(colour = Colour(WhiteBalance(temperatureK = 3500f))))
        val magenta = CpuDevelop.renderLinear(input, Recipe(colour = Colour(WhiteBalance(tint = 50f))))

        assertThat(asShot[0]).isWithin(1e-4f).of(asShot[1])
        assertThat(asShot[2]).isWithin(1e-4f).of(asShot[1])
        assertThat(corrected[2]).isGreaterThan(corrected[0])
        // Positive tint tells the model the light was magenta, so the correction pushes the picture to green.
        assertThat(magenta[1]).isGreaterThan(magenta[0])
        assertThat(magenta[1]).isGreaterThan(magenta[2])
    }

    @Test
    fun `render is deterministic and does not modify the input`() {
        val input = SyntheticImages.scene(64, 48)
        val before = input.linearRgb.copyOf()
        val recipe = Recipe(light = Light(0.7f), colour = Colour(WhiteBalance(5200f, 8f)))

        val a = CpuDevelop.render(input, recipe)
        val b = CpuDevelop.render(input, recipe)

        assertThat(a).isEqualTo(b)
        assertThat(input.linearRgb).isEqualTo(before)
    }

    @Test
    fun `golden checksum pins the pipeline maths to PIPELINE_VERSION`() {
        val input = SyntheticImages.scene(64, 48)
        val recipe = Recipe(light = Light(0.7f), colour = Colour(WhiteBalance(5200f, 8f)))

        val crc = CRC32().apply { update(CpuDevelop.render(input, recipe)) }.value

        // If this fails the pipeline output changed: that is only legitimate together with a PIPELINE_VERSION bump
        // (docs/adr/0002, "PIPELINE_VERSION rules"); update both here.
        assertThat(PIPELINE_VERSION).isEqualTo(1)
        assertThat(crc).isEqualTo(GOLDEN_CRC_V1)
    }

    private companion object {
        const val GOLDEN_CRC_V1: Long = 1568956133L
    }
}
