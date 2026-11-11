package dev.hfps.reveal.pipeline.recipe

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a control's value is written (sliders, history, numeric input). */
enum class ValueKind {
    /** Stops with an explicit sign and two decimals: `+0.70`, `−1.25`, `0.00`. */
    EV_SIGNED,

    /** Integer with an explicit sign: `+12`, `−12`, `0`. */
    SIGNED_INT,

    /** Kelvin: `5600 K`. */
    KELVIN,

    /** Unsigned percent: `100 %`. */
    PERCENT,

    /** Degrees with one decimal, minus only when negative: `1.5°`, `−3.0°`. */
    DEGREES,
}

/** Every scalar control the editor sliders and the operators share. */
enum class ControlId {
    PROFILE_AMOUNT,
    EXPOSURE,
    CONTRAST,
    HIGHLIGHTS,
    SHADOWS,
    WHITES,
    BLACKS,
    TEMPERATURE,
    TINT,
    REL_TEMPERATURE,
    REL_TINT,
    VIBRANCE,
    SATURATION,
    ANGLE,
}

/**
 * The range of one scalar control: the single source of truth for sliders (track, ticks, steps, value labels) and
 * for operator clamping. [default] is the slider's starting position when the recipe holds no value; for the
 * nullable absolute white balance it is a display start (5500 K, tint 0), while the recipe's `null` means as-shot.
 * [neutral] is the value that means "no change" (the centre tick of a bipolar slider).
 *
 * @property step drag granularity; [fineStep] is the finest resolution (fine drag away from the row, numeric ±).
 */
data class ControlRange(
    val id: ControlId,
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float,
    val fineStep: Float,
    val bipolar: Boolean,
    val neutral: Float,
    val kind: ValueKind,
) {
    init {
        require(min < max) { "min must be below max" }
        require(default in min..max && neutral in min..max) { "default and neutral must lie in the range" }
        require(fineStep in 0f..step && step > 0f) { "0 < fineStep <= step" }
    }

    /** [value] limited to the range. */
    fun clamp(value: Float): Float = value.coerceIn(min, max)

    /** [value] limited to the range and rounded to the nearest multiple of [stepSize] counted from [min]. */
    fun snap(value: Float, stepSize: Float = step): Float =
        (min + ((clamp(value) - min) / stepSize).roundToInt() * stepSize).coerceIn(min, max)

    /** [value] written the way the slider label and the history show it. */
    fun format(value: Float): String = ValueFormat.format(kind, value)
}

/** The ranges of all scalar recipe controls (docs/adr/0003, "Fields"). */
object RecipeRanges {
    private const val TONE = 100f
    private const val EV_MAX = 5f
    private const val KELVIN_MIN = 2000f
    private const val KELVIN_MAX = 15000f
    private const val KELVIN_DEFAULT = 5500f
    private const val TINT_MAX = 150f
    private const val AMOUNT_MAX = 200f
    private const val ANGLE_MAX = 45f
    private const val KELVIN_STEP = 50f
    private const val KELVIN_FINE = 10f
    private const val ANGLE_STEP = 0.1f
    private const val EV_STEP = 0.01f

    private fun bipolar(id: ControlId, max: Float, step: Float, fine: Float, kind: ValueKind) =
        ControlRange(id, -max, max, 0f, step, fine, bipolar = true, neutral = 0f, kind = kind)

    private fun signedInt(id: ControlId, max: Float = TONE) = bipolar(id, max, 1f, 1f, ValueKind.SIGNED_INT)

    val profileAmount = ControlRange(
        ControlId.PROFILE_AMOUNT, 0f, AMOUNT_MAX, 100f, 1f, 1f, bipolar = false, neutral = 100f,
        kind = ValueKind.PERCENT,
    )
    val exposure = bipolar(ControlId.EXPOSURE, EV_MAX, EV_STEP, EV_STEP, ValueKind.EV_SIGNED)
    val contrast = signedInt(ControlId.CONTRAST)
    val highlights = signedInt(ControlId.HIGHLIGHTS)
    val shadows = signedInt(ControlId.SHADOWS)
    val whites = signedInt(ControlId.WHITES)
    val blacks = signedInt(ControlId.BLACKS)
    val temperature = ControlRange(
        ControlId.TEMPERATURE, KELVIN_MIN, KELVIN_MAX, KELVIN_DEFAULT, KELVIN_STEP, KELVIN_FINE,
        bipolar = false, neutral = KELVIN_DEFAULT, kind = ValueKind.KELVIN,
    )
    val tint = signedInt(ControlId.TINT, TINT_MAX)
    val relTemperature = signedInt(ControlId.REL_TEMPERATURE)
    val relTint = signedInt(ControlId.REL_TINT)
    val vibrance = signedInt(ControlId.VIBRANCE)
    val saturation = signedInt(ControlId.SATURATION)
    val angle = bipolar(ControlId.ANGLE, ANGLE_MAX, ANGLE_STEP, ANGLE_STEP, ValueKind.DEGREES)

    /** All descriptors in panel order. */
    val all: List<ControlRange> = listOf(
        profileAmount, exposure, contrast, highlights, shadows, whites, blacks,
        temperature, tint, relTemperature, relTint, vibrance, saturation, angle,
    )

    private val byId = all.associateBy { it.id }

    /** The descriptor of [id]. */
    operator fun get(id: ControlId): ControlRange = byId.getValue(id)
}

/** Locale-independent value text: explicit sign, U+2212 as the minus, fixed decimals, never `Locale.getDefault()`. */
object ValueFormat {
    private const val MINUS = '−'
    private const val EV_DECIMALS = 2

    /** [value] written as [kind] says. */
    fun format(kind: ValueKind, value: Float): String = when (kind) {
        ValueKind.EV_SIGNED -> signed(value, EV_DECIMALS)
        ValueKind.SIGNED_INT -> signed(value, 0)
        ValueKind.KELVIN -> "${value.roundToInt()} K"
        ValueKind.PERCENT -> "${value.roundToInt()} %"
        ValueKind.DEGREES -> degrees(value)
    }

    /** Explicit sign (none for zero), U+2212 for negatives, [decimals] fixed decimals. */
    fun signed(value: Float, decimals: Int): String {
        val text = fixed(abs(value), decimals)
        return when {
            text.all { it == '0' || it == '.' } -> text
            value < 0f -> "$MINUS$text"
            else -> "+$text"
        }
    }

    private fun degrees(value: Float): String {
        val text = fixed(abs(value), 1)
        val zero = text.all { it == '0' || it == '.' }
        return if (value < 0f && !zero) "$MINUS$text°" else "$text°"
    }

    private fun fixed(value: Float, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)
}
