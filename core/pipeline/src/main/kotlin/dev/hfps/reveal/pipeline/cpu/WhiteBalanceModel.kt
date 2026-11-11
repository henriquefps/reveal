@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import kotlin.math.sqrt

/** Result of resolving white balance: the multipliers and the camera-to-working matrix that goes with them. */
class WhiteBalanceSolution(
    /** Raw white-balance multipliers (R, G, B) with G = 1, applied to the camera RGB. */
    val multipliers: DoubleArray,
    /** Balanced camera RGB (after [multipliers]) to linear working-space RGB. */
    val balancedToWorking: Mat3,
)

/**
 * The white-balance model: scene illuminant (temperature, tint) to raw multipliers, with chromatic adaptation. It
 * follows the DNG specification's camera-neutral approach (Adobe DNG 1.6, chapter 6, "Mapping Camera Color Space to
 * CIE XYZ Space") reduced to one calibration illuminant:
 *
 * 1. The camera's raw response to an illuminant with XYZ `W` is `cam = M^-1 W` (M = [PipelineInput.cameraToXyz]).
 * 2. The multipliers that make that illuminant neutral are `mul = (cam_g/cam_r, 1, cam_g/cam_b)`.
 * 3. As-shot: `mul` is the camera's own multipliers; the as-shot illuminant is `W = M * (1/mul)`.
 * 4. After the multipliers, balanced camera RGB goes to XYZ with `M * diag(1/mul)` (the camera's colour mixing,
 *    which still sees the original light) and is then adapted from the illuminant `W` to D65 with the Bradford
 *    transform (Lam 1985), and finally to the working space.
 *
 * The slider's temperature/tint are the CCT and Duv-derived tint of `W`: the Planckian locus in CIE 1960 UCS from
 * Krystek's rational approximation (1985, error below 0.0002 uv for 1000-15000 K), tint is a displacement
 * perpendicular to the locus, `duv = -tint / 3000` (tint +150 is 0.05 Duv towards magenta).
 *
 * **Display-referred input** (JPEG/HEIF/PNG, `PipelineInput.kind == DISPLAY_REFERRED`) ignores the absolute pair and
 * reads the relative one ([WhiteBalance.relTemperature], [WhiteBalance.relTint], -100..+100): a von Kries
 * (Bradford) adaptation in linear light from a pretended illuminant to the file's white (D65), see [relativeCct].
 *
 * Stable API for the editor (docs/adr/0008): [presetCct] / [preset] (WB chips), [neutralFromSample] (eyedropper),
 * [asShot], and [AutoWhiteBalance] (Auto).
 */
object WhiteBalanceModel {
    /** Lowest supported colour temperature in kelvin. */
    const val MIN_TEMPERATURE_K: Double = 2000.0

    /** Highest supported colour temperature in kelvin. */
    const val MAX_TEMPERATURE_K: Double = 15000.0

    private const val TINT_PER_DUV = 3000.0
    private const val SEARCH_ITERATIONS = 80
    private const val DERIVATIVE_STEP_K = 1.0
    private const val MIN_RESPONSE = 1e-3
    private val GOLDEN = (sqrt(5.0) - 1.0) / 2.0

    /** Colour temperature and tint of an illuminant. */
    data class Cct(val temperatureK: Double, val tint: Double)

    /** CIE 1960 UCS coordinates of the Planckian locus at [temperatureK] (Krystek 1985). */
    fun planckianUv(temperatureK: Double): DoubleArray {
        val t = temperatureK
        val t2 = t * t
        val u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t2) /
            (1.0 + 8.42420235e-4 * t + 7.08145163e-7 * t2)
        val v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t2) /
            (1.0 - 2.89741816e-5 * t + 1.61456053e-7 * t2)
        return doubleArrayOf(u, v)
    }

    /** XYZ (Y = 1) of the illuminant with the given CCT and tint. */
    fun illuminantXyz(temperatureK: Double, tint: Double): DoubleArray {
        val t = temperatureK.coerceIn(MIN_TEMPERATURE_K, MAX_TEMPERATURE_K)
        val locus = planckianUv(t)
        val normal = locusNormal(t)
        val duv = -tint / TINT_PER_DUV
        val u = locus[0] + normal[0] * duv
        val v = locus[1] + normal[1] * duv
        // CIE 1960 UCS to xy.
        val d = 2.0 * u - 8.0 * v + 4.0
        return ColorSpaces.xyToXyz(ColorSpaces.Xy(3.0 * u / d, 2.0 * v / d))
    }

    /** Temperature and tint of an illuminant given as XYZ (any scale). */
    fun cctAndTint(xyz: DoubleArray): Cct {
        val denominator = xyz[0] + 15.0 * xyz[1] + 3.0 * xyz[2]
        val u = 4.0 * xyz[0] / denominator
        val v = 6.0 * xyz[1] / denominator
        // Golden-section search for the nearest point on the locus (distance is unimodal near the locus).
        var lo = MIN_TEMPERATURE_K
        var hi = MAX_TEMPERATURE_K
        var x1 = hi - GOLDEN * (hi - lo)
        var x2 = lo + GOLDEN * (hi - lo)
        var f1 = distance2(u, v, x1)
        var f2 = distance2(u, v, x2)
        repeat(SEARCH_ITERATIONS) {
            if (f1 < f2) {
                hi = x2
                x2 = x1
                f2 = f1
                x1 = hi - GOLDEN * (hi - lo)
                f1 = distance2(u, v, x1)
            } else {
                lo = x1
                x1 = x2
                f1 = f2
                x2 = lo + GOLDEN * (hi - lo)
                f2 = distance2(u, v, x2)
            }
        }
        val t = (lo + hi) / 2.0
        val locus = planckianUv(t)
        val normal = locusNormal(t)
        val duv = (u - locus[0]) * normal[0] + (v - locus[1]) * normal[1]
        return Cct(t, -duv * TINT_PER_DUV)
    }

    /** The camera's own as-shot illuminant as temperature and tint (what the sliders show for "as shot"). */
    fun asShot(input: PipelineInput): Cct = cctAndTint(asShotWhite(input))

    /** Resolves the recipe's white balance against the camera: multipliers and the matrix into the working space. */
    fun solve(
        input: PipelineInput,
        wb: WhiteBalance,
        working: ColorSpaces.Rgb = ColorSpaces.REC2020,
    ): WhiteBalanceSolution {
        if (input.kind == InputKind.DISPLAY_REFERRED) return solveRelative(input, wb, working)
        val m = input.cameraToXyz
        val asShotMul = input.asShotMultipliers
            ?.let { doubleArrayOf(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
            ?: doubleArrayOf(1.0, 1.0, 1.0)
        val multipliers = if (wb.temperatureK == null && wb.tint == null) {
            normaliseGreen(asShotMul)
        } else {
            val asShotCct = cctAndTint(asShotWhite(input))
            val target = illuminantXyz(
                wb.temperatureK?.toDouble() ?: asShotCct.temperatureK,
                wb.tint?.toDouble() ?: asShotCct.tint,
            )
            val cam = m.inverse().times(target)
            val g = cam[1].coerceAtLeast(MIN_RESPONSE)
            doubleArrayOf(g / cam[0].coerceAtLeast(MIN_RESPONSE * g), 1.0, g / cam[2].coerceAtLeast(MIN_RESPONSE * g))
        }
        val illuminant = m.times(1.0 / multipliers[0], 1.0 / multipliers[1], 1.0 / multipliers[2])
        val adaptation = ColorSpaces.bradfordAdaptation(illuminant, ColorSpaces.D65_XYZ)
        val balancedToWorking = working.fromXyz * adaptation * m *
            Mat3.diagonal(1.0 / multipliers[0], 1.0 / multipliers[1], 1.0 / multipliers[2])
        return WhiteBalanceSolution(multipliers, balancedToWorking)
    }

    // ------------------------------------------------------------------ display-referred (relative) white balance

    /** Mireds per unit of relative temperature: ±100 moves the pretended light by ∓75 mired from D65. */
    const val MIRED_PER_REL_UNIT: Double = 0.75

    /** The model's own temperature and tint of D65 (CIE D65 sits slightly above the Planckian locus). */
    val D65_CCT: Cct by lazy { cctAndTint(ColorSpaces.D65_XYZ) }

    /**
     * The illuminant the relative sliders pretend the light was, as temperature and tint. A positive
     * [relTemperature] makes the picture warmer: the pretended light is cooler than D65 by
     * `0.75 * relTemperature` mired (+100 = 12 690 K, -100 = 4 371 K); a positive [relTint] makes it more magenta:
     * the pretended light is greener by `relTint` tint units (100 = 0.033 Duv). Mireds are used because equal
     * mired steps look like equal colour changes (Judd 1960; the scale of most raw converters' temperature slider).
     */
    fun relativeCct(relTemperature: Double, relTint: Double): Cct {
        val mired = MIRED_PER_K / D65_CCT.temperatureK - MIRED_PER_REL_UNIT * relTemperature
        return Cct(MIRED_PER_K / mired, D65_CCT.tint - relTint)
    }

    private fun solveRelative(input: PipelineInput, wb: WhiteBalance, working: ColorSpaces.Rgb): WhiteBalanceSolution {
        val m = input.cameraToXyz
        val relT = RecipeRanges.relTemperature.clamp(wb.relTemperature).toDouble()
        val relTint = RecipeRanges.relTint.clamp(wb.relTint).toDouble()
        val toXyz = if (relT == 0.0 && relTint == 0.0) {
            m
        } else {
            // Adapt from the pretended light to the model's D65 point (not the exact D65 xy), so that the shift is
            // relative to the same locus model on both sides and 0/0 is exactly the identity.
            val pretended = relativeCct(relT, relTint)
            val source = illuminantXyz(pretended.temperatureK, pretended.tint)
            val dest = illuminantXyz(D65_CCT.temperatureK, D65_CCT.tint)
            ColorSpaces.bradfordAdaptation(source, dest) * m
        }
        return WhiteBalanceSolution(doubleArrayOf(1.0, 1.0, 1.0), working.fromXyz * toXyz)
    }

    // ------------------------------------------------------------------ presets and eyedropper

    /**
     * The illuminant of each white-balance preset, as CIE chromaticities (CIE 15:2004): Daylight = D55, Cloudy = D65,
     * Shade = D75 (the CIE daylight locus at 5503, 6504 and 7504 K), Tungsten = illuminant A (2856 K), Fluorescent =
     * F2 (cool white, 4230 K, slightly green), Flash = the daylight locus at 5900 K (xenon flash tubes). `null` for
     * the modes that are not a fixed light (as shot, auto, custom).
     */
    fun presetXy(mode: WbMode): ColorSpaces.Xy? = when (mode) {
        WbMode.DAYLIGHT -> daylightLocus(5503.0)
        WbMode.CLOUDY -> daylightLocus(6504.0)
        WbMode.SHADE -> daylightLocus(7504.0)
        WbMode.TUNGSTEN -> ColorSpaces.Xy(0.44757, 0.40745)
        WbMode.FLUORESCENT -> ColorSpaces.Xy(0.37208, 0.37529)
        WbMode.FLASH -> daylightLocus(5900.0)
        WbMode.AS_SHOT, WbMode.AUTO, WbMode.CUSTOM -> null
    }

    /** Temperature and tint of a preset ([presetXy]) on this model's scale, or `null`. */
    fun presetCct(mode: WbMode): Cct? = presetXy(mode)?.let { cctAndTint(ColorSpaces.xyToXyz(it)) }

    /**
     * The recipe white balance a preset chip writes: as shot clears the override; a light preset writes its
     * temperature/tint (RAW). Display-referred input has no absolute light, so its presets are as shot and
     * Auto/Custom only (`null` for the light presets there); Auto and Custom need data ([AutoWhiteBalance],
     * [neutralFromSample]) and return `null` here.
     */
    fun preset(mode: WbMode, input: PipelineInput): WhiteBalance? = when {
        mode == WbMode.AS_SHOT -> WhiteBalance(mode = WbMode.AS_SHOT)
        input.kind == InputKind.DISPLAY_REFERRED -> null
        else -> presetCct(mode)?.let { WhiteBalance(it.temperatureK.toFloat(), it.tint.toFloat(), mode) }
    }

    /**
     * Eyedropper: the white balance that makes [rgb] neutral. [rgb] is the input's own linear pixel (or the mean of
     * a small area) as found in `PipelineInput.linearRgb`: un-balanced camera RGB for a RAW, linear file RGB for a
     * display-referred image. For a RAW a neutral surface's raw response is the light's, so the light is
     * `XYZ = cameraToXyz * rgb` and the result is its temperature/tint (clamped to the slider ranges). For a
     * display-referred image the sample's chromaticity is taken as the pretended light and [relativeCct] is inverted.
     * `mode` is [WbMode.CUSTOM].
     */
    fun neutralFromSample(input: PipelineInput, rgb: FloatArray): WhiteBalance {
        require(rgb.size == PipelineInput.CHANNELS) { "an RGB sample has 3 values" }
        val xyz = input.cameraToXyz.times(
            rgb[0].toDouble().coerceAtLeast(MIN_SAMPLE),
            rgb[1].toDouble().coerceAtLeast(MIN_SAMPLE),
            rgb[2].toDouble().coerceAtLeast(MIN_SAMPLE),
        )
        return fromIlluminant(input, xyz, WbMode.CUSTOM)
    }

    /** The recipe white balance for a light with XYZ [xyz] measured in [input]'s colour space. */
    fun fromIlluminant(input: PipelineInput, xyz: DoubleArray, mode: WbMode): WhiteBalance {
        val cct = cctAndTint(xyz)
        return if (input.kind == InputKind.DISPLAY_REFERRED) {
            val mired = MIRED_PER_K / cct.temperatureK
            val relT = (MIRED_PER_K / D65_CCT.temperatureK - mired) / MIRED_PER_REL_UNIT
            val relTint = D65_CCT.tint - cct.tint
            WhiteBalance(
                mode = mode,
                relTemperature = RecipeRanges.relTemperature.clamp(relT.toFloat()),
                relTint = RecipeRanges.relTint.clamp(relTint.toFloat()),
            )
        } else {
            WhiteBalance(
                RecipeRanges.temperature.clamp(cct.temperatureK.toFloat()),
                RecipeRanges.tint.clamp(cct.tint.toFloat()),
                mode,
            )
        }
    }

    /** CIE daylight locus chromaticity at correlated colour temperature [t] (CIE 15:2004, eq. 3.3/3.4). */
    fun daylightLocus(t: Double): ColorSpaces.Xy {
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 7000.0) {
            -4.6070e9 / t3 + 2.9678e6 / t2 + 0.09911e3 / t + 0.244063
        } else {
            -2.0064e9 / t3 + 1.9018e6 / t2 + 0.24748e3 / t + 0.237040
        }
        return ColorSpaces.Xy(x, -3.0 * x * x + 2.870 * x - 0.275)
    }

    private const val MIRED_PER_K = 1e6
    private const val MIN_SAMPLE = 1e-9

    private fun asShotWhite(input: PipelineInput): DoubleArray {
        val mul = input.asShotMultipliers
        return if (mul == null) {
            input.cameraToXyz.times(1.0, 1.0, 1.0)
        } else {
            input.cameraToXyz.times(1.0 / mul[0], 1.0 / mul[1], 1.0 / mul[2])
        }
    }

    private fun normaliseGreen(mul: DoubleArray) = doubleArrayOf(mul[0] / mul[1], 1.0, mul[2] / mul[1])

    private fun distance2(u: Double, v: Double, temperatureK: Double): Double {
        val l = planckianUv(temperatureK)
        return (u - l[0]) * (u - l[0]) + (v - l[1]) * (v - l[1])
    }

    /** Unit normal of the locus at [temperatureK] pointing to the green side (positive v). */
    private fun locusNormal(temperatureK: Double): DoubleArray {
        val a = planckianUv(temperatureK - DERIVATIVE_STEP_K)
        val b = planckianUv(temperatureK + DERIVATIVE_STEP_K)
        val du = b[0] - a[0]
        val dv = b[1] - a[1]
        val length = sqrt(du * du + dv * dv)
        var nx = -dv / length
        var ny = du / length
        if (ny < 0.0) {
            nx = -nx
            ny = -ny
        }
        return doubleArrayOf(nx, ny)
    }
}
