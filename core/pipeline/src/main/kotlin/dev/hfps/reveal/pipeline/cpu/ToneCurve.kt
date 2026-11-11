@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The profile's tone curve (docs/adr/0008): a monotone cubic Hermite spline over [KNOTS] knots spaced one stop apart
 * in `log2 x`, from `2^`[FIRST_STOP] to `2^`[LAST_STOP] (0.001 to 32 in scene-linear units after the profile gain).
 * [y] are the display-linear values at the knots and [m] the tangents `dy/dlog2(x)` there.
 *
 * ```
 * f(x) = max(x, 0) * y[0] / x0                          x <= x0 = 2^FIRST_STOP (linear toe; negatives -> 0)
 * f(x) = H(y[i], m[i], y[i+1], m[i+1], t - i)           t = log2(min(x, x_last)) - FIRST_STOP, i = min(floor t, 14)
 * H(y0, m0, y1, m1, f) = (2f³-3f²+1) y0 + (f³-2f²+f) m0 + (-2f³+3f²) y1 + (f³-f²) m1
 * ```
 *
 * Above the last knot the curve holds `y[15]` (its tangent is 0, so it is C1 there). The first tangent is
 * `y[0] ln 2`, the slope of the linear toe in log units, so the curve is C1 at `x0` too. Monotone when every
 * `y[i+1] >= y[i]` and the tangents satisfy Fritsch-Carlson's condition (`m >= 0`, `a² + b² <= 9` per interval),
 * which [limited] enforces. The same formula is in `gl/ColourShaders.kt`.
 *
 * Negative inputs (sensor noise below black, colours outside the curve space's gamut) map to 0, as a camera clips to
 * its output gamut. So every channel leaves the profile non-negative (the post matrices map non-negative to
 * non-negative: identity, or sRGB primaries into Rec.2020), and no later operator sees a colour with negative
 * luminance (an unstable input for luminance-driven tone operators, ADR 0007).
 */
class ToneCurve(val y: FloatArray, val m: FloatArray) {
    init {
        require(y.size == KNOTS && m.size == KNOTS) { "a tone curve has $KNOTS knots" }
    }

    /** Slope of the linear toe below `x0`. */
    val toeSlope: Float get() = y[0] / X0

    /** The curve at [x]. */
    fun eval(x: Float): Float {
        if (x <= X0) return max(x, 0f) * toeSlope
        val t = log2(min(x, X_LAST)) - FIRST_STOP
        val i = min(floor(t).toInt(), KNOTS - 2)
        val f = t - i
        val f2 = f * f
        val f3 = f2 * f
        return (2f * f3 - 3f * f2 + 1f) * y[i] + (f3 - 2f * f2 + f) * m[i] +
            (-2f * f3 + 3f * f2) * y[i + 1] + (f3 - f2) * m[i + 1]
    }

    override fun equals(other: Any?): Boolean =
        other is ToneCurve && y.contentEquals(other.y) && m.contentEquals(other.m)

    override fun hashCode(): Int = y.contentHashCode() * HASH + m.contentHashCode()

    companion object {
        /** Number of knots. */
        const val KNOTS: Int = 16

        /** `log2` of the first knot. */
        const val FIRST_STOP: Float = -10f

        /** `log2` of the last knot. */
        const val LAST_STOP: Float = FIRST_STOP + KNOTS - 1

        /** First knot: below it the curve is linear through the origin. */
        val X0: Float = StrictMath.pow(2.0, FIRST_STOP.toDouble()).toFloat()

        /** Last knot: above it the curve is flat. */
        val X_LAST: Float = StrictMath.pow(2.0, LAST_STOP.toDouble()).toFloat()

        private const val HASH = 31
        private const val LN2 = 0.6931471805599453
        private const val INV_LN2 = 1.0 / LN2
        private const val FC_LIMIT = 3.0
        private const val TINY = 1e-12

        /** `log2` used by the CPU reference (the shader uses GLSL `log2`). */
        fun log2(x: Float): Float = (StrictMath.log(x.toDouble()) * INV_LN2).toFloat()

        /** Knot positions `2^(FIRST_STOP + i)`. */
        fun knotX(i: Int): Double = StrictMath.pow(2.0, FIRST_STOP.toDouble() + i)

        /**
         * Samples a smooth increasing function given as `F(s)`, `s = log2 x`, returning `log2 f(x)`-free values:
         * [value] is `f` at `2^s` and [slope] its derivative `df/dlog2 x`. Used for the designed (parametric) looks:
         * the tangents are the function's own, so the spline interpolates it to within float precision of its shape.
         */
        fun sample(value: (Double) -> Double, slope: (Double) -> Double): ToneCurve {
            val ys = DoubleArray(KNOTS) { value(FIRST_STOP.toDouble() + it) }
            val ms = DoubleArray(KNOTS) { slope(FIRST_STOP.toDouble() + it) }
            ms[0] = ys[0] * LN2
            ms[KNOTS - 1] = 0.0
            return limited(ys, ms)
        }

        /** Fritsch-Carlson tangents for knot values [ys] (harmonic mean of the adjacent secants), then [limited]. */
        fun monotone(ys: DoubleArray): ToneCurve {
            val ms = DoubleArray(KNOTS)
            for (i in 1 until KNOTS - 1) {
                val a = ys[i] - ys[i - 1]
                val b = ys[i + 1] - ys[i]
                ms[i] = if (a > 0 && b > 0) 2.0 / (1.0 / a + 1.0 / b) else 0.0
            }
            ms[0] = ys[0] * LN2
            return limited(ys, ms)
        }

        /** `a + t (b - a)` knot by knot (t may exceed 1: amounts above 100 %), then made monotone by [limited]. */
        fun lerp(a: ToneCurve, b: ToneCurve, t: Double): ToneCurve {
            val ys = DoubleArray(KNOTS) { a.y[it] + t * (b.y[it] - a.y[it]) }
            val ms = DoubleArray(KNOTS) { a.m[it] + t * (b.m[it] - a.m[it]) }
            return limited(ys, ms)
        }

        /**
         * Makes the spline monotone and bounded: values clamped to `[0, 1)` and non-decreasing, tangents
         * non-negative, the first tangent tied to the toe (C1 at `x0`), the last 0, and each interval's tangents
         * scaled into Fritsch-Carlson's circle `a² + b² <= 9` (a, b = tangent / secant).
         */
        fun limited(ys: DoubleArray, ms: DoubleArray): ToneCurve {
            val y = DoubleArray(KNOTS)
            var previous = 0.0
            for (i in 0 until KNOTS) {
                previous = max(previous, ys[i].coerceIn(0.0, MAX_Y))
                y[i] = previous
            }
            val m = DoubleArray(KNOTS) { max(ms[it], 0.0) }
            m[0] = y[0] * LN2
            m[KNOTS - 1] = 0.0
            for (i in 0 until KNOTS - 1) {
                val secant = y[i + 1] - y[i]
                if (secant <= TINY) {
                    m[i] = if (i == 0) m[i] else 0.0
                    m[i + 1] = 0.0
                    continue
                }
                val a = m[i] / secant
                val b = m[i + 1] / secant
                val r = a * a + b * b
                if (r > FC_LIMIT * FC_LIMIT) {
                    val s = FC_LIMIT / sqrt(r)
                    if (i > 0) m[i] = s * a * secant
                    m[i + 1] = s * b * secant
                }
            }
            // The first tangent is pinned to the toe; if it breaks the circle, the toe knot decides (it is tiny).
            return ToneCurve(FloatArray(KNOTS) { y[it].toFloat() }, FloatArray(KNOTS) { m[it].toFloat() })
        }

        private const val MAX_Y = 0.99999
    }
}
