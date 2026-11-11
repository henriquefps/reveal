@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.optics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The automatic lateral chromatic aberration estimate (docs/adr/0015, "Chromatic aberration") for photos whose
 * profile has no CA data: red and blue are modelled as copies of green scaled about the centre by `m_c` (what the lens
 * does to them). On edges that run across the radius, the radial profiles of green and of the channel are compared;
 * the channel's scale `m` (searched from `1 - 0.004` to `1 + 0.004` in steps of 1e-4, then a parabola) is the one
 * that best aligns them over the whole image. Returned as the recipe's `caRed` / `caBlue`
 * (`(m - 1) * 1e4`); the correction reads channel `c` at `m_c` times the radius.
 */
object CaEstimator {
    private const val STEP = 3
    private const val INNER = 0.2
    private const val RADIAL_COS = 0.9
    private const val HALF_PROFILE = 4.0
    private const val PROFILE_STEP = 0.5
    private const val MAX_FIT_POINTS = 1500
    private const val SEARCH = 40
    private const val MIN_SPREAD = 1e-3
    private const val MAX_POINTS = 4000
    private const val MIN_POINTS = 40
    private const val MIN_GRADIENT = 0.02f
    private const val UNIT = 1e4

    /** Estimated (caRed, caBlue) of [rgb] (`width*height*3`, linear), or `null` with too few usable edges. */
    @Suppress("ReturnCount")
    fun estimate(rgb: FloatArray, width: Int, height: Int): Pair<Float, Float>? {
        require(rgb.size == width * height * 3) { "rgb must be width * height * 3" }
        val cx = width / 2.0
        val cy = height / 2.0
        val half = hypot(cx, cy)
        // Perceptual-ish channels so edges in shadows count too.
        // A light blur (sigma 1 px) first: on very sharp edges sub-pixel shifts would otherwise read through the
        // interpolation's phase bias (towards whole pixels).
        val ch = Array(3) { c -> blur(FloatArray(width * height) { i -> sqrt(max(0f, rgb[i * 3 + c])) }, width, height) }
        val points = ArrayList<DoubleArray>()
        for (y in 2 until height - 2 step STEP) {
            for (x in 2 until width - 2 step STEP) {
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                val r = hypot(dx, dy)
                if (r < INNER * half) continue
                val g = ch[1]
                val gx = (g[y * width + x + 1] - g[y * width + x - 1]) / 2f
                val gy = (g[(y + 1) * width + x] - g[(y - 1) * width + x]) / 2f
                val m = hypot(gx, gy)
                if (m < MIN_GRADIENT) continue
                val cos = abs(gx * dx + gy * dy) / (m * r)
                if (cos < RADIAL_COS) continue
                points += doubleArrayOf(x + 0.5, y + 0.5, dx / r, dy / r, r, m.toDouble())
            }
        }
        if (points.size < MIN_POINTS) return null
        val used = points.sortedByDescending { it[5] }.take(MAX_POINTS)
        val red = scale(ch[0], ch[1], used, width, height) ?: return null
        val blue = scale(ch[2], ch[1], used, width, height) ?: return null
        return ((red - 1) * UNIT).toFloat() to ((blue - 1) * UNIT).toFloat()
    }

    /**
     * The scale `m` of channel [c] relative to [g]: the minimum over `m` of the summed mismatch between each point's
     * radial profile of green and the channel's profile read at the same points scaled by `m` about the centre (each
     * profile normalised to zero mean and unit spread, so colour and exposure differences do not count). A global
     * fit: per-edge shifts of a tenth of a pixel are too small to measure one by one.
     */
    private fun scale(c: FloatArray, g: FloatArray, points: List<DoubleArray>, width: Int, height: Int): Double? {
        val cx = width / 2.0
        val cy = height / 2.0
        val n = (2 * HALF_PROFILE / PROFILE_STEP).toInt() + 1
        val greens = ArrayList<DoubleArray>()
        val used = ArrayList<DoubleArray>()
        for (p in points.take(MAX_FIT_POINTS)) {
            val profile = DoubleArray(n) { k ->
                val t = -HALF_PROFILE + k * PROFILE_STEP
                sample(g, width, height, p[0] + t * p[2], p[1] + t * p[3])
            }
            if (!normalise(profile)) continue
            greens += profile
            used += p
        }
        if (used.size < MIN_POINTS) return null
        val cp = DoubleArray(n)
        fun error(m: Double): Double {
            var e = 0.0
            for ((i, p) in used.withIndex()) {
                for (k in 0 until n) {
                    val t = -HALF_PROFILE + k * PROFILE_STEP
                    val x = p[0] + t * p[2]
                    val y = p[1] + t * p[3]
                    cp[k] = sample(c, width, height, cx + m * (x - cx), cy + m * (y - cy))
                }
                if (!normalise(cp)) continue
                val gp = greens[i]
                for (k in 0 until n) e += (cp[k] - gp[k]).let { it * it }
            }
            return e
        }
        var bestK = 0
        var bestE = Double.MAX_VALUE
        val errors = DoubleArray(2 * SEARCH + 1) { j ->
            error(1 + (j - SEARCH) / UNIT).also {
                if (it < bestE) {
                    bestE = it
                    bestK = j
                }
            }
        }
        if (bestK == 0 || bestK == errors.lastIndex) return null
        val a = errors[bestK - 1]
        val b = errors[bestK + 1]
        val den = a - 2 * bestE + b
        val k = if (den > 0) bestK + 0.5 * (a - b) / den else bestK.toDouble()
        return 1 + (k - SEARCH) / UNIT
    }

    private fun normalise(v: DoubleArray): Boolean {
        val mean = v.average()
        var sd = 0.0
        for (x in v) sd += (x - mean) * (x - mean)
        sd = sqrt(sd / v.size)
        if (sd < MIN_SPREAD) return false
        for (i in v.indices) v[i] = (v[i] - mean) / sd
        return true
    }

    private fun blur(img: FloatArray, width: Int, height: Int): FloatArray {
        val k = FloatArray(2 * BLUR_RADIUS + 1) { i ->
            val d = (i - BLUR_RADIUS).toFloat()
            kotlin.math.exp(-d * d / 2f)
        }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(img.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (j in k.indices) acc += k[j] * img[y * width + (x + j - BLUR_RADIUS).coerceIn(0, width - 1)]
                tmp[y * width + x] = acc
            }
        }
        val out = FloatArray(img.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (j in k.indices) acc += k[j] * tmp[(y + j - BLUR_RADIUS).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = acc
            }
        }
        return out
    }

    private const val BLUR_RADIUS = 3

    /** Catmull-Rom sample at pixel position ([x], [y]) (pixel centres at `i + 0.5`), taps clamped to the image. */
    private fun sample(img: FloatArray, width: Int, height: Int, x: Double, y: Double): Double {
        val sx = x - 0.5
        val sy = y - 0.5
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val wx = weights(sx - x0)
        val wy = weights(sy - y0)
        var acc = 0.0
        for (j in 0 until TAPS) {
            val row = (y0 - 1 + j).coerceIn(0, height - 1) * width
            var r = 0.0
            for (i in 0 until TAPS) r += wx[i] * img[row + (x0 - 1 + i).coerceIn(0, width - 1)]
            acc += wy[j] * r
        }
        return acc
    }

    private fun weights(t: Double): DoubleArray = doubleArrayOf(
        ((-0.5 * t + 1) * t - 0.5) * t,
        (1.5 * t - 2.5) * t * t + 1,
        ((-1.5 * t + 2) * t + 0.5) * t,
        (0.5 * t - 0.5) * t * t,
    )

    private const val TAPS = 4
}
