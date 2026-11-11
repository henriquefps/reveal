package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import org.junit.Test

class WhiteBalanceModesTest {
    private val m = SyntheticImages.TEST_CAMERA_TO_XYZ

    /** A RAW pixel of a neutral surface under the light (temperature, tint). */
    private fun neutralUnder(t: Double, tint: Double, level: Double = 0.3): FloatArray {
        val cam = m.inverse().times(WhiteBalanceModel.illuminantXyz(t, tint))
        val k = level / cam[1]
        return FloatArray(3) { (cam[it] * k).toFloat() }
    }

    private fun raw(rgb: FloatArray) =
        PipelineInput(InputKind.SCENE_REFERRED, 1, 1, rgb, m, SyntheticImages.TEST_AS_SHOT)

    @Test
    fun `presets are the documented CIE lights`() {
        val expected = mapOf(
            WbMode.DAYLIGHT to 5503.0,
            WbMode.CLOUDY to 6504.0,
            WbMode.SHADE to 7504.0,
            WbMode.TUNGSTEN to 2856.0,
            WbMode.FLUORESCENT to 4230.0,
            WbMode.FLASH to 5900.0,
        )
        for ((mode, k) in expected) {
            val cct = checkNotNull(WhiteBalanceModel.presetCct(mode))
            assertThat(cct.temperatureK).isWithin(k * 0.01).of(k)
            // Daylight lies slightly above the Planckian locus (green side), so its tint is a little negative;
            // fluorescent F2 more so; illuminant A is on the locus.
            assertThat(cct.tint).isWithin(15.0).of(0.0)
        }
        assertThat(WhiteBalanceModel.presetCct(WbMode.TUNGSTEN)!!.tint).isWithin(1.0).of(0.0)
        assertThat(WhiteBalanceModel.presetCct(WbMode.FLUORESCENT)!!.tint).isLessThan(-3.0)
        assertThat(WhiteBalanceModel.presetCct(WbMode.AUTO)).isNull()
        assertThat(WhiteBalanceModel.presetCct(WbMode.AS_SHOT)).isNull()
    }

    @Test
    fun `preset chips write values for RAW and only as shot for display-referred input`() {
        val rawInput = raw(FloatArray(3))
        val tungsten = WhiteBalanceModel.preset(WbMode.TUNGSTEN, rawInput)!!
        assertThat(tungsten.mode).isEqualTo(WbMode.TUNGSTEN)
        assertThat(tungsten.temperatureK!!.toDouble()).isWithin(30.0).of(2856.0)
        assertThat(WhiteBalanceModel.preset(WbMode.AS_SHOT, rawInput)).isEqualTo(WhiteBalance())
        val jpeg = PipelineInput.linearSrgb(1, 1, FloatArray(3))
        assertThat(WhiteBalanceModel.preset(WbMode.TUNGSTEN, jpeg)).isNull()
        assertThat(WhiteBalanceModel.preset(WbMode.AS_SHOT, jpeg)).isEqualTo(WhiteBalance())
    }

    @Test
    fun `eyedropper on a RAW neutral recovers the light and renders it neutral`() {
        for ((t, tint) in listOf(3200.0 to 0.0, 5000.0 to 12.0, 8000.0 to -20.0)) {
            val pixel = neutralUnder(t, tint)
            val input = raw(pixel)
            val wb = WhiteBalanceModel.neutralFromSample(input, pixel)
            assertThat(wb.mode).isEqualTo(WbMode.CUSTOM)
            assertThat(wb.temperatureK!!.toDouble()).isWithin(t * 0.005).of(t)
            assertThat(wb.tint!!.toDouble()).isWithin(0.5).of(tint)
            val out = CpuDevelop.renderLinear(input, Recipe(colour = Colour(wb)))
            assertThat(abs(out[0] - out[1]) / out[1]).isLessThan(2e-3f)
            assertThat(abs(out[2] - out[1]) / out[1]).isLessThan(2e-3f)
        }
    }

    @Test
    fun `relative temperature warms and tint goes magenta on display-referred input, zero is exact`() {
        val grey = PipelineInput.linearSrgb(1, 1, floatArrayOf(0.2f, 0.2f, 0.2f))
        val base = CpuDevelop.renderLinear(grey, Recipe())
        val warm = CpuDevelop.renderLinear(grey, Recipe(colour = Colour(WhiteBalance(relTemperature = 50f))))
        val cool = CpuDevelop.renderLinear(grey, Recipe(colour = Colour(WhiteBalance(relTemperature = -50f))))
        val magenta = CpuDevelop.renderLinear(grey, Recipe(colour = Colour(WhiteBalance(relTint = 50f))))
        assertThat(warm[0]).isGreaterThan(warm[2])
        assertThat(cool[2]).isGreaterThan(cool[0])
        assertThat(magenta[1]).isLessThan(magenta[0])
        assertThat(magenta[1]).isLessThan(magenta[2])
        // Absolute values are ignored on display-referred input.
        val absolute = CpuDevelop.renderLinear(grey, Recipe(colour = Colour(WhiteBalance(temperatureK = 3000f))))
        assertThat(absolute).isEqualTo(base)
        // Relative values are ignored on RAW input.
        val rawInput = raw(neutralUnder(5000.0, 0.0))
        assertThat(CpuDevelop.renderLinear(rawInput, Recipe(colour = Colour(WhiteBalance(relTemperature = 80f)))))
            .isEqualTo(CpuDevelop.renderLinear(rawInput, Recipe()))
        // ±100 is the documented mired range.
        assertThat(WhiteBalanceModel.relativeCct(100.0, 0.0).temperatureK).isWithin(150.0).of(12690.0)
        assertThat(WhiteBalanceModel.relativeCct(-100.0, 0.0).temperatureK).isWithin(30.0).of(4371.0)
    }

    @Test
    fun `eyedropper on a display-referred pixel makes it neutral`() {
        for (pixel in listOf(
            floatArrayOf(0.3f, 0.25f, 0.18f),
            floatArrayOf(0.2f, 0.24f, 0.3f),
            floatArrayOf(0.3f, 0.22f, 0.3f),
        )) {
            val input = PipelineInput.linearSrgb(1, 1, pixel)
            val wb = WhiteBalanceModel.neutralFromSample(input, pixel)
            assertThat(wb.temperatureK).isNull()
            val out = CpuDevelop.renderLinear(input, Recipe(colour = Colour(wb)))
            assertThat(abs(out[0] - out[1]) / out[1]).isLessThan(5e-3f)
            assertThat(abs(out[2] - out[1]) / out[1]).isLessThan(5e-3f)
        }
    }
}
