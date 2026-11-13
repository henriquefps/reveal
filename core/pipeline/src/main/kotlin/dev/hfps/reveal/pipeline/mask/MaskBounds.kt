@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.mask

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where a mask's coverage can be non-zero (docs/adr/0022 "Speed (M4/P)"): a conservative pixel box of a [MaskPlan]
 * slot, so a drag of that mask's adjustments re-renders only there. Outside the box the coverage is exactly 0, so the
 * pixels there do not depend on the mask's adjustments.
 *
 * Per component, the set where its value `v > 0` before inversion: linear = the half-plane `t < e1` (clipped to the
 * image), radial inside = the ellipse's box, brush = the boxes of its painting dabs; colour and luminance ranges, AI
 * layers, outside radials and inverted components = the whole image. Combination from `cov = 0`: add = union,
 * subtract = unchanged (it only lowers coverage), intersect = intersection.
 */
object MaskBounds {
    /**
     * The box `(x0, y0, x1, y1)` (pixels, exclusive ends, within the plan's image) outside which mask slot [slot] of
     * [plan] covers nothing; `x0 >= x1` when it covers nothing at all.
     */
    fun of(plan: MaskPlan, slot: Int): IntArray {
        val w = plan.imageWidth
        val h = plan.imageHeight
        val whole = intArrayOf(0, 0, w, h)
        var box = EMPTY
        for (c in plan.components[slot]) {
            val v = if (c.invert) whole else component(c, plan, w, h) ?: continue
            box = when (c.op) {
                0 -> union(box, v)
                1 -> box
                else -> intersect(box, v)
            }
        }
        return intersect(box, whole).copyOf()
    }

    /** True when [box] (from [of]) is empty. */
    fun isEmpty(box: IntArray): Boolean = box[0] >= box[2] || box[1] >= box[3]

    /** The union of two boxes (an empty one is ignored). */
    fun union(a: IntArray, b: IntArray): IntArray = when {
        isEmpty(a) -> b.copyOf()
        isEmpty(b) -> a.copyOf()
        else -> intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
    }

    private fun intersect(a: IntArray, b: IntArray): IntArray {
        val r = intArrayOf(max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3]))
        return if (isEmpty(r)) EMPTY else r
    }

    /** A component's box where `v > 0`; null when it is skipped (contributes nothing). */
    private fun component(c: MaskPlan.PackedComponent, plan: MaskPlan, w: Int, h: Int): IntArray? {
        val d = c.data
        return when (c.type) {
            MaskPlan.TYPE_LINEAR -> halfPlane(d[0], d[1], d[2], d[3], d[5], w, h)
            MaskPlan.TYPE_RADIAL -> if (d[7] != 0f) ellipse(d) else intArrayOf(0, 0, w, h)
            MaskPlan.TYPE_BRUSH -> brush(plan.brushes[c.layer])
            MaskPlan.TYPE_COLOUR, MaskPlan.TYPE_LUMA, MaskPlan.TYPE_AI -> intArrayOf(0, 0, w, h)
            else -> null
        }
    }

    /**
     * Linear: `v = 1 - sstep(e0, e1, t)` is positive where `t < e1`, `t = (q - p0) . k`: the image rectangle clipped by
     * that half-plane (Sutherland-Hodgman on four corners), its box widened by one pixel.
     */
    @Suppress("LongParameterList")
    private fun halfPlane(px: Float, py: Float, kx: Float, ky: Float, e1: Float, w: Int, h: Int): IntArray {
        val corners = listOf(0.0 to 0.0, w.toDouble() to 0.0, w.toDouble() to h.toDouble(), 0.0 to h.toDouble())
        fun t(p: Pair<Double, Double>) = (p.first - px) * kx + (p.second - py) * ky - e1
        val inside = ArrayList<Pair<Double, Double>>()
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val ta = t(a)
            val tb = t(b)
            if (ta < 0) inside += a
            if ((ta < 0) != (tb < 0)) {
                val f = ta / (ta - tb)
                inside += (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
            }
        }
        if (inside.isEmpty()) return EMPTY
        return pixels(
            inside.minOf { it.first },
            inside.minOf { it.second },
            inside.maxOf { it.first },
            inside.maxOf { it.second },
        )
    }

    /** Radial, effect inside: the rotated ellipse's box (radii from `1 / d4`, `1 / d5`, angle from `d2`, `d3`). */
    private fun ellipse(d: FloatArray): IntArray {
        val rx = 1.0 / d[4]
        val ry = 1.0 / d[5]
        val cs = d[2].toDouble()
        val sn = d[3].toDouble()
        val ex = sqrt(rx * rx * cs * cs + ry * ry * sn * sn)
        val ey = sqrt(rx * rx * sn * sn + ry * ry * cs * cs)
        return pixels(d[0] - ex, d[1] - ey, d[0] + ex, d[1] + ey)
    }

    /** The painting dabs' boxes (an erasing dab never raises coverage). */
    private fun brush(dabs: Dabs): IntArray {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        val box = IntArray(4)
        for (k in 0 until dabs.size) {
            if (dabs.data[k * Dabs.DAB_FLOATS + 5] != 0f) continue
            dabs.box(k, box)
            x0 = min(x0, box[0])
            y0 = min(y0, box[1])
            x1 = max(x1, box[2] + 1)
            y1 = max(y1, box[3] + 1)
        }
        return if (x1 < x0) EMPTY else intArrayOf(x0, y0, x1, y1)
    }

    /** Pixels whose centres a continuous box can contain, one pixel wider on every side (float rounding). */
    private fun pixels(x0: Double, y0: Double, x1: Double, y1: Double): IntArray =
        intArrayOf(floor(x0).toInt() - 1, floor(y0).toInt() - 1, ceil(x1).toInt() + 1, ceil(y1).toInt() + 1)

    private val EMPTY = intArrayOf(0, 0, 0, 0)
}
