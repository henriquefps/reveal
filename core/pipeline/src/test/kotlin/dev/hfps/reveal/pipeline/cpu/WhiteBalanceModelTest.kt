package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import org.junit.Test

class WhiteBalanceModelTest {
    private val raw = SyntheticImages.rawNoise(2, 2, 1)
    private val jpeg = SyntheticImages.grayRamp(2, 1)

    @Test
    fun `Planckian locus matches CIE reference points`() {
        // CIE 1960 uv of a 6500 K blackbody (Krystek; note D65 daylight is 0.1978, 0.3122, slightly off the locus).
        val uv = WhiteBalanceModel.planckianUv(6500.0)
        assertThat(uv[0]).isWithin(1e-3).of(0.2005)
        assertThat(uv[1]).isWithin(1e-3).of(0.3104)
        // 2856 K (illuminant A) is xy 0.4476, 0.4074.
        val a = WhiteBalanceModel.illuminantXyz(2856.0, 0.0)
        assertThat(a[0] / (a[0] + a[1] + a[2])).isWithin(2e-3).of(0.4476)
        assertThat(a[1] / (a[0] + a[1] + a[2])).isWithin(2e-3).of(0.4074)
    }

    @Test
    fun `temperature and tint round trip through XYZ`() {
        for (t in listOf(2500.0, 4000.0, 5600.0, 6500.0, 9000.0, 14000.0)) {
            for (tint in listOf(-80.0, 0.0, 45.0)) {
                val cct = WhiteBalanceModel.cctAndTint(WhiteBalanceModel.illuminantXyz(t, tint))
                assertThat(cct.temperatureK).isWithin(t * 0.002).of(t)
                assertThat(cct.tint).isWithin(0.05).of(tint)
            }
        }
    }

    @Test
    fun `positive tint is magenta, lowering the temperature makes the image bluer`() {
        val neutral = WhiteBalanceModel.illuminantXyz(5000.0, 0.0)
        val magenta = WhiteBalanceModel.illuminantXyz(5000.0, 60.0)
        // Magenta light has less green than the locus point: lower y.
        assertThat(magenta[1] / magenta.sum()).isLessThan(neutral[1] / neutral.sum())

        val asShot = WhiteBalanceModel.solve(raw, WhiteBalance())
        val cool = WhiteBalanceModel.solve(raw, WhiteBalance(temperatureK = 4000f))
        // Telling the pipeline the light was warmer than it was removes red and adds blue.
        assertThat(cool.multipliers[0]).isLessThan(asShot.multipliers[0])
        assertThat(cool.multipliers[2]).isGreaterThan(asShot.multipliers[2])
    }

    @Test
    fun `non raw as shot is the identity into the working space`() {
        val solution = WhiteBalanceModel.solve(jpeg, WhiteBalance())
        val expected = ColorSpaces.REC2020.fromXyz * ColorSpaces.SRGB.toXyz

        assertThat(solution.multipliers.toList()).containsExactly(1.0, 1.0, 1.0)
        for (r in 0..2) for (c in 0..2) assertThat(solution.balancedToWorking[r, c]).isWithin(1e-9).of(expected[r, c])
    }

    @Test
    fun `balanced white maps to D65 white in every case`() {
        for (wb in listOf(
            WhiteBalance(),
            WhiteBalance(3200f, null),
            WhiteBalance(7500f, 40f),
            WhiteBalance(null, -30f),
        )) {
            val solution = WhiteBalanceModel.solve(raw, wb)
            val white = solution.balancedToWorking.times(1.0, 1.0, 1.0)
            val expected = ColorSpaces.REC2020.fromXyz.times(ColorSpaces.D65_XYZ)
            for (i in 0..2) assertThat(white[i]).isWithin(1e-9).of(expected[i])
        }
    }

    @Test
    fun `a neutral surface under the as shot light develops to grey`() {
        val neutralRaw = SyntheticImages.TEST_AS_SHOT.map { 0.4 / it }
        val solution = WhiteBalanceModel.solve(raw, WhiteBalance())
        val balanced = doubleArrayOf(
            neutralRaw[0] * solution.multipliers[0],
            neutralRaw[1] * solution.multipliers[1],
            neutralRaw[2] * solution.multipliers[2],
        )

        val working = solution.balancedToWorking.times(balanced)

        assertThat(working[0]).isWithin(1e-5).of(working[1])
        assertThat(working[2]).isWithin(1e-5).of(working[1])
    }

    @Test
    fun `as shot temperature of a D65 camera is close to 6500 with near zero tint`() {
        val cct = WhiteBalanceModel.asShot(raw)

        assertThat(cct.temperatureK).isWithin(150.0).of(6504.0)
        assertThat(cct.tint).isWithin(15.0).of(0.0)
    }

    @Test
    fun `setting the as shot values explicitly is close to leaving them unset`() {
        val cct = WhiteBalanceModel.asShot(raw)
        val unset = WhiteBalanceModel.solve(raw, WhiteBalance())
        val set = WhiteBalanceModel.solve(raw, WhiteBalance(cct.temperatureK.toFloat(), cct.tint.toFloat()))

        for (i in 0..2) assertThat(set.multipliers[i]).isWithin(2e-3).of(unset.multipliers[i])
    }

    @Test
    fun `input validation`() {
        val ok = PipelineInput.linearSrgb(1, 1, FloatArray(3))
        assertThat(ok.isRaw).isFalse()
        assertThat(raw.isRaw).isTrue()
        try {
            PipelineInput.linearSrgb(2, 2, FloatArray(3))
            error("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("width*height*3")
        }
    }
}
