@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml.face

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A closed polygon with a soft edge (docs/adr/0021 "M4/C", face parts): coverage 1 inside, 0 outside, a linear ramp
 * [feather] pixels wide centred on the outline (0.5 on it). Coordinates are continuous image pixels; a pixel is
 * evaluated at its centre. Pure maths, JVM-tested.
 */
class SoftPolygon(val xs: FloatArray, val ys: FloatArray, val feather: Float) {
    init {
        require(xs.size == ys.size && xs.size >= 3) { "a polygon needs at least 3 points" }
        require(feather > 0f) { "feather must be positive" }
    }

    /** The pixel box (inclusive) where coverage can be above zero. */
    val left: Int = floor(xs.min() - feather).toInt()
    val top: Int = floor(ys.min() - feather).toInt()
    val right: Int = ceil(xs.max() + feather).toInt()
    val bottom: Int = ceil(ys.max() + feather).toInt()

    private fun inBox(px: Float, py: Float): Boolean = px >= left && px <= right + 1 && py in top.toFloat()..bottom + 1f

    /** Coverage at the point ([px], [py]). */
    fun at(px: Float, py: Float): Float {
        if (!inBox(px, py)) return 0f
        var inside = false
        var best = Float.MAX_VALUE
        var j = xs.size - 1
        for (i in xs.indices) {
            val xi = xs[i]
            val yi = ys[i]
            val xj = xs[j]
            val yj = ys[j]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            best = min(best, segmentDistance2(px, py, xj, yj, xi, yi))
            j = i
        }
        val d = sqrt(best)
        val signed = if (inside) d else -d
        return (0.5f + signed / feather).coerceIn(0f, 1f)
    }

    companion object {
        /** Squared distance from (px, py) to the segment (ax, ay)-(bx, by). */
        @Suppress("LongParameterList")
        fun segmentDistance2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0f) (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
            val ex = ax + t * dx - px
            val ey = ay + t * dy - py
            return ex * ex + ey * ey
        }

        /**
         * A closed Catmull-Rom spline through the points ([xs], [ys]), [steps] points per segment: the landmark
         * contours are 10-20 points, which would draw visible corners on a 100 % view.
         */
        fun smooth(xs: FloatArray, ys: FloatArray, steps: Int = SMOOTH_STEPS): Pair<FloatArray, FloatArray> {
            val n = xs.size
            val ox = FloatArray(n * steps)
            val oy = FloatArray(n * steps)
            for (i in 0 until n) {
                val i0 = (i - 1 + n) % n
                val i2 = (i + 1) % n
                val i3 = (i + 2) % n
                for (k in 0 until steps) {
                    val t = k.toFloat() / steps
                    ox[i * steps + k] = catmullRom(xs[i0], xs[i], xs[i2], xs[i3], t)
                    oy[i * steps + k] = catmullRom(ys[i0], ys[i], ys[i2], ys[i3], t)
                }
            }
            return ox to oy
        }

        private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5f *
                (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3)
        }

        /** Polygon points per landmark segment after smoothing. */
        const val SMOOTH_STEPS = 4

        /** The union box of [polygons], clipped to a [w] x [h] image: (left, top, right, bottom), right/bottom excl. */
        fun box(polygons: List<SoftPolygon>, w: Int, h: Int): IntArray = intArrayOf(
            max(0, polygons.minOf { it.left }),
            max(0, polygons.minOf { it.top }),
            min(w, polygons.maxOf { it.right } + 1),
            min(h, polygons.maxOf { it.bottom } + 1),
        )
    }
}
