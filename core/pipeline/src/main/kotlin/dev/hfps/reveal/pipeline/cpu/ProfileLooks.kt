@file:Suppress("MagicNumber", "SpreadOperator")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.min

/**
 * The base looks (profile slot 6, docs/adr/0008). Each look is a [Look]: a 3x3 into the curve's space (with the
 * profile gain), a tone curve, a 3x3 back to Rec.2020 and a hue treatment. The designed looks use the [Filmic]
 * curve family and Rec.2020 as the curve space; Camera JPEG match uses the curve, matrix and hue treatment fitted to
 * the ZV-E10's own JPEGs ([CameraMatchZvE10]) with sRGB primaries as the curve space, as the camera does.
 *
 * **Amount** `t = amount / 100` (0..2) blends each part from Standard: `pre`, `post` and the curve knots
 * (`y`, `m`) are `std + t (look - std)` (the curve is then made monotone again, [ToneCurve.limited]), the hue
 * coefficients are `t look`. Monochrome's colour part stops at 100 % (`2 mix - I` would invert colours); its
 * extra contrast continues to 200 %. Standard ignores the amount (ADR 0003).
 */
object ProfileLooks {
    /** Scene grey: the designed curves map `GREY` (after the gain) to display `GREY`. */
    const val GREY: Double = 0.18

    private const val LN2 = 0.6931471805599453

    /**
     * The filmic curve family, in stops around grey: `s = log2(x / GREY)`, `y = GREY 2^F(s)` with
     *
     * ```
     * F(s) = c s / (1 + (c s / A)^n)^(1/n)             s > 0   (shoulder: approaches A, i.e. y -> GREY 2^A)
     * F(s) = c s (1 + toe q / (1 + q)),  q = -s        s <= 0  (toe: the log slope grows from c to c (1 + toe))
     * ```
     *
     * [contrast] `c` is the slope in stops per stop at grey, [shoulder] `n` how sharply highlights roll off (higher
     * = later, harder), [whiteStops] `A` the asymptote (`GREY 2^A` = the display white the curve approaches) and
     * [toe] the deepening of the shadows. C1 at grey (both sides have slope `c`). [gainEv] is the profile's
     * baseline exposure: scene-linear values are multiplied by `2^gainEv` before the curve.
     */
    data class Filmic(
        val gainEv: Double,
        val contrast: Double,
        val shoulder: Double,
        val whiteStops: Double,
        val toe: Double,
    ) {
        /** `F(s)`. */
        fun stops(s: Double): Double = if (s > 0) {
            val u = contrast * s / whiteStops
            contrast * s / StrictMath.pow(1.0 + StrictMath.pow(u, shoulder), 1.0 / shoulder)
        } else {
            val q = -s
            contrast * s * (1.0 + toe * q / (1.0 + q))
        }

        /** `dF/ds`. */
        fun slope(s: Double): Double = if (s > 0) {
            val u = contrast * s / whiteStops
            contrast * StrictMath.pow(1.0 + StrictMath.pow(u, shoulder), -(shoulder + 1.0) / shoulder)
        } else {
            val q = -s
            contrast * (1.0 + toe * q / (1.0 + q) + toe * q / ((1.0 + q) * (1.0 + q)))
        }

        /** The curve sampled at the knots, with this function's own tangents (`dy/dlog2 x = y ln2 F'(s)`). */
        fun curve(): ToneCurve {
            val log2Grey = StrictMath.log(GREY) / LN2
            return ToneCurve.sample(
                value = { l -> GREY * StrictMath.pow(2.0, stops(l - log2Grey)) },
                slope = { l -> GREY * StrictMath.pow(2.0, stops(l - log2Grey)) * LN2 * slope(l - log2Grey) },
            )
        }

        val gain: Double get() = StrictMath.pow(2.0, gainEv)
    }

    /** A resolved look: everything [ProfileOp] needs at 100 %. */
    class Look(
        val pre: Mat3,
        val curve: ToneCurve,
        val post: Mat3,
        val hueChroma: DoubleArray,
        val hueShift: DoubleArray,
        /** Highest amount the colour part ([pre], [post], hue) follows; the curve always follows up to 200 %. */
        val colourAmountMax: Double = 2.0,
    )

    /**
     * Standard: the tone of the ZV-E10's JPEGs (baseline exposure, contrast, toe, shoulder fitted to the camera,
     * [CameraMatchZvE10]) with neutral, colorimetric colour. The default look.
     */
    val STANDARD_FILMIC: Filmic = Filmic(
        gainEv = CameraMatchZvE10.STANDARD_GAIN_EV,
        contrast = CameraMatchZvE10.STANDARD_CONTRAST,
        shoulder = CameraMatchZvE10.STANDARD_SHOULDER,
        whiteStops = CameraMatchZvE10.STANDARD_WHITE_STOPS,
        toe = CameraMatchZvE10.STANDARD_TOE,
    )

    /** Neutral/Flat: one-to-one contrast at grey, no toe, a soft shoulder, 10 % less chroma. */
    val NEUTRAL_FILMIC: Filmic = STANDARD_FILMIC.copy(contrast = 0.95, shoulder = 3.0, toe = 0.0)

    /** Vivid: more contrast and a deeper toe; chroma +22 %. */
    val VIVID_FILMIC: Filmic = STANDARD_FILMIC.copy(
        contrast = STANDARD_FILMIC.contrast * 1.12,
        toe = STANDARD_FILMIC.toe + 0.1,
    )

    /** Portrait: slightly softer contrast; skin hues (OkLab 55°) 10 % less chroma, everything 4 % less. */
    val PORTRAIT_FILMIC: Filmic = STANDARD_FILMIC.copy(contrast = STANDARD_FILMIC.contrast * 0.95)

    /** Landscape: more contrast; greens (OkLab 140°) +25 % and blues (255°) +20 % chroma. */
    val LANDSCAPE_FILMIC: Filmic = STANDARD_FILMIC.copy(contrast = STANDARD_FILMIC.contrast * 1.06)

    /** Monochrome: more contrast for black and white. */
    val MONOCHROME_FILMIC: Filmic = STANDARD_FILMIC.copy(contrast = STANDARD_FILMIC.contrast * 1.08)

    /**
     * Monochrome channel mix on linear Rec.2020: the Rec.2020 luminance weights (0.2627, 0.6780, 0.0593) with 0.06
     * moved from green to red, which lightens skin and warm tones a little (like a mild yellow-orange filter) and
     * keeps blue skies where luminance puts them. Rows equal, so the output is exactly neutral.
     */
    val MONOCHROME_MIX: DoubleArray = doubleArrayOf(0.3227, 0.6180, 0.0593)

    /** Standard at 100 %. */
    val standardLook: Look by lazy { designed(STANDARD_FILMIC) }

    private val looks: Map<BaseLook, Look> by lazy {
        mapOf(
            BaseLook.STANDARD to standardLook,
            BaseLook.NEUTRAL to designed(NEUTRAL_FILMIC, chroma = global(-0.10)),
            BaseLook.VIVID to designed(VIVID_FILMIC, chroma = global(0.22)),
            BaseLook.PORTRAIT to designed(
                PORTRAIT_FILMIC,
                chroma = sum(global(-0.04), HueTreatment.lobe(-0.10, VibranceSaturationOp.SKIN_HUE_DEGREES)),
            ),
            BaseLook.LANDSCAPE to designed(
                LANDSCAPE_FILMIC,
                chroma = sum(HueTreatment.lobe(0.25, GREEN_HUE), HueTreatment.lobe(0.20, BLUE_HUE)),
            ),
            BaseLook.MONOCHROME to Look(
                pre = Mat3(*DoubleArray(9) { MONOCHROME_MIX[it % 3] }) * scalar(MONOCHROME_FILMIC.gain),
                curve = MONOCHROME_FILMIC.curve(),
                post = Mat3.IDENTITY,
                hueChroma = DoubleArray(HueTreatment.COEFFICIENTS),
                hueShift = DoubleArray(HueTreatment.COEFFICIENTS),
                colourAmountMax = 1.0,
            ),
            BaseLook.CAMERA_MATCH to CameraMatchZvE10.look(),
        )
    }

    /** OkLab hue of foliage greens. */
    const val GREEN_HUE: Double = 140.0

    /** OkLab hue of sky blues. */
    const val BLUE_HUE: Double = 255.0

    /** The look for [base] at 100 %. */
    fun look(base: BaseLook): Look = looks.getValue(base)

    /** [profile] resolved for scene-referred input (amount clamped to 0..200 %). */
    fun resolve(profile: Profile): ProfileParams {
        val std = standardLook
        if (profile.base == BaseLook.STANDARD) return params(std, std, 1.0, 1.0)
        val look = look(profile.base)
        val t = RecipeRanges.profileAmount.clamp(profile.amountPercent).toDouble() / 100.0
        return params(std, look, t, min(t, look.colourAmountMax))
    }

    /** [look] blended from [std] by [t] (curve) and [tc] (colour), see the class comment. */
    fun params(std: Look, look: Look, t: Double, tc: Double): ProfileParams {
        val pre = lerp(std.pre, look.pre, tc)
        val post = lerp(std.post, look.post, tc)
        return ProfileParams(
            enabled = true,
            pre = pre.toFloatArray(),
            curve = ToneCurve.lerp(std.curve, look.curve, t),
            post = post.toFloatArray(),
            hueChroma = FloatArray(HueTreatment.COEFFICIENTS) { (tc * look.hueChroma[it]).toFloat() },
            hueShift = FloatArray(HueTreatment.COEFFICIENTS) { (tc * look.hueShift[it]).toFloat() },
        )
    }

    /** A designed look: the filmic curve in Rec.2020 with [chroma] as its hue treatment. */
    fun designed(filmic: Filmic, chroma: DoubleArray = DoubleArray(HueTreatment.COEFFICIENTS)): Look = Look(
        pre = scalar(filmic.gain),
        curve = filmic.curve(),
        post = Mat3.IDENTITY,
        hueChroma = chroma,
        hueShift = DoubleArray(HueTreatment.COEFFICIENTS),
    )

    private fun global(amount: Double) = DoubleArray(HueTreatment.COEFFICIENTS).also { it[0] = amount }

    private fun sum(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + b[it] }

    private fun scalar(k: Double) = Mat3.diagonal(k, k, k)

    @Suppress("SpreadOperator")
    private fun lerp(a: Mat3, b: Mat3, t: Double): Mat3 =
        Mat3(*DoubleArray(9) { a[it / 3, it % 3] + t * (b[it / 3, it % 3] - a[it / 3, it % 3]) })
}
