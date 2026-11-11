@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A straight segment in pixel coordinates (y down) with its [weight] (summed gradient magnitude along it). */
data class Segment(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val weight: Double = 1.0) {
    val length: Double get() = hypot(x1 - x0, y1 - y0)

    /** Direction in degrees, folded into (-90, 90]: 0 = horizontal, +-90 = vertical. */
    val angle: Double
        get() {
            var a = Math.toDegrees(atan2(y1 - y0, x1 - x0))
            if (a > 90.0) a -= 180.0
            if (a <= -90.0) a += 180.0
            return a
        }
}

/**
 * Straight-line detection for Upright and auto-straighten (docs/adr/0015, "Line detection"): a simplified LSD
 * (Grompone von Gioi et al., "LSD: a Line Segment Detector", IPOL 2012) in pure Kotlin, without the a-contrario
 * validation:
 *
 * 1. Gaussian blur (sigma [SIGMA]) of the luminance (values about 0..1, gamma-encoded), then the 2x2 gradient of LSD;
 *    pixels below [MIN_GRADIENT] are ignored.
 * 2. Pixels in decreasing gradient order seed **line-support regions**: 8-connected neighbours whose level-line angle
 *    (perpendicular to the gradient) is within [TOLERANCE] of the region's mean angle.
 * 3. Each region becomes a segment through its gradient-weighted centroid along its principal axis (the eigenvector of
 *    the weighted inertia matrix); it is kept when it is long ([minLength]), thin (length / width at least
 *    [MIN_ELONGATION]) and dense (at least [MIN_DENSITY] of its rectangle covered).
 *
 * The principal axis of a few hundred edge pixels pins the direction to a few hundredths of a degree on clean edges,
 * which is what the 0.2 degree target of auto-straighten needs.
 */
object LineDetector {
    const val SIGMA: Double = 0.8
    const val MIN_GRADIENT: Double = 5.2 / 255.0
    const val TOLERANCE: Double = PI / 8
    const val MIN_ELONGATION: Double = 6.0
    const val MIN_DENSITY: Double = 0.6
    private const val BINS = 1024

    /**
     * Segments of the [width] x [height] luminance [gray] (row-major), longest-weighted first; [minLength] in pixels
     * (default 4 % of the long edge).
     */
    fun detect(
        gray: FloatArray,
        width: Int,
        height: Int,
        minLength: Double = 0.04 * max(width, height),
    ): List<Segment> {
        require(gray.size == width * height) { "gray must be width * height" }
        if (width < 4 || height < 4) return emptyList()
        val img = blur(gray, width, height)
        val n = width * height
        val mag = FloatArray(n)
        val angle = FloatArray(n)
        var maxMag = 0f
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val i = y * width + x
                val a = img[i]
                val b = img[i + 1]
                val c = img[i + width]
                val d = img[i + width + 1]
                val gx = (b + d - a - c) / 2f
                val gy = (c + d - a - b) / 2f
                val m = sqrt(gx * gx + gy * gy)
                mag[i] = m
                // Level-line angle: perpendicular to the gradient.
                angle[i] = atan2(gx.toDouble(), -gy.toDouble()).toFloat()
                if (m > maxMag) maxMag = m
            }
        }
        if (maxMag <= MIN_GRADIENT) return emptyList()
        // Pseudo-ordering by gradient magnitude (bins), strongest first.
        val bins = Array(BINS) { ArrayList<Int>() }
        for (i in 0 until n) {
            if (mag[i] > MIN_GRADIENT) bins[min(BINS - 1, (mag[i] / maxMag * (BINS - 1)).toInt())].add(i)
        }
        val used = BooleanArray(n)
        val region = ArrayList<Int>()
        val out = ArrayList<Segment>()
        for (b in BINS - 1 downTo 0) {
            for (seed in bins[b]) {
                if (used[seed]) continue
                grow(seed, width, height, mag, angle, used, region)
                segment(region, width, mag, minLength)?.let { out += it }
            }
        }
        return out.sortedByDescending { it.weight }
    }

    private fun grow(
        seed: Int,
        width: Int,
        height: Int,
        mag: FloatArray,
        angle: FloatArray,
        used: BooleanArray,
        region: ArrayList<Int>,
    ) {
        region.clear()
        region += seed
        used[seed] = true
        var sx = cos(angle[seed].toDouble())
        var sy = sin(angle[seed].toDouble())
        var regionAngle = atan2(sy, sx)
        var k = 0
        while (k < region.size) {
            val p = region[k++]
            val px = p % width
            val py = p / width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val qx = px + dx
                    val qy = py + dy
                    if (qx < 0 || qy < 0 || qx >= width - 1 || qy >= height - 1) continue
                    val q = qy * width + qx
                    if (used[q] || mag[q] <= MIN_GRADIENT) continue
                    if (!aligned(angle[q].toDouble(), regionAngle)) continue
                    used[q] = true
                    region += q
                    sx += cos(angle[q].toDouble())
                    sy += sin(angle[q].toDouble())
                    regionAngle = atan2(sy, sx)
                }
            }
        }
    }

    /** Whether two level-line angles agree within [TOLERANCE] (direction matters: a dark-to-light edge has one side). */
    private fun aligned(a: Double, b: Double): Boolean {
        var d = abs(a - b)
        if (d > PI) d = 2 * PI - d
        return d < TOLERANCE
    }

    @Suppress("ReturnCount")
    private fun segment(region: List<Int>, width: Int, mag: FloatArray, minLength: Double): Segment? {
        if (region.size < minLength) return null
        var w = 0.0
        var cx = 0.0
        var cy = 0.0
        for (p in region) {
            val m = mag[p].toDouble()
            w += m
            // The 2x2 gradient sits at the centre of the 2x2 block: pixel corner (x + 1, y + 1).
            cx += m * (p % width + 1)
            cy += m * (p / width + 1)
        }
        cx /= w
        cy /= w
        var ixx = 0.0
        var iyy = 0.0
        var ixy = 0.0
        for (p in region) {
            val m = mag[p].toDouble()
            val dx = p % width + 1 - cx
            val dy = p / width + 1 - cy
            ixx += m * dx * dx
            iyy += m * dy * dy
            ixy += m * dx * dy
        }
        // Principal axis: the eigenvector of the larger eigenvalue.
        val theta = 0.5 * atan2(2 * ixy, ixx - iyy)
        val ux = cos(theta)
        val uy = sin(theta)
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var wlo = Double.MAX_VALUE
        var whi = -Double.MAX_VALUE
        for (p in region) {
            val dx = p % width + 1 - cx
            val dy = p / width + 1 - cy
            val along = dx * ux + dy * uy
            val across = -dx * uy + dy * ux
            lo = min(lo, along)
            hi = max(hi, along)
            wlo = min(wlo, across)
            whi = max(whi, across)
        }
        val length = hi - lo + 1
        val thickness = whi - wlo + 1
        if (length < minLength || length / thickness < MIN_ELONGATION) return null
        if (region.size / (length * thickness) < MIN_DENSITY) return null
        return Segment(cx + lo * ux, cy + lo * uy, cx + hi * ux, cy + hi * uy, w)
    }

    private fun blur(gray: FloatArray, width: Int, height: Int): FloatArray {
        val r = (3 * SIGMA).roundToInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * SIGMA * SIGMA)).toFloat() }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(gray.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (j in -r..r) acc += k[j + r] * gray[y * width + (x + j).coerceIn(0, width - 1)]
                tmp[y * width + x] = acc
            }
        }
        val out = FloatArray(gray.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (j in -r..r) acc += k[j + r] * tmp[(y + j).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = acc
            }
        }
        return out
    }
}
