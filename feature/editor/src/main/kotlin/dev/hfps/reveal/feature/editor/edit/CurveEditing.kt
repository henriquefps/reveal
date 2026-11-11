package dev.hfps.reveal.feature.editor.edit

import dev.hfps.reveal.pipeline.cpu.MonotoneSpline
import dev.hfps.reveal.pipeline.cpu.ParametricCurveShape
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The point curve editor's maths (pure, JVM-tested): points in the curve's unit square, kept in x order with a minimum
 * gap; the two end points can move (black and white points) but never be removed. The editor shows the empty list
 * (straight line) as its two corners.
 */
object CurveEditing {
    /** Smallest x distance between two points (2.5 of 255 levels). */
    const val MIN_GAP: Float = 0.01f

    /** Movement of one accessibility nudge (one 8-bit level). */
    const val NUDGE: Float = 1f / 255f

    /** The points the editor shows for [stored] (the corners for a straight line). */
    fun shown(stored: List<CurvePoint>): List<CurvePoint> = stored.ifEmpty { Curves.LINEAR }

    /** The curve through [points] at [x] (the identity for a straight line). */
    fun valueAt(points: List<CurvePoint>, x: Float): Float =
        MonotoneSpline.of(points)?.eval(x.toDouble())?.toFloat() ?: x

    /** The parametric curve at [x]. */
    fun parametricAt(p: ParametricCurve, x: Float): Float =
        ParametricCurveShape.spline(p)?.eval(x.toDouble())?.toFloat() ?: x

    /**
     * The point of [points] nearest to ([x], [y]) within [radiusX] / [radiusY] (an ellipse in curve units, so the grab
     * radius is round on screen), or `null`.
     */
    fun nearest(points: List<CurvePoint>, x: Float, y: Float, radiusX: Float, radiusY: Float): Int? {
        var best: Int? = null
        var bestD = 1f
        points.forEachIndexed { i, p ->
            val d = hypot((p.x - x) / radiusX, (p.y - y) / radiusY)
            if (d <= bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /**
     * [points] with a new point at [x] on the curve (so adding a point changes nothing yet) and its index, or `null`
     * when the curve is full or [x] is too close to an existing point.
     */
    @Suppress("ReturnCount")
    fun add(points: List<CurvePoint>, x: Float): Pair<List<CurvePoint>, Int>? {
        if (points.size >= Curves.MAX_POINTS) return null
        val cx = x.coerceIn(0f, 1f)
        if (points.any { abs(it.x - cx) < MIN_GAP }) return null
        val index = points.indexOfFirst { it.x > cx }.let { if (it < 0) points.size else it }
        if (index == 0 || index == points.size) return null
        val y = valueAt(points, cx).coerceIn(0f, 1f)
        return points.toMutableList().apply { add(index, CurvePoint(cx, y)) } to index
    }

    /** [points] with point [index] moved to ([x], [y]), kept between its neighbours and inside the square. */
    fun move(points: List<CurvePoint>, index: Int, x: Float, y: Float): List<CurvePoint> {
        val last = points.lastIndex
        val lo = if (index == 0) 0f else points[index - 1].x + MIN_GAP
        val hi = if (index == last) 1f else points[index + 1].x - MIN_GAP
        val p = CurvePoint(x.coerceIn(lo, maxOf(lo, hi)), y.coerceIn(0f, 1f))
        return points.toMutableList().apply { set(index, p) }
    }

    /** True for an interior point (the end points cannot be removed). */
    fun removable(points: List<CurvePoint>, index: Int): Boolean = index in 1 until points.lastIndex

    /** [points] without the interior point [index] (unchanged for an end point). */
    fun remove(points: List<CurvePoint>, index: Int): List<CurvePoint> =
        if (removable(points, index)) points.filterIndexed { i, _ -> i != index } else points

    /** True when a dragged point at ([x], [y]) has left the square by more than [margin] (drag off = remove). */
    fun draggedOff(x: Float, y: Float, margin: Float): Boolean =
        x < -margin || x > 1f + margin || y < -margin || y > 1f + margin

    /** Where an "add point" accessibility action puts a point: the middle of the widest gap. */
    fun widestGapMiddle(points: List<CurvePoint>): Float =
        points.zipWithNext().maxBy { (a, b) -> b.x - a.x }.let { (a, b) -> (a.x + b.x) / 2f }

    /** A curve coordinate as the 0..255 level the readout and TalkBack say. */
    fun level(v: Float): Int = (v * LEVELS).roundToInt().coerceIn(0, LEVELS.toInt())

    private const val LEVELS = 255f
}
