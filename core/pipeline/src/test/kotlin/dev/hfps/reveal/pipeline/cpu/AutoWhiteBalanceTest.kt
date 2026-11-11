package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/**
 * Auto white balance on synthetic fixtures: scenes of surfaces (defined as colours under D65) photographed under a
 * known light by the test camera. The light is applied as corresponding colours (Bradford from D65 to the light),
 * then XYZ goes to raw camera RGB through the inverse camera matrix. Measured: within 4 mired and 1 tint of the
 * light (a mired step of 5 is about the smallest visible white-balance change).
 */
class AutoWhiteBalanceTest {
    private val m = SyntheticImages.TEST_CAMERA_TO_XYZ
    private val toXyz = ColorSpaces.REC2020.toXyz

    private fun mired(k: Double) = 1e6 / k

    /**
     * Random surfaces whose average is grey (each colour comes with its complement around a grey), plus 15 % neutral
     * surfaces of random lightness (walls, paper, clouds: real scenes have some).
     */
    private fun surfaces(count: Int, seed: Int): List<DoubleArray> {
        val random = Random(seed)
        val neutrals = (0 until count * 15 / 100).map {
            DoubleArray(3) { _ -> 0.1 + random.nextDouble() * 0.7 }.let { v -> DoubleArray(3) { v[0] } }
        }
        return neutrals + (0 until count * 85 / 200).flatMap {
            val grey = 0.05 + random.nextDouble() * 0.4
            val d = DoubleArray(3) { (random.nextDouble() - 0.5) * grey * 0.8 }
            listOf(DoubleArray(3) { grey + d[it] }, DoubleArray(3) { grey - d[it] })
        }
    }

    private fun scene(surfaces: List<DoubleArray>, t: Double, tint: Double, saturation: Float? = null): PipelineInput {
        val light = WhiteBalanceModel.illuminantXyz(t, tint)
        val adapt = ColorSpaces.bradfordAdaptation(ColorSpaces.D65_XYZ, light)
        val camFromXyz = m.inverse()
        val side = 64
        val data = FloatArray(side * side * 3)
        for (p in 0 until side * side) {
            val s = surfaces[p % surfaces.size]
            val cam = camFromXyz.times(adapt.times(toXyz.times(s)))
            for (c in 0..2) data[p * 3 + c] = cam[c].toFloat().coerceAtLeast(0f)
        }
        return PipelineInput(InputKind.SCENE_REFERRED, side, side, data, m, SyntheticImages.TEST_AS_SHOT, saturation)
    }

    @Test
    fun `recovers known lights on grey-balanced scenes`() {
        for ((t, tint) in listOf(2800.0 to 0.0, 4000.0 to 0.0, 5500.0 to 5.0, 6500.0 to -8.0, 9000.0 to 0.0)) {
            val input = scene(surfaces(400, t.toInt()), t, tint)
            val est = AutoWhiteBalance.estimate(AutoWhiteBalance.analyse(input))
            assertThat(abs(mired(est.temperatureK) - mired(t))).isLessThan(8.0)
            assertThat(est.tint).isWithin(4.0).of(tint)
        }
    }

    @Test
    fun `the skin guard keeps a skin-heavy portrait from going cold`() {
        // 40 % of the scene is skin (warm), the rest grey-balanced surfaces; the light is 5500 K.
        val skin = List(160) { doubleArrayOf(0.42, 0.25, 0.17) }
        val all = surfaces(240, 7) + skin
        val input = scene(all.shuffled(Random(1)), 5500.0, 0.0)
        val analysis = AutoWhiteBalance.analyse(input)
        val guarded = AutoWhiteBalance.estimate(analysis)
        val unguarded = AutoWhiteBalance.estimate(analysis, skinGuard = false)
        val errGuarded = abs(mired(guarded.temperatureK) - mired(5500.0))
        val errUnguarded = abs(mired(unguarded.temperatureK) - mired(5500.0))
        // Without the guard the warm skin reads as a warm light (the correction then turns the picture cold).
        assertThat(unguarded.temperatureK).isLessThan(5500.0)
        assertThat(errGuarded).isLessThan(errUnguarded / 2)
        assertThat(errGuarded).isLessThan(12.0)
    }

    @Test
    fun `clipped areas are ignored`() {
        val input = scene(surfaces(400, 3), 4500.0, 0.0, saturation = 1f)
        // A large blown sky: clipped in every channel, which as data would look like a neutral at the clip.
        for (p in 0 until 64 * 20) for (c in 0..2) input.linearRgb[p * 3 + c] = 1f
        val est = AutoWhiteBalance.estimate(AutoWhiteBalance.analyse(input))
        assertThat(abs(mired(est.temperatureK) - mired(4500.0))).isLessThan(10.0)
    }

    @Test
    fun `a scene of one colour is held to a plausible light`() {
        val grass = List(10) { doubleArrayOf(0.08, 0.3, 0.05) }
        val input = scene(grass, 5500.0, 0.0)
        val est = AutoWhiteBalance.estimate(AutoWhiteBalance.analyse(input))
        assertThat(abs(est.tint)).isAtMost(30.0)
    }

    @Test
    fun `the result is plain slider values with mode auto`() {
        val input = scene(surfaces(200, 4), 3500.0, 0.0)
        val wb = AutoWhiteBalance.whiteBalance(AutoWhiteBalance.analyse(input))
        assertThat(wb.mode).isEqualTo(WbMode.AUTO)
        assertThat(wb.temperatureK!!.toDouble()).isWithin(150.0).of(3500.0)
        assertThat(wb.tint).isNotNull()
        // Display-referred input gets the relative pair.
        val jpeg = PipelineInput.linearSrgb(input.width, input.height, input.linearRgb)
        val rel = AutoWhiteBalance.whiteBalance(AutoWhiteBalance.analyse(jpeg))
        assertThat(rel.temperatureK).isNull()
        assertThat(rel.mode).isEqualTo(WbMode.AUTO)
    }
}
