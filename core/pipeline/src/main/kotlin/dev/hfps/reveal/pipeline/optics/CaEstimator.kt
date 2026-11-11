@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.optics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The automatic lateral chromatic aberration estimate (docs/adr/0015, "Chromatic aberration") for photos whose
 * profile has no CA data: red and blue are modelled as copies of green scaled about the centre by `m_c` (what the lens
 * does to them). On edges that run across the radius, the radial profiles of green and of the channel are compared;
 * the shift `s` (pixels, outward positive) that best aligns them at radius `r` gives `m = 1 + s / r`, and the
 * gradient-weighted median over the image is the estimate. Returned as the recipe's `caRed` / `caBlue`
 * (`(m - 1) * 1e4`); the correction reads channel `c` at `m_c` times the radius.
 */
object CaEstimator {
    private const val STEP = 3
    private const val INNER = 0.2
    private const val RADIAL_COS = 0.9
    private const val HALF_PROFILE = 4.0
    private const val PROFILE_STEP = 0.5
    private const val MAX_SHIFT = 2.0
    private const val SHIFT_STEP = 0.05
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
        val ch = Array(3) { c -> FloatArray(width * height) { i -> sqrt(max(0f, rgb[i * 3 + c])) } }
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

    private fun scale(c: FloatArray, g: FloatArray, points: List<DoubleArray>, width: Int, height: Int): Double? {
        val ratios = ArrayList<Pair<Double, Double>>()
        val n = (2 * HALF_PROFILE / PROFILE_STEP).toInt() + 1
        val gp = DoubleArray(n)
        val cp = DoubleArray(n)
        for (p in points) {
            val (x, y, ux, uy) = p
            val r = p[4]
            for (k in 0 until n) {
                val t = -HALF_PROFILE + k * PROFILE_STEP
                gp[k] = sample(g, width, height, x + t * ux, y + t * uy)
            }
            if (!normalise(gp)) continue
            var bestS = 0.0
            var bestE = Double.MAX_VALUE
            val errors = ArrayList<Double>()
            var s = -MAX_SHIFT
            while (s <= MAX_SHIFT + 1e-9) {
                for (k in 0 until n) {
                    val t = -HALF_PROFILE + k * PROFILE_STEP + s
                    cp[k] = sample(c, width, height, x + t * ux, y + t * uy)
                }
                val e = if (normalise(cp)) (0 until n).sumOf { (cp[it] - gp[it]).let { d -> d * d } } else Double.MAX_VALUE
                errors += e
                if (e < bestE) {
                    bestE = e
                    bestS = s
                }
                s += SHIFT_STEP
            }
            val i = errors.indexOf(bestE)
            if (i == 0 || i == errors.lastIndex || bestE > n * 0.2) continue
            val a = errors[i - 1]
            val b = errors[i + 1]
            val den = a - 2 * bestE + b
            val refined = if (den > 0) bestS + 0.5 * (a - b) / den * SHIFT_STEP else bestS
            // The channel matches green's profile at t + s: its content sits s further out than green's.
            ratios += 1 + refined / r to p[5]
        }
        if (ratios.size < MIN_POINTS / 2) return null
        val sorted = ratios.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        var acc = 0.0
        for ((v, w) in sorted) {
            acc += w
            if (acc >= total / 2) return v
        }
        return sorted.last().first
    }

    private fun normalise(v: DoubleArray): Boolean {
        val mean = v.average()
        var sd = 0.0
        for (x in v) sd += (x - mean) * (x - mean)
        sd = sqrt(sd / v.size)
        if (sd < 1e-4) return false
        for (i in v.indices) v[i] = (v[i] - mean) / sd
        return true
    }

    private fun sample(img: FloatArray, width: Int, height: Int, x: Double, y: Double): Double {
        val sx = (x - 0.5).coerceIn(0.0, width - 1.0)
        val sy = (y - 0.5).coerceIn(0.0, height - 1.0)
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = sx - x0
        val fy = sy - y0
        val top = img[y0 * width + x0] * (1 - fx) + img[y0 * width + x1] * fx
        val bottom = img[y1 * width + x0] * (1 - fx) + img[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }
}
