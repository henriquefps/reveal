@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Light sliders resolved to operator constants (computed in double, rounded once). Every field at its neutral
 * value means the operator is skipped (bit-exact identity on CPU and GPU).
 *
 * @property highlightStops highlights slider / 100 times [LocalToneOp.HIGHLIGHT_STOPS] (negative = darken).
 * @property shadowStops shadows slider / 100 times [LocalToneOp.SHADOW_STOPS] (positive = lift).
 * @property contrastGamma slope of the contrast curve at its pivot, `2^(contrast/100 * CONTRAST_LOG2)`.
 * @property whitesShift move of the white end in lightness units (whites / 100 times [GlobalToneOp.WHITES_RANGE]).
 * @property blacksShift move of the black end in lightness units (blacks / 100 times [GlobalToneOp.BLACKS_RANGE]).
 */
data class ToneParams(
    val highlightStops: Float = 0f,
    val shadowStops: Float = 0f,
    val contrastGamma: Float = 1f,
    val whitesShift: Float = 0f,
    val blacksShift: Float = 0f,
) {
    /** Highlights or shadows active: the guide passes and the local apply run. */
    val localActive: Boolean get() = highlightStops != 0f || shadowStops != 0f

    /** Contrast, whites or blacks active. */
    val globalActive: Boolean get() = contrastGamma != 1f || whitesShift != 0f || blacksShift != 0f

    companion object {
        private const val PERCENT = 100.0

        /** Resolves the Light group's tone sliders (clamped to [RecipeRanges]). */
        fun from(light: Light): ToneParams {
            fun unit(v: Float, range: dev.hfps.reveal.pipeline.recipe.ControlRange) = range.clamp(v) / PERCENT
            return ToneParams(
                highlightStops = (unit(light.highlights, RecipeRanges.highlights) * LocalToneOp.HIGHLIGHT_STOPS)
                    .toFloat(),
                shadowStops = (unit(light.shadows, RecipeRanges.shadows) * LocalToneOp.SHADOW_STOPS).toFloat(),
                contrastGamma = StrictMath.pow(
                    2.0,
                    unit(light.contrast, RecipeRanges.contrast) * GlobalToneOp.CONTRAST_LOG2,
                ).toFloat(),
                whitesShift = (unit(light.whites, RecipeRanges.whites) * GlobalToneOp.WHITES_RANGE).toFloat(),
                blacksShift = (unit(light.blacks, RecipeRanges.blacks) * GlobalToneOp.BLACKS_RANGE).toFloat(),
            )
        }
    }
}

/** Rec.2020 luminance weights (ITU-R BT.2020), shared by the tone operators. */
internal object Luma {
    const val R: Float = 0.2627f
    const val G: Float = 0.6780f
    const val B: Float = 0.0593f

    fun of(r: Float, g: Float, b: Float): Float = R * r + G * g + B * b
}

/**
 * Highlights and shadows: edge-aware local tone mapping on scene-linear light, before the base look (docs/adr/0007).
 *
 * Work in log luminance `l = log2(max(Y, 2^-20))` (Rec.2020 Y; 0 = the reference white, -2.47 = mid grey).
 * 1. **Base layer** `B` = a fast guided filter of `l` guided by itself (He, Sun, Tang 2013, "Guided Image
 *    Filtering", TPAMI; He and Sun 2015, "Fast Guided Filter", arXiv:1505.00996) on the [LowResGrid]: cell means of
 *    `l` and `l^2`, box mean of radius [RADIUS] cells, `a = var / (var + EPS)`, `b = mean (1 - a)`, box mean of
 *    `a` and `b` again, then at full resolution `B = a' l + b'` with `a'`, `b'` upsampled edge-aware
 *    ([sampleCoef]). In flat regions `B` is the
 *    local mean, so texture lives in the detail `l - B` and survives; across an edge stronger than `sqrt(EPS)` the
 *    filter keeps `a` near 1 so `B` follows the pixel: the operator becomes pointwise right at the edge and no halo
 *    can form (a dark subject next to a bright sky is lifted as far as the same subject in the middle of the frame).
 * 2. **Tone curve on the base**, two monotonic maps applied one after the other (so their composition is monotonic
 *    for every pair of slider values): shadows `B1 = B + S wS(B)`, a bump that is 1 between -6 and -5.5 stops and
 *    fades out at -12 (black stays black) and at -1.5 (mid grey moves a little, highlights not at all); highlights
 *    `B2 = B1 + H wH(B1)`, `wH = smoothstep(-3, -0.5, B) + K ramp(B + 0.5)` with `ramp(x) = (sqrt(x^2 + D^2) + x)/2`
 *    a smooth `max(0, x)`. The ramp makes negative highlights *compress* everything above the reference white
 *    instead of shifting it, so the headroom of a RAW comes back into range: at -100 white goes to -1.36 stops, two
 *    stops over white to -0.51, four stops over to +0.29 (still bright, with its detail).
 * 3. **Luminance-preserving apply:** `rgb *= 2^(B2 - B)`; a gain on all three channels keeps chromaticity and hue.
 *
 * Lightroom-like intuition (not a copy of its formulas): -100 highlights on a bright sky darkens and pulls back the
 * clouds while the texture of the clouds (detail) stays; +100 shadows on a backlit face lifts it by up to 1.75 stops
 * with its local contrast intact and no bright rim where it meets the sky.
 */
object LocalToneOp {
    /** Stops of the highlights curve per slider unit (at 100). */
    const val HIGHLIGHT_STOPS: Double = 1.0

    /** Stops of the shadows curve per slider unit (at 100). */
    const val SHADOW_STOPS: Double = 1.75

    /** Box radius of the guided filter, in grid cells. */
    const val RADIUS: Int = 6

    /** Guided-filter regulariser in stops squared: structures weaker than ~0.18 stops (its root) count as detail. */
    const val EPS: Float = 0.03125f

    /** Floor of the log luminance, in stops. */
    const val LOG_FLOOR: Float = -20f

    const val H0: Float = -3f
    const val H1: Float = -0.5f
    const val K: Float = 0.6f
    const val D: Float = 0.5f
    const val S0: Float = -12f
    const val S1: Float = -6f
    const val S2: Float = -5.5f
    const val S3: Float = -1.5f

    private const val LN2 = 0.6931471805599453

    /** `2^LOG_FLOOR`, the luminance floor of [logLuminance]. */
    const val MIN_Y: Float = 9.5367431640625e-7f
    private const val COMPS = 2

    /** `log2(max(Y, 2^-20))` of a working-space pixel. */
    fun logLuminance(r: Float, g: Float, b: Float): Float =
        (StrictMath.log(max(Luma.of(r, g, b), MIN_Y).toDouble()) / LN2).toFloat()

    /** Highlights weight of base value [b]. */
    fun highlightWeight(b: Float): Float {
        val x = b - H1
        return LowResGrid.smoothstep(H0, H1, b) + K * ((sqrt(x * x + D * D) + x) * HALF)
    }

    /** Shadows weight (bump) of base value [b]. */
    fun shadowWeight(b: Float): Float = LowResGrid.smoothstep(S0, S1, b) * (1f - LowResGrid.smoothstep(S2, S3, b))

    /** The tone curve on the base layer: shadows then highlights; returns `B2 - B` in stops. */
    fun delta(b: Float, p: ToneParams): Float {
        val b1 = b + p.shadowStops * shadowWeight(b)
        val b2 = b1 + p.highlightStops * highlightWeight(b1)
        return b2 - b
    }

    /** Cell means of `l` and `l^2` (`gridWidth*gridHeight*2`) of the image [rgb]: the guide's first step. */
    fun moments(rgb: FloatArray, grid: LowResGrid): FloatArray = grid.reduceCells(COMPS) { index, acc ->
        val i = index * CHANNELS
        val l = logLuminance(rgb[i], rgb[i + 1], rgb[i + 2])
        acc[0] += l
        acc[1] += l * l
    }

    /** Guided-filter coefficients `(a', b')` per grid cell (`gridWidth*gridHeight*2`) from the cell [moments]. */
    fun guide(moments: FloatArray, grid: LowResGrid): FloatArray {
        val gw = grid.gridWidth
        val gh = grid.gridHeight
        val mean = box(box(moments, gw, gh, horizontal = true), gw, gh, horizontal = false)
        val coef = FloatArray(gw * gh * COMPS)
        for (i in 0 until gw * gh) {
            val m = mean[i * COMPS]
            val variance = max(mean[i * COMPS + 1] - m * m, 0f)
            val a = variance / (variance + EPS)
            coef[i * COMPS] = a
            coef[i * COMPS + 1] = m * (1f - a)
        }
        return box(box(coef, gw, gh, horizontal = true), gw, gh, horizontal = false)
    }

    /** Box mean of radius [RADIUS] along one axis, normalised by the cells inside the grid (2 components). */
    @Suppress("NestedBlockDepth")
    fun box(src: FloatArray, w: Int, h: Int, horizontal: Boolean): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s0 = 0f
                var s1 = 0f
                var n = 0
                for (k in -RADIUS..RADIUS) {
                    val sx = if (horizontal) x + k else x
                    val sy = if (horizontal) y else y + k
                    if (sx in 0 until w && sy in 0 until h) {
                        s0 += src[(sy * w + sx) * COMPS]
                        s1 += src[(sy * w + sx) * COMPS + 1]
                        n++
                    }
                }
                out[(y * w + x) * COMPS] = s0 / n
                out[(y * w + x) * COMPS + 1] = s1 / n
            }
        }
        return out
    }

    /**
     * The guide coefficients at full-resolution pixel ([x], [y]) with log luminance [l]: the four nearest cells,
     * each weighted by its bilinear weight times a range weight `max(exp(-(l - mean)^2 / (2 RANGE_SIGMA^2)),
     * RANGE_FLOOR)` on the cell's mean `l` (joint bilateral upsampling, Kopf et al. 2007). Plain bilinear
     * upsampling would blend the coefficients of the dark and the bright side for the pixels of a cell next to an
     * edge (a fringe one cell wide); the range weight makes a pixel take the coefficients of the cells on its own
     * side. Writes `(a', b')` into [out].
     */
    @Suppress("LongParameterList")
    fun sampleCoef(coef: FloatArray, moments: FloatArray, grid: LowResGrid, x: Int, y: Int, l: Float, out: FloatArray) {
        val w = grid.gridWidth
        val h = grid.gridHeight
        val uc = min(max(grid.toGrid(x), 0f), (w - 1).toFloat())
        val vc = min(max(grid.toGrid(y), 0f), (h - 1).toFloat())
        val x0 = floor(uc).toInt()
        val y0 = floor(vc).toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = uc - x0
        val fy = vc - y0
        var sw = 0f
        var sa = 0f
        var sb = 0f
        for (k in 0 until CORNERS) {
            val cx = if (k == 1 || k == 3) x1 else x0
            val cy = if (k >= 2) y1 else y0
            val bx = if (k == 1 || k == 3) fx else 1f - fx
            val by = if (k >= 2) fy else 1f - fy
            val i = (cy * w + cx) * COMPS
            val d = l - moments[i]
            val range = max(StrictMath.exp((-(d * d) * RANGE_SCALE).toDouble()).toFloat(), RANGE_FLOOR)
            val weight = bx * by * range
            sw += weight
            sa += weight * coef[i]
            sb += weight * coef[i + 1]
        }
        out[0] = sa / sw
        out[1] = sb / sw
    }

    /** Applies highlights/shadows to scene-linear [rgb] (in place) with the guide [coef] and cell [moments]. */
    fun apply(rgb: FloatArray, grid: LowResGrid, moments: FloatArray, coef: FloatArray, p: ToneParams) {
        val ab = FloatArray(COMPS)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val i = (y * grid.width + x) * CHANNELS
                val l = logLuminance(rgb[i], rgb[i + 1], rgb[i + 2])
                sampleCoef(coef, moments, grid, x, y, l, ab)
                val base = ab[0] * l + ab[1]
                val gain = StrictMath.pow(2.0, delta(base, p).toDouble()).toFloat()
                rgb[i] *= gain
                rgb[i + 1] *= gain
                rgb[i + 2] *= gain
            }
        }
    }

    /** Range sigma of the coefficient upsampling, in stops. */
    const val RANGE_SIGMA: Float = 0.5f

    /** Smallest range weight, so a pixel unlike all four cells (a thin line) falls back to bilinear. */
    const val RANGE_FLOOR: Float = 1e-3f

    /** `1 / (2 RANGE_SIGMA^2)`. */
    const val RANGE_SCALE: Float = 2f

    private const val CORNERS = 4
    private const val HALF = 0.5f
    private const val CHANNELS = 3
}

/**
 * Contrast, whites and blacks: global curves on display-referred light, after the base look (ADR 0002 slot 8),
 * on luminance so hue never shifts.
 *
 * Lightness `p = cbrt(max(Y, 0))` (the cube root of CIE L*; mid grey 0.18 -> [PIVOT] 0.565, white 1 -> 1):
 * 1. **Contrast**, an S-curve through the pivot with fixed ends: two cubic Hermite segments, (0, 0) -> (p0, p0) and
 *    (p0, p0) -> (1, 1), slope `g` at the pivot and `1/g` at both ends, identity from 1 (display-referred input
 *    with headroom). C1 at the pivot, `g = 2^(contrast/100 * 0.7)` (+100: slope 1.62; -100: 0.62), strictly
 *    monotonic (Fritsch and Carlson 1980: end tangents `g`, `1/g` over a unit secant satisfy `g^2 + g^-2 <= 9`).
 *    Finite slopes everywhere: a power curve `p0 (p/p0)^g` would have an infinite slope at the ends for `g < 1`,
 *    amplifying any difference next to black and white. Positive contrast darkens the toe and brightens the
 *    shoulder without ever pushing white past 1.
 * 2. **Whites** move the white end: `p + w smoothstep(p0, 1, p)` (`w` = +-[WHITES_RANGE]); +100 sends the top of
 *    the range past white (more clipped whites, like Lightroom), -100 brings white down to lightness 0.88.
 * 3. **Blacks** move the black end: `p + k (1 - smoothstep(0, p0, p))` (`k` = +-[BLACKS_RANGE]); +100 lifts black
 *    to a matte grey (Y = 0.008), -100 crushes everything below Y = 0.008 to black.
 *
 * Applied as a gain `g = min(Y'/max(Y, YMIN), GMAX)` on the three channels, plus the rest of the luminance change
 * added as neutral grey (`Y' - max(Y,0) g`, never negative): a pure gain keeps chromaticity, and the neutral
 * remainder lets blacks lift a pure black (where no gain can) without amplifying the noise of the deep shadows more
 * than [GMAX] times.
 */
object GlobalToneOp {
    /** log2 of the contrast slope at +100. */
    const val CONTRAST_LOG2: Double = 0.7

    /** Whites move at +-100, in lightness. */
    const val WHITES_RANGE: Double = 0.12

    /** Blacks move at +-100, in lightness. */
    const val BLACKS_RANGE: Double = 0.2

    /** Lightness of mid grey, `cbrt(0.18)`. */
    val PIVOT: Float = StrictMath.cbrt(0.18).toFloat()

    /** Largest gain on the channels; the rest of a lift is added as neutral. */
    const val GMAX: Float = 8f

    /** Luminance below which the gain is computed against this value. */
    const val YMIN: Float = 1e-5f

    private const val THIRD = 1.0 / 3.0

    /** Contrast curve on lightness [p]: two cubic Hermite segments meeting at the pivot with slope [gamma]. */
    fun contrast(p: Float, gamma: Float): Float {
        val p0 = PIVOT
        val end = 1f / gamma
        return when {
            p <= 0f || p >= 1f -> p
            p <= p0 -> hermite(p / p0, 0f, p0, end * p0, gamma * p0)
            else -> hermite((p - p0) / (1f - p0), p0, 1f, gamma * (1f - p0), end * (1f - p0))
        }
    }

    /** Cubic Hermite on t in [0, 1] from [y0] to [y1] with end tangents [m0], [m1] (already scaled by the width). */
    fun hermite(t: Float, y0: Float, y1: Float, m0: Float, m1: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return (2f * t3 - 3f * t2 + 1f) * y0 + (t3 - 2f * t2 + t) * m0 + (-2f * t3 + 3f * t2) * y1 + (t3 - t2) * m1
    }

    /** The whole curve on lightness [p]: contrast, whites, blacks. */
    fun curve(p: Float, t: ToneParams): Float {
        val p1 = contrast(p, t.contrastGamma)
        val p2 = p1 + t.whitesShift * LowResGrid.smoothstep(PIVOT, 1f, p1)
        return p2 + t.blacksShift * (1f - LowResGrid.smoothstep(0f, PIVOT, p2))
    }

    /** Applies contrast/whites/blacks to display-referred [rgb] in place. */
    fun apply(rgb: FloatArray, t: ToneParams) {
        var i = 0
        while (i < rgb.size) {
            val y = Luma.of(rgb[i], rgb[i + 1], rgb[i + 2])
            val yc = max(y, 0f)
            val p = pow(yc, THIRD.toFloat())
            val q = max(curve(p, t), 0f)
            val y2 = q * q * q
            val gain = min(y2 / max(yc, YMIN), GMAX)
            val add = max(y2 - yc * gain, 0f)
            rgb[i] = rgb[i] * gain + add
            rgb[i + 1] = rgb[i + 1] * gain + add
            rgb[i + 2] = rgb[i + 2] * gain + add
            i += CHANNELS
        }
    }

    private fun pow(x: Float, e: Float): Float = StrictMath.pow(x.toDouble(), e.toDouble()).toFloat()

    private const val CHANNELS = 3
}
