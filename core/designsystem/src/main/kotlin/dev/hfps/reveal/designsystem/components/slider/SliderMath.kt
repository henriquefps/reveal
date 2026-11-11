package dev.hfps.reveal.designsystem.components.slider

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * What a [RevealSlider] controls (mirrors a recipe `ControlRange`, which this leaf module cannot see).
 *
 * @property neutral the value that means "no change": the centre tick of a [bipolar] slider and the snap point.
 * @property step granularity of a normal drag and of the ± stepper; [fineStep] the granularity of a fine drag.
 * @property bipolar fills from [neutral] (with a centre tick) instead of from [min].
 */
@Immutable
data class SliderSpec(
    val min: Float,
    val max: Float,
    val neutral: Float,
    val step: Float,
    val fineStep: Float,
    val bipolar: Boolean,
) {
    init {
        require(min < max) { "min must be below max" }
        require(neutral in min..max) { "neutral must lie in the range" }
        require(step > 0f && fineStep > 0f && fineStep <= step) { "0 < fineStep <= step" }
    }

    val span: Float get() = max - min

    /** [value] rounded to a multiple of [granularity] counted from [neutral] (so neutral is exact), clamped. */
    fun snap(value: Float, granularity: Float = step): Float =
        (neutral + ((value - neutral) / granularity).roundToInt() * granularity).coerceIn(min, max)

    /** Position of [value] along the track, 0..1. */
    fun fraction(value: Float): Float = ((value - min) / span).coerceIn(0f, 1f)

    /** Decimals needed to show [fineStep] (0.01 -> 2, 1 -> 0, 0.1 -> 1). */
    val decimals: Int
        get() = if (fineStep >= 1f) 0 else (-log10(fineStep.toDouble()) + DECIMAL_EPSILON).toInt().coerceAtLeast(0)

    private companion object {
        const val DECIMAL_EPSILON = 1e-6
    }
}

/**
 * Fine control: how much a horizontal finger move counts, from how far the finger is **vertically** away from where
 * the drag started. Velocity plays no part, only position, so the same move always gives the same change.
 *
 * `factor(d) = 1` for `d <= start`, else `max(MIN_FACTOR, 0.5 ^ ((d - start) / halving))`: full speed while the
 * finger stays on the row, then the speed halves every [halving] further away (with the defaults, 40 dp on the row,
 * then 1/2 at 120 dp, 1/4 at 200 dp, 1/8 at 280 dp, never below 1/20).
 */
object FineControl {
    /** Distance the finger can wander off the row without slowing down, in dp. */
    const val START_DP: Float = 40f

    /** Each further [HALVING_DP] halves the speed. */
    const val HALVING_DP: Float = 80f

    /** Slowest factor: 1/20 of the normal speed. */
    const val MIN_FACTOR: Float = 0.05f

    fun factor(distancePx: Float, startPx: Float, halvingPx: Float): Float {
        val d = abs(distancePx)
        if (d <= startPx) return 1f
        return 0.5f.pow((d - startPx) / halvingPx).coerceAtLeast(MIN_FACTOR)
    }
}

/** A haptic tick of a slider drag. */
enum class SliderTick {
    /** The value reached or crossed the neutral value. */
    NEUTRAL,

    /** The value reached the minimum or the maximum. */
    END,
}

/** One step of a drag: the value to show and an optional haptic [tick]. */
data class SliderDragStep(val value: Float, val tick: SliderTick?)

/**
 * Relative-drag maths of [RevealSlider], pure and JVM-tested. The value moves by the finger's horizontal travel
 * (`dx / trackWidth * span * fineFactor`), never to where the finger is, so the thumb never jumps. An internal raw
 * value accumulates the movement (clamped to the range, so reversing at an end responds at once); the emitted value
 * is the raw one snapped to [SliderSpec.step] (or [SliderSpec.fineStep] in fine mode) and held at the neutral value
 * while the raw value is inside a short snap zone ([snapZonePx] of finger travel at the current speed).
 *
 * @param trackWidthPx width of the track in pixels (the full-span travel at normal speed).
 * @param fineStartPx [FineControl.START_DP] in pixels; [fineHalvingPx] [FineControl.HALVING_DP] in pixels.
 */
class SliderDrag(
    private val spec: SliderSpec,
    private val trackWidthPx: Float,
    private val fineStartPx: Float,
    private val fineHalvingPx: Float,
    private val snapZonePx: Float,
) {
    private var raw = 0f

    /** The last emitted value. */
    var value: Float = 0f
        private set

    /** Starts a drag at [start] (the current value). */
    fun start(start: Float) {
        raw = start.coerceIn(spec.min, spec.max)
        value = raw
    }

    /**
     * Moves by [dx] pixels horizontally with the finger [verticalDistancePx] away (vertically) from where the drag
     * started; returns the new value and the haptic tick to play, if any.
     */
    fun move(dx: Float, verticalDistancePx: Float): SliderDragStep {
        val factor = FineControl.factor(verticalDistancePx, fineStartPx, fineHalvingPx)
        val perPx = spec.span / trackWidthPx.coerceAtLeast(1f) * factor
        raw = (raw + dx * perPx).coerceIn(spec.min, spec.max)
        val granularity = if (factor < 1f) spec.fineStep else spec.step
        val zone = snapZonePx * perPx
        val next = if (abs(raw - spec.neutral) <= zone) spec.neutral else spec.snap(raw, granularity)
        val tick = tickBetween(value, next)
        value = next
        return SliderDragStep(next, tick)
    }

    private fun tickBetween(previous: Float, next: Float): SliderTick? {
        if (previous == next) return null
        val n = spec.neutral
        val crossed = next == n || (previous != n && sign(previous - n) != sign(next - n))
        return when {
            crossed && n > spec.min && n < spec.max -> SliderTick.NEUTRAL
            next == spec.min || next == spec.max -> SliderTick.END
            else -> null
        }
    }
}
