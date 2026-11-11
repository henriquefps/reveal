@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// CPU reference of the tone curve (slot 8, after contrast/whites/blacks), docs/adr/0013. Mirrored by the GLSL in
// `gl/ColourToolShaders.kt`.

/**
 * Monotone piecewise cubic Hermite interpolation through points with strictly increasing x (Fritsch and Butland
 * 1984, "A method for constructing local monotone piecewise cubic interpolants", SIAM J. Sci. Stat. Comput. 5(2)):
 * interior tangents are the weighted harmonic mean of the neighbouring secants (0 where the data turns), end tangents
 * the end secants. Every tangent then lies in Fritsch-Carlson's monotonicity square (`m / secant` in [0, 3]), so the
 * curve is monotone wherever the data is, never overshoots a point and is C1. Flat outside the first and last point.
 */
class MonotoneSpline(private val xs: DoubleArray, private val ys: DoubleArray) {
    private val ms: DoubleArray

    init {
        require(xs.size == ys.size && xs.size >= 2) { "at least two points" }
        require((1 until xs.size).all { xs[it] > xs[it - 1] }) { "x must increase" }
        val n = xs.size
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        ms = DoubleArray(n) { k ->
            when (k) {
                0 -> d[0]
                n - 1 -> d[n - 2]
                else -> butland(xs[k] - xs[k - 1], xs[k + 1] - xs[k], d[k - 1], d[k])
            }
        }
    }

    /** The curve at [x]. */
    @Suppress("ReturnCount")
    fun eval(x: Double): Double {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var k = 0
        while (k < n - 2 && x >= xs[k + 1]) k++
        val h = xs[k + 1] - xs[k]
        return hermite((x - xs[k]) / h, ys[k], ys[k + 1], ms[k] * h, ms[k + 1] * h)
    }

    companion object {
        /** Fritsch-Butland tangent from the secants [d0], [d1] of intervals [h0], [h1]; 0 at a turn. */
        fun butland(h0: Double, h1: Double, d0: Double, d1: Double): Double =
            if (d0 * d1 <= 0.0) 0.0 else 3.0 * (h0 + h1) / ((2.0 * h1 + h0) / d0 + (h1 + 2.0 * h0) / d1)

        /** Cubic Hermite on the unit interval: values [y0], [y1], tangents (already times the interval) [t0], [t1]. */
        fun hermite(t: Double, y0: Double, y1: Double, t0: Double, t1: Double): Double {
            val t2 = t * t
            val t3 = t2 * t
            return (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * t0 + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * t1
        }

        /** The spline of a point curve; `null` for the straight line. */
        fun of(points: List<CurvePoint>): MonotoneSpline? = if (Curves.isLinear(points)) {
            null
        } else {
            MonotoneSpline(
                DoubleArray(points.size) { points[it].x.toDouble() },
                DoubleArray(points.size) { points[it].y.toDouble() },
            )
        }
    }
}

/**
 * The parametric curve on the encoded range 0..1: four regions separated by the splits `s1 < s2 < s3` (each at least
 * [MIN_GAP] apart and from the ends). Each region slider moves one knot at the middle of its region,
 * `c = s1/2, (s1+s2)/2, (s2+s3)/2, (s3+1)/2` (shadows, darks, lights, highlights), vertically by
 * `d = a * AMOUNT * gap`, with `a` in -1..1 and `gap` the distance to the neighbouring knot in the direction of the
 * move (the right one when lifting, the left one when lowering; the ends 0 and 1 stay fixed). Any two neighbours then
 * stay at least `(1 - 2 AMOUNT) gap` apart vertically, so the knots increase for every slider combination and the
 * [MonotoneSpline] through `(0,0), knots, (1,1)` is strictly increasing: black stays black, white stays white, no
 * slider can fold the curve.
 */
object ParametricCurveShape {
    /** Fraction of the gap a region knot moves at ±100. */
    const val AMOUNT: Double = 0.45

    /** Smallest distance between splits, and between a split and 0 or 1. */
    const val MIN_GAP: Double = 0.05

    private const val PERCENT = 100.0

    /** The splits of [p] in 0..1, ordered and [MIN_GAP] apart. */
    fun splits(p: ParametricCurve): DoubleArray {
        val s1 = (p.splitShadows / PERCENT).coerceIn(MIN_GAP, 1 - 3 * MIN_GAP)
        val s2 = (p.splitMidtones / PERCENT).coerceIn(s1 + MIN_GAP, max(s1 + MIN_GAP, 1 - 2 * MIN_GAP))
        val s3 = (p.splitHighlights / PERCENT).coerceIn(s2 + MIN_GAP, max(s2 + MIN_GAP, 1 - MIN_GAP))
        return doubleArrayOf(s1, s2, s3)
    }

    /** The spline of [p]; `null` when every region slider is 0 (identity). */
    fun spline(p: ParametricCurve): MonotoneSpline? {
        if (p.isNeutral) return null
        val (s1, s2, s3) = splits(p).let { Triple(it[0], it[1], it[2]) }
        val xs = doubleArrayOf(0.0, s1 / 2, (s1 + s2) / 2, (s2 + s3) / 2, (s3 + 1) / 2, 1.0)
        val amounts = doubleArrayOf(
            0.0,
            p.shadows / PERCENT,
            p.darks / PERCENT,
            p.lights / PERCENT,
            p.highlights / PERCENT,
            0.0,
        ).map { it.coerceIn(-1.0, 1.0) }
        val ys = DoubleArray(xs.size) { k ->
            val a = amounts[k]
            val gap = when {
                a > 0 -> xs[k + 1] - xs[k]
                a < 0 -> xs[k] - xs[k - 1]
                else -> 0.0
            }
            xs[k] + a * AMOUNT * gap
        }
        return MonotoneSpline(xs, ys)
    }
}

/**
 * The tone curve of one recipe baked into lookup tables (computed in double, rounded once to float; the GPU gets the
 * same floats). [table]: per channel, the composition `channel curve ∘ RGB point curve ∘ parametric curve` on the
 * encoded axes, sampled at `e = i/(SIZE-1)` (the curve as the editor draws it; also the slope-1 extension outside
 * 0..1). [linear]: the whole operator `decode(curve(encode(v)))` for linear `v = u²`, sampled at `u = i/(SIZE-1)` — the
 * operator interpolates this one in `u = sqrt(v)`, so the per-pixel work in 0..1 is a square root and a lerp (no
 * transfer-function `pow`; the square-root spacing puts half the samples below linear 0.25, where the encoded curve
 * changes fastest). With 512 entries the operator is within 1e-3 (encoded) of the exact composition for every curve the
 * editor can draw (`CurvesTest`).
 *
 * @property table `SIZE * 3` floats, entry `i` channel `c` at `3 i + c` (encoded in, encoded out).
 * @property linear `SIZE * 3` floats, same layout (linear out at `v = (i/(SIZE-1))²`).
 */
class CurveLut(val table: FloatArray, val linear: FloatArray) {
    init {
        require(table.size == SIZE * 3 && linear.size == SIZE * 3) { "one rgb entry per sample" }
    }

    /** Channel [c] of the composite curve at the encoded value [e] (any real: slope-1 extension outside 0..1). */
    @Suppress("ReturnCount")
    fun eval(e: Float, c: Int): Float {
        if (e < 0f) return table[c] + e
        if (e > 1f) return table[(SIZE - 1) * 3 + c] + (e - 1f)
        val t = e * (SIZE - 1).toFloat()
        val i = min(floor(t).toInt(), SIZE - 2)
        val f = t - i.toFloat()
        val y0 = table[i * 3 + c]
        val y1 = table[(i + 1) * 3 + c]
        return y0 + f * (y1 - y0)
    }

    /** The operator on channel [c] of the linear value [v]: [linear] in `sqrt(v)` on 0..1, the encoded path outside. */
    fun apply(v: Float, c: Int): Float {
        if (v < 0f || v > 1f) return CurveOp.decode(eval(CurveOp.encode(v), c))
        val t = sqrt(v) * (SIZE - 1).toFloat()
        val i = min(floor(t).toInt(), SIZE - 2)
        val f = t - i.toFloat()
        val y0 = linear[i * 3 + c]
        val y1 = linear[(i + 1) * 3 + c]
        return y0 + f * (y1 - y0)
    }

    override fun equals(other: Any?): Boolean = other is CurveLut && table.contentEquals(other.table)

    override fun hashCode(): Int = table.contentHashCode()

    companion object {
        /** Entries per channel. */
        const val SIZE: Int = 512

        /** The tables of [curves]; `null` when every curve is the identity (the operator is skipped). */
        fun of(curves: Curves): CurveLut? {
            if (curves.isNeutral) return null
            val parametric = ParametricCurveShape.spline(curves.parametric)
            val rgb = MonotoneSpline.of(curves.rgb)
            val channels = listOf(curves.red, curves.green, curves.blue).map { MonotoneSpline.of(it) }
            fun curve(e: Double, c: Int): Double {
                val p = parametric?.eval(e) ?: e
                val r = rgb?.eval(p) ?: p
                return channels[c]?.eval(r) ?: r
            }
            val table = FloatArray(SIZE * 3)
            val linear = FloatArray(SIZE * 3)
            for (i in 0 until SIZE) {
                val e = i.toDouble() / (SIZE - 1)
                val u = i.toDouble() / (SIZE - 1)
                val encoded = encode(u * u)
                for (c in 0 until 3) {
                    table[i * 3 + c] = curve(e, c).toFloat()
                    linear[i * 3 + c] = decode(curve(encoded, c)).toFloat()
                }
            }
            return CurveLut(table, linear)
        }

        /** sRGB OETF in double (table building). */
        private fun encode(v: Double): Double =
            if (v <= 0.0031308) 12.92 * v else 1.055 * StrictMath.pow(v, 1.0 / 2.4) - 0.055

        /** sRGB EOTF in double (table building). */
        private fun decode(e: Double): Double =
            if (e <= 0.04045) e / 12.92 else StrictMath.pow((e + 0.055) / 1.055, 2.4)
    }
}

/**
 * Slot 8 (after contrast/whites/blacks): the tone curve, per channel on display-referred linear Rec.2020 values
 * with axes encoded by the sRGB transfer function (so the curve's axes match the histogram behind it for greys):
 *
 * ```
 * v in 0..1:  v' = lerp of CurveLut.linear at u = sqrt(v)          (= decode(curve(encode(v))) sampled in sqrt(v))
 * otherwise:  e = sign(v) srgbOetf(|v|);  y = curve_c(e) with slope 1 outside 0..1;  v' = sign(y) srgbEotf(|y|)
 * ```
 *
 * Per channel, like the point curves of every raw converter: an S-curve adds saturation along with contrast and the
 * R/G/B curves shift colour balance by tone. Negative (out-of-gamut) and above-white values pass through the
 * slope-1 extension, so the operator is continuous and monotone everywhere.
 */
object CurveOp {
    /** sRGB OETF (IEC 61966-2-1), odd-extended to negative values. */
    fun encode(v: Float): Float {
        val a = abs(v)
        val e = if (a <= 0.0031308f) 12.92f * a else 1.055f * StrictMath.pow(a.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        return if (v < 0f) -e else e
    }

    /** sRGB EOTF, odd-extended. */
    fun decode(e: Float): Float {
        val a = abs(e)
        val v = if (a <= 0.04045f) a / 12.92f else StrictMath.pow(((a + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return if (e < 0f) -v else v
    }

    /** Applies [lut] to every pixel of [rgb] in place. */
    fun apply(rgb: FloatArray, lut: CurveLut) {
        var i = 0
        while (i < rgb.size) {
            for (c in 0 until 3) rgb[i + c] = lut.apply(rgb[i + c], c)
            i += 3
        }
    }
}
