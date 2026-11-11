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

    /** Unsigned integer: `40`. */
    INT,

    /** Unsigned number with one decimal: `1.0`. */
    DECIMAL1,

    /** A hue angle, whole degrees: `210°`. */
    HUE,
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
    SHARPEN_AMOUNT,
    SHARPEN_RADIUS,
    SHARPEN_DETAIL,
    SHARPEN_MASKING,
    NR_LUMINANCE,
    NR_LUMINANCE_DETAIL,
    NR_LUMINANCE_CONTRAST,
    NR_COLOUR,
    NR_COLOUR_DETAIL,
    NR_COLOUR_SMOOTHNESS,
    TEXTURE,
    CLARITY,
    DEHAZE,
    VIGNETTE_AMOUNT,
    VIGNETTE_MIDPOINT,
    VIGNETTE_ROUNDNESS,
    VIGNETTE_FEATHER,
    VIGNETTE_HIGHLIGHTS,
    GRAIN_AMOUNT,
    GRAIN_SIZE,
    GRAIN_ROUGHNESS,

    // Optics and transform (docs/adr/0015). Before CURVE_SHADOWS: ColourToolControls handles the ids from there on.
    LENS_DISTORTION,
    LENS_VIGNETTING,
    DEFRINGE_PURPLE_AMOUNT,
    DEFRINGE_PURPLE_HUE_LOW,
    DEFRINGE_PURPLE_HUE_HIGH,
    DEFRINGE_GREEN_AMOUNT,
    DEFRINGE_GREEN_HUE_LOW,
    DEFRINGE_GREEN_HUE_HIGH,
    TRANSFORM_VERTICAL,
    TRANSFORM_HORIZONTAL,
    TRANSFORM_ROTATE,
    TRANSFORM_ASPECT,
    TRANSFORM_SCALE,
    TRANSFORM_OFFSET_X,
    TRANSFORM_OFFSET_Y,

    // Tone curve, parametric (docs/adr/0013).
    CURVE_SHADOWS,
    CURVE_DARKS,
    CURVE_LIGHTS,
    CURVE_HIGHLIGHTS,
    CURVE_SPLIT_SHADOWS,
    CURVE_SPLIT_MIDTONES,
    CURVE_SPLIT_HIGHLIGHTS,

    // Colour mixer: hue, saturation, luminance per band (ColourBand order).
    HUE_RED,
    HUE_ORANGE,
    HUE_YELLOW,
    HUE_GREEN,
    HUE_AQUA,
    HUE_BLUE,
    HUE_PURPLE,
    HUE_MAGENTA,
    SAT_RED,
    SAT_ORANGE,
    SAT_YELLOW,
    SAT_GREEN,
    SAT_AQUA,
    SAT_BLUE,
    SAT_PURPLE,
    SAT_MAGENTA,
    LUM_RED,
    LUM_ORANGE,
    LUM_YELLOW,
    LUM_GREEN,
    LUM_AQUA,
    LUM_BLUE,
    LUM_PURPLE,
    LUM_MAGENTA,

    // Black & white grey mixer per band.
    GREY_RED,
    GREY_ORANGE,
    GREY_YELLOW,
    GREY_GREEN,
    GREY_AQUA,
    GREY_BLUE,
    GREY_PURPLE,
    GREY_MAGENTA,

    // Colour grading: hue, saturation, luminance per wheel (GradeRegion order), blending, balance.
    GRADE_SHADOWS_HUE,
    GRADE_SHADOWS_SAT,
    GRADE_SHADOWS_LUM,
    GRADE_MIDTONES_HUE,
    GRADE_MIDTONES_SAT,
    GRADE_MIDTONES_LUM,
    GRADE_HIGHLIGHTS_HUE,
    GRADE_HIGHLIGHTS_SAT,
    GRADE_HIGHLIGHTS_LUM,
    GRADE_GLOBAL_HUE,
    GRADE_GLOBAL_SAT,
    GRADE_GLOBAL_LUM,
    GRADE_BLENDING,
    GRADE_BALANCE,
    ;

    companion object {
        /** The colour mixer control of [property] for [band]. */
        fun mixer(property: MixerProperty, band: ColourBand): ControlId {
            val prefix = when (property) {
                MixerProperty.HUE -> "HUE"
                MixerProperty.SATURATION -> "SAT"
                MixerProperty.LUMINANCE -> "LUM"
            }
            return valueOf("${prefix}_${band.name}")
        }

        /** The grey mixer control of [band]. */
        fun grey(band: ColourBand): ControlId = valueOf("GREY_${band.name}")

        /** The colour grading control of [region]'s [property] (saturation for [MixerProperty.SATURATION]). */
        fun grade(region: GradeRegion, property: MixerProperty): ControlId {
            val suffix = when (property) {
                MixerProperty.HUE -> "HUE"
                MixerProperty.SATURATION -> "SAT"
                MixerProperty.LUMINANCE -> "LUM"
            }
            return valueOf("GRADE_${region.name}_$suffix")
        }
    }
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
    private const val SHARPEN_MAX = 150f
    private const val SHARPEN_DETAIL_DEFAULT = 25f
    private const val RADIUS_MIN = 0.5f
    private const val RADIUS_MAX = 3f
    private const val RADIUS_STEP = 0.1f
    private const val HALF = 50f
    private const val SPLIT_MIN = 5f
    private const val SPLIT_MAX = 95f
    private const val HUE_MAX = 360f
    private const val GRAIN_SIZE_DEFAULT = 25f
    private const val DEFRINGE_MAX = 20f
    private const val ROTATE_MAX = 10f
    private const val SCALE_MIN = 50f
    private const val SCALE_MAX = 150f

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

    private fun unipolar(id: ControlId, max: Float, neutral: Float) =
        ControlRange(id, 0f, max, neutral, 1f, 1f, bipolar = false, neutral = neutral, kind = ValueKind.INT)

    val sharpenAmount = unipolar(ControlId.SHARPEN_AMOUNT, SHARPEN_MAX, 0f)
    val sharpenRadius = ControlRange(
        ControlId.SHARPEN_RADIUS, RADIUS_MIN, RADIUS_MAX, 1f, RADIUS_STEP, RADIUS_STEP, bipolar = false, neutral = 1f,
        kind = ValueKind.DECIMAL1,
    )
    val sharpenDetail = unipolar(ControlId.SHARPEN_DETAIL, TONE, SHARPEN_DETAIL_DEFAULT)
    val sharpenMasking = unipolar(ControlId.SHARPEN_MASKING, TONE, 0f)
    val nrLuminance = unipolar(ControlId.NR_LUMINANCE, TONE, 0f)
    val nrLuminanceDetail = unipolar(ControlId.NR_LUMINANCE_DETAIL, TONE, HALF)
    val nrLuminanceContrast = unipolar(ControlId.NR_LUMINANCE_CONTRAST, TONE, 0f)
    val nrColour = unipolar(ControlId.NR_COLOUR, TONE, 0f)
    val nrColourDetail = unipolar(ControlId.NR_COLOUR_DETAIL, TONE, HALF)
    val nrColourSmoothness = unipolar(ControlId.NR_COLOUR_SMOOTHNESS, TONE, HALF)
    val texture = signedInt(ControlId.TEXTURE)
    val clarity = signedInt(ControlId.CLARITY)
    val dehaze = signedInt(ControlId.DEHAZE)
    val vignetteAmount = signedInt(ControlId.VIGNETTE_AMOUNT)
    val vignetteMidpoint = unipolar(ControlId.VIGNETTE_MIDPOINT, TONE, HALF)
    val vignetteRoundness = signedInt(ControlId.VIGNETTE_ROUNDNESS)
    val vignetteFeather = unipolar(ControlId.VIGNETTE_FEATHER, TONE, HALF)
    val vignetteHighlights = unipolar(ControlId.VIGNETTE_HIGHLIGHTS, TONE, 0f)
    val grainAmount = unipolar(ControlId.GRAIN_AMOUNT, TONE, 0f)
    val grainSize = unipolar(ControlId.GRAIN_SIZE, TONE, GRAIN_SIZE_DEFAULT)
    val grainRoughness = unipolar(ControlId.GRAIN_ROUGHNESS, TONE, HALF)

    val lensDistortion = ControlRange(
        ControlId.LENS_DISTORTION, 0f, AMOUNT_MAX, 100f, 1f, 1f, bipolar = false, neutral = 100f,
        kind = ValueKind.PERCENT,
    )
    val lensVignetting = lensDistortion.copy(id = ControlId.LENS_VIGNETTING)
    val defringePurpleAmount = unipolar(ControlId.DEFRINGE_PURPLE_AMOUNT, DEFRINGE_MAX, 0f)
    val defringePurpleHueLow = unipolar(ControlId.DEFRINGE_PURPLE_HUE_LOW, TONE, Optics.PURPLE_LOW)
    val defringePurpleHueHigh = unipolar(ControlId.DEFRINGE_PURPLE_HUE_HIGH, TONE, Optics.PURPLE_HIGH)
    val defringeGreenAmount = unipolar(ControlId.DEFRINGE_GREEN_AMOUNT, DEFRINGE_MAX, 0f)
    val defringeGreenHueLow = unipolar(ControlId.DEFRINGE_GREEN_HUE_LOW, TONE, Optics.GREEN_LOW)
    val defringeGreenHueHigh = unipolar(ControlId.DEFRINGE_GREEN_HUE_HIGH, TONE, Optics.GREEN_HIGH)
    val transformVertical = signedInt(ControlId.TRANSFORM_VERTICAL)
    val transformHorizontal = signedInt(ControlId.TRANSFORM_HORIZONTAL)
    val transformRotate = bipolar(ControlId.TRANSFORM_ROTATE, ROTATE_MAX, ANGLE_STEP, ANGLE_STEP, ValueKind.DEGREES)
    val transformAspect = signedInt(ControlId.TRANSFORM_ASPECT)
    val transformScale = ControlRange(
        ControlId.TRANSFORM_SCALE, SCALE_MIN, SCALE_MAX, 100f, 1f, 1f, bipolar = false, neutral = 100f,
        kind = ValueKind.PERCENT,
    )
    val transformOffsetX = signedInt(ControlId.TRANSFORM_OFFSET_X)
    val transformOffsetY = signedInt(ControlId.TRANSFORM_OFFSET_Y)

    /** The optics and transform ranges (docs/adr/0015), in panel order. */
    val optics: List<ControlRange> = listOf(
        lensDistortion, lensVignetting, defringePurpleAmount, defringePurpleHueLow, defringePurpleHueHigh,
        defringeGreenAmount, defringeGreenHueLow, defringeGreenHueHigh,
    )
    val transform: List<ControlRange> = listOf(
        transformVertical, transformHorizontal, transformRotate, transformAspect, transformScale, transformOffsetX,
        transformOffsetY,
    )

    private fun split(id: ControlId, neutral: Float) = ControlRange(
        id, SPLIT_MIN, SPLIT_MAX, neutral, 1f, 1f, bipolar = false, neutral = neutral, kind = ValueKind.PERCENT,
    )

    private fun unipolarOf(id: ControlId, max: Float, neutral: Float, kind: ValueKind) =
        ControlRange(id, 0f, max, neutral, 1f, 1f, bipolar = false, neutral = neutral, kind = kind)

    /** Ranges of the tone curve, colour mixer, grey mixer and colour grading controls (docs/adr/0013). */
    private val colourTools: List<ControlRange> = ControlId.entries.mapNotNull { id ->
        val name = id.name
        when {
            id == ControlId.CURVE_SPLIT_SHADOWS -> split(id, ParametricCurve.DEFAULT_SPLIT_SHADOWS)

            id == ControlId.CURVE_SPLIT_MIDTONES -> split(id, ParametricCurve.DEFAULT_SPLIT_MIDTONES)

            id == ControlId.CURVE_SPLIT_HIGHLIGHTS -> split(id, ParametricCurve.DEFAULT_SPLIT_HIGHLIGHTS)

            id == ControlId.GRADE_BLENDING ->
                unipolarOf(id, TONE, ColourGrading.DEFAULT_BLENDING, ValueKind.INT)

            name.startsWith("GRADE_") && name.endsWith("_HUE") -> unipolarOf(id, HUE_MAX, 0f, ValueKind.HUE)

            name.startsWith("GRADE_") && name.endsWith("_SAT") -> unipolarOf(id, TONE, 0f, ValueKind.INT)

            name.startsWith("CURVE_") || name.startsWith("HUE_") || name.startsWith("SAT_") ||
                name.startsWith("LUM_") || name.startsWith("GREY_") || name.startsWith("GRADE_") -> signedInt(id)

            else -> null
        }
    }

    /** All descriptors in panel order. */
    val all: List<ControlRange> = listOf(
        profileAmount, exposure, contrast, highlights, shadows, whites, blacks,
        temperature, tint, relTemperature, relTint, vibrance, saturation, angle,
        sharpenAmount, sharpenRadius, sharpenDetail, sharpenMasking,
        nrLuminance, nrLuminanceDetail, nrLuminanceContrast, nrColour, nrColourDetail, nrColourSmoothness,
        texture, clarity, dehaze, vignetteAmount, vignetteMidpoint, vignetteRoundness, vignetteFeather,
        vignetteHighlights, grainAmount, grainSize, grainRoughness,
    ) + optics + transform + colourTools

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
        ValueKind.INT -> fixed(abs(value), 0)
        ValueKind.DECIMAL1 -> fixed(abs(value), 1)
        ValueKind.HUE -> "${value.roundToInt()}°"
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
