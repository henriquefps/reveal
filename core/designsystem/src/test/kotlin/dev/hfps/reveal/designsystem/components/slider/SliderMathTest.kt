package dev.hfps.reveal.designsystem.components.slider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SliderMathTest {
    private val exposure = SliderSpec(-5f, 5f, 0f, 0.01f, 0.01f, bipolar = true)
    private val tone = SliderSpec(-100f, 100f, 0f, 1f, 1f, bipolar = true)
    private val kelvin = SliderSpec(2000f, 15000f, 5500f, 50f, 10f, bipolar = false)

    /** 1000 px track, fine control from 40 px, halving every 80 px, 8 px snap zone. */
    private fun drag(spec: SliderSpec, start: Float) = SliderDrag(spec, 1000f, 40f, 80f, 8f).also { it.start(start) }

    @Test
    fun theValueMovesWithTheFingerTravelNotItsPosition() {
        val d = drag(tone, 50f)
        // A tenth of the track is a tenth of the 200 span.
        assertThat(d.move(100f, 0f).value).isEqualTo(70f)
        assertThat(d.move(-50f, 0f).value).isEqualTo(60f)
    }

    @Test
    fun moreVerticalDistanceMeansFinerControlAndSpeedPlaysNoPart() {
        val near = drag(exposure, 1f)
        val far = drag(exposure, 1f)
        val fine = drag(exposure, 1f)
        // Same horizontal travel, in one move or in ten: same result (no velocity term).
        repeat(10) { near.move(10f, 20f) }
        assertThat(near.value).isWithin(1e-4f).of(2f)
        far.move(100f, 120f) // 80 px past the start: half speed
        assertThat(far.value).isWithin(1e-4f).of(1.5f)
        fine.move(100f, 200f) // 160 px past: quarter speed
        assertThat(fine.value).isWithin(1e-4f).of(1.25f)
    }

    @Test
    fun fineFactorCurve() {
        assertThat(FineControl.factor(0f, 40f, 80f)).isEqualTo(1f)
        assertThat(FineControl.factor(-40f, 40f, 80f)).isEqualTo(1f)
        assertThat(FineControl.factor(120f, 40f, 80f)).isWithin(1e-6f).of(0.5f)
        assertThat(FineControl.factor(-200f, 40f, 80f)).isWithin(1e-6f).of(0.25f)
        assertThat(FineControl.factor(10_000f, 40f, 80f)).isEqualTo(FineControl.MIN_FACTOR)
    }

    @Test
    fun coarseDragsSnapToTheStepAndFineDragsToTheFineStep() {
        val coarse = drag(kelvin, 5000f)
        assertThat(coarse.move(1.5f, 0f).value).isEqualTo(5000f) // 19.5 K: rounds back
        assertThat(coarse.move(2f, 0f).value).isEqualTo(5050f) // 45.5 K total
        val fine = drag(kelvin, 5000f)
        // 6 px at quarter speed is 19.5 K: the fine step (10 K) shows it.
        assertThat(fine.move(6f, 200f).value).isEqualTo(5020f)
    }

    @Test
    fun theThumbNeverRunsPastTheEndsAndReversingRespondsAtOnce() {
        val d = drag(tone, 90f)
        assertThat(d.move(500f, 0f).value).isEqualTo(100f)
        assertThat(d.move(-10f, 0f).value).isEqualTo(98f)
    }

    @Test
    fun aShortZoneHoldsTheNeutralValue() {
        val d = drag(tone, -2f)
        // 8 px of travel = 1.6: inside the zone around 0.
        assertThat(d.move(15f, 0f).value).isEqualTo(0f)
        assertThat(d.move(10f, 0f).value).isEqualTo(3f) // raw 3 is outside the zone
    }

    @Test
    fun hapticTicksAtNeutralAndAtTheEnds() {
        val d = drag(tone, -10f)
        assertThat(d.move(40f, 0f).tick).isNull() // -2 is outside the zone: a plain step
        assertThat(d.move(10f, 0f).tick).isEqualTo(SliderTick.NEUTRAL) // 0 (snapped)
        assertThat(d.move(2f, 0f).tick).isNull() // still held at 0
        assertThat(d.move(20f, 0f).tick).isNull() // leaving neutral is not a tick
        val crossing = drag(tone, -5f)
        assertThat(crossing.move(100f, 0f).tick).isEqualTo(SliderTick.NEUTRAL) // -5 -> +15 jumps over 0
        val end = drag(tone, 95f)
        assertThat(end.move(50f, 0f).tick).isEqualTo(SliderTick.END)
        assertThat(end.move(50f, 0f).tick).isNull() // already there
    }

    @Test
    fun aUnipolarSliderWhoseNeutralIsTheMinimumTicksAsAnEnd() {
        val amount = SliderSpec(0f, 200f, 0f, 1f, 1f, bipolar = false)
        val d = drag(amount, 20f)
        assertThat(d.move(-500f, 0f).tick).isEqualTo(SliderTick.END)
    }

    @Test
    fun snapIsExactAtNeutralAndDecimalsFollowTheFineStep() {
        assertThat(exposure.snap(0.004f)).isEqualTo(0f)
        assertThat(exposure.snap(-0.706f)).isWithin(1e-6f).of(-0.71f)
        assertThat(kelvin.snap(5524f)).isEqualTo(5500f)
        assertThat(kelvin.snap(5524f, 10f)).isEqualTo(5520f)
        assertThat(exposure.decimals).isEqualTo(2)
        assertThat(tone.decimals).isEqualTo(0)
        assertThat(SliderSpec(-45f, 45f, 0f, 0.1f, 0.1f, true).decimals).isEqualTo(1)
    }
}
