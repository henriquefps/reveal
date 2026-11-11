@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
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
