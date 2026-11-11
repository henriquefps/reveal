package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import org.junit.Test

class WhiteBalancePickerTest {
    private val m = SyntheticImages.TEST_CAMERA_TO_XYZ

    /** Raw camera RGB of a grey card lit by the illuminant ([t], [tint]). */
    private fun greyCard(t: Double, tint: Double, level: Double = 0.3): FloatArray {
        val w = WhiteBalanceModel.illuminantXyz(t, tint)
        val cam = m.inverse().times(w)
        return FloatArray(3) { (cam[it] * level).toFloat() }
    }

    private fun uniformRaw(rgb: FloatArray, size: Int = 16): PipelineInput {
        val data = FloatArray(size * size * 3) { rgb[it % 3] }
        return PipelineInput(InputKind.SCENE_REFERRED, size, size, data, m, SyntheticImages.TEST_AS_SHOT, 1f)
    }

    @Test
    fun theEyedropperRecoversTheIlluminantOfAGreyCard() {
        for ((t, tint) in listOf(3200.0 to 0.0, 5600.0 to 20.0, 8000.0 to -30.0)) {
            val card = greyCard(t, tint)
            val cct = checkNotNull(WhiteBalancePicker.fromNeutral(uniformRaw(card), card))
            assertThat(cct.temperatureK).isWithin(t * 0.003).of(t)
            assertThat(cct.tint).isWithin(0.2).of(tint)
        }
    }

    @Test
    fun thePickedValuesRenderTheCardNeutral() {
        val card = greyCard(4300.0, 12.0)
        val input = uniformRaw(card)
        val cct = checkNotNull(WhiteBalancePicker.fromNeutral(input, card))
        val solution = WhiteBalanceModel.solve(
            input,
            WhiteBalance(temperatureK = cct.temperatureK.toFloat(), tint = cct.tint.toFloat()),
        )
        val balanced = DoubleArray(3) { card[it] * solution.multipliers[it] }
        assertThat(balanced[0] / balanced[1]).isWithin(5e-3).of(1.0)
        assertThat(balanced[2] / balanced[1]).isWithin(5e-3).of(1.0)
    }

    @Test
    fun resultsAreClampedToTheSliderRanges() {
        val cct =
            checkNotNull(WhiteBalancePicker.fromNeutral(uniformRaw(greyCard(5000.0, 0.0)), floatArrayOf(1f, 0.05f, 1f)))
        assertThat(cct.tint).isAtMost(150.0)
        assertThat(cct.tint).isAtLeast(-150.0)
        assertThat(cct.temperatureK).isIn(com.google.common.collect.Range.closed(2000.0, 15000.0))
    }

    @Test
    fun displayReferredInputAndDarkPatchesHaveNoAnswer() {
        assertThat(
            WhiteBalancePicker.fromNeutral(SyntheticImages.grayRamp(4, 4), floatArrayOf(0.5f, 0.5f, 0.5f)),
        ).isNull()
        assertThat(WhiteBalancePicker.grayWorld(SyntheticImages.grayRamp(4, 4))).isNull()
        val raw = uniformRaw(greyCard(5000.0, 0.0))
        assertThat(WhiteBalancePicker.fromNeutral(raw, floatArrayOf(0f, 0.2f, 0.2f))).isNull()
    }

    @Test
    fun greyWorldOfAUniformCardIsItsIlluminantAndSkipsClippedPixels() {
        val card = greyCard(6100.0, -10.0)
        val size = 16
        val data = FloatArray(size * size * 3) { card[it % 3] }
        // A clipped, magenta highlight in one row must not bias the estimate.
        for (x in 0 until size) {
            data[x * 3] = 1f
            data[x * 3 + 1] = 0.95f
            data[x * 3 + 2] = 1f
        }
        val input = PipelineInput(InputKind.SCENE_REFERRED, size, size, data, m, SyntheticImages.TEST_AS_SHOT, 1f)
        val cct = checkNotNull(WhiteBalancePicker.grayWorld(input, stride = 1))
        assertThat(cct.temperatureK).isWithin(6100 * 0.003).of(6100.0)
        assertThat(cct.tint).isWithin(0.2).of(-10.0)
    }
}
