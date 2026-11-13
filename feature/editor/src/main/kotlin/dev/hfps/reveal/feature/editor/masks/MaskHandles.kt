package dev.hfps.reveal.feature.editor.masks

import androidx.compose.ui.geometry.Offset
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A draggable part of a gradient on the photo. */
enum class HandleId {
    /** The pin: moves the whole shape. */
    CENTRE,

    /** Linear: the line of full effect. */
    START,

    /** Linear: the line of no effect. */
    END,

    /** Linear: the centre line (rotates around the pin). Radial: the knob above the ellipse. */
    ROTATE,

    /** Radial: the horizontal radius (either side). */
    RADIUS_X,

    /** Radial: the vertical radius (either side). */
    RADIUS_Y,

    /** Radial: the inner ellipse where the feather starts. */
    FEATHER,
}

/**
 * The on-photo handles of linear and radial gradients (docs/adr/0022 "M4/B (editor)"), as pure maths in the isotropic
 * plane (`u = x W / L`, `v = y H / L`, the plane the engine evaluates shapes in): a drag maps the finger to that plane
 * through [MaskView], so it works the same whatever the crop, rotation, flips, lens corrections and zoom.
 */
object MaskHandles {
    /** A linear gradient's lines in the isotropic plane: start (full effect), centre and end, and the axis. */
    data class LinearLines(
        val start: Offset,
        val centre: Offset,
        val end: Offset,
        val axis: Offset,
        val length: Float,
    ) {
        /** The unit normal of the lines (the lines run along it). */
        val normal: Offset get() = Offset(-axis.y, axis.x)
    }

    fun linearLines(c: MaskComponent.Linear, view: MaskView): LinearLines {
        val p0 = view.toIso(Offset(c.x0, c.y0))
        val p1 = view.toIso(Offset(c.x1, c.y1))
        val d = p1 - p0
        val len = d.getDistance()
        val axis = if (len > EPS) d / len else Offset(0f, 1f)
        val f = (c.feather / FULL).coerceIn(0f, 1f)
        return LinearLines(
            start = p0 + axis * (len * (HALF - f / 2f)),
            centre = p0 + axis * (len * HALF),
            end = p0 + axis * (len * (HALF + f / 2f)),
            axis = axis,
            length = len,
        )
    }

    /**
     * [c] after dragging [handle] from isotropic point [from] to [to]: the pin moves the gradient, a start or end line
     * moves along the axis (the other line stays; the feather keeps its share), the centre line turns the gradient
     * around the pin.
     */
    fun dragLinear(
        c: MaskComponent.Linear,
        handle: HandleId,
        from: Offset,
        to: Offset,
        view: MaskView,
    ): MaskComponent.Linear {
        val lines = linearLines(c, view)
        val p0 = view.toIso(Offset(c.x0, c.y0))
        val p1 = view.toIso(Offset(c.x1, c.y1))
        return when (handle) {
            HandleId.CENTRE -> withIso(c, p0 + (to - from), p1 + (to - from), view)

            HandleId.ROTATE -> {
                val angle = angle(to - lines.centre) - angle(from - lines.centre)
                withIso(c, rotate(p0, lines.centre, angle), rotate(p1, lines.centre, angle), view)
            }

            HandleId.START, HandleId.END -> {
                val f = (c.feather / FULL).coerceIn(0f, 1f)
                // Positions along the axis, measured from p0.
                var s = dot(lines.start - p0, lines.axis)
                var e = dot(lines.end - p0, lines.axis)
                val at = dot(to - p0, lines.axis)
                if (handle == HandleId.START) s = minOf(at, e - MIN_GAP) else e = max(at, s + MIN_GAP)
                if (f <= EPS) {
                    // A hard edge has one line: it moves the centre.
                    val shift = (if (handle == HandleId.START) s else e) - dot(lines.centre - p0, lines.axis)
                    withIso(c, p0 + lines.axis * shift, p1 + lines.axis * shift, view)
                } else {
                    val len = (e - s) / f
                    val mid = (s + e) / 2f
                    val n0 = p0 + lines.axis * (mid - len / 2f)
                    withIso(c, n0, n0 + lines.axis * len, view)
                }
            }

            else -> c
        }
    }

    /** A radial gradient's frame: centre (isotropic), radii, rotation in radians (clockwise, y down). */
    data class RadialFrame(val centre: Offset, val rx: Float, val ry: Float, val theta: Float) {
        /** Local (ellipse axes) -> isotropic. */
        fun toIso(lx: Float, ly: Float): Offset {
            val c = cos(theta)
            val s = sin(theta)
            return Offset(centre.x + c * lx - s * ly, centre.y + s * lx + c * ly)
        }

        /** Isotropic -> local. */
        fun toLocal(q: Offset): Offset {
            val c = cos(theta)
            val s = sin(theta)
            val dx = q.x - centre.x
            val dy = q.y - centre.y
            return Offset(c * dx + s * dy, -s * dx + c * dy)
        }
    }

    fun radialFrame(c: MaskComponent.Radial, view: MaskView): RadialFrame =
        RadialFrame(view.toIso(Offset(c.cx, c.cy)), c.rx, c.ry, (c.rotation * PI / HALF_TURN).toFloat())

    /** The isotropic outline of the ellipse scaled by [scale] (1 = the edge, `1 - feather` = where it starts). */
    fun radialOutline(frame: RadialFrame, scale: Float = 1f, points: Int = OUTLINE_POINTS): List<Offset> =
        List(points + 1) { i ->
            val t = (2 * PI * i / points).toFloat()
            frame.toIso(frame.rx * scale * cos(t), frame.ry * scale * sin(t))
        }

    /** Where the radial handles are (isotropic), the rotation knob [knob] isotropic units above the top edge. */
    fun radialHandles(c: MaskComponent.Radial, view: MaskView, knob: Float): Map<HandleId, List<Offset>> {
        val f = radialFrame(c, view)
        val inner = (1f - c.feather / FULL).coerceIn(0f, 1f)
        return mapOf(
            HandleId.CENTRE to listOf(f.centre),
            HandleId.RADIUS_X to listOf(f.toIso(f.rx, 0f), f.toIso(-f.rx, 0f)),
            HandleId.RADIUS_Y to listOf(f.toIso(0f, f.ry), f.toIso(0f, -f.ry)),
            HandleId.ROTATE to listOf(f.toIso(0f, -f.ry - knob)),
            HandleId.FEATHER to listOf(f.toIso(f.rx * inner * DIAGONAL, -f.ry * inner * DIAGONAL)),
        )
    }

    /** [c] after dragging [handle] from isotropic point [from] to [to]. Radii stay at least [MIN_RADIUS]. */
    fun dragRadial(
        c: MaskComponent.Radial,
        handle: HandleId,
        from: Offset,
        to: Offset,
        view: MaskView,
    ): MaskComponent.Radial {
        val f = radialFrame(c, view)
        val local = f.toLocal(to)
        return when (handle) {
            HandleId.CENTRE -> {
                val centre = view.fromIso(f.centre + (to - from))
                c.copy(cx = centre.x, cy = centre.y)
            }

            HandleId.RADIUS_X -> c.copy(rx = max(kotlin.math.abs(local.x), MIN_RADIUS))

            HandleId.RADIUS_Y -> c.copy(ry = max(kotlin.math.abs(local.y), MIN_RADIUS))

            HandleId.ROTATE -> {
                val turn = angle(to - f.centre) - angle(from - f.centre)
                c.copy(rotation = normaliseDegrees(c.rotation + Math.toDegrees(turn.toDouble()).toFloat()))
            }

            HandleId.FEATHER -> {
                val d = sqrt((local.x / f.rx) * (local.x / f.rx) + (local.y / f.ry) * (local.y / f.ry))
                c.copy(feather = ((1f - d).coerceIn(0f, 1f) * FULL))
            }

            else -> c
        }
    }

    /** Distance from [p] to the polyline [line]. */
    fun distanceToPolyline(p: Offset, line: List<Offset>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until line.size - 1) best = minOf(best, distanceToSegment(p, line[i], line[i + 1]))
        return best
    }

    fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        if (len2 == 0f) return (p - a).getDistance()
        val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
        return (p - (a + ab * t)).getDistance()
    }

    /** Degrees into -180..180. */
    fun normaliseDegrees(d: Float): Float {
        var v = d % FULL_TURN
        if (v > HALF_TURN) v -= FULL_TURN
        if (v < -HALF_TURN) v += FULL_TURN
        return v
    }

    private fun withIso(c: MaskComponent.Linear, p0: Offset, p1: Offset, view: MaskView): MaskComponent.Linear {
        val a = view.fromIso(p0)
        val b = view.fromIso(p1)
        return c.copy(x0 = a.x, y0 = a.y, x1 = b.x, y1 = b.y)
    }

    private fun angle(v: Offset): Float = atan2(v.y, v.x)

    private fun rotate(p: Offset, around: Offset, angle: Float): Offset {
        val c = cos(angle)
        val s = sin(angle)
        val dx = p.x - around.x
        val dy = p.y - around.y
        return Offset(around.x + c * dx - s * dy, around.y + s * dx + c * dy)
    }

    private fun dot(a: Offset, b: Offset): Float = a.x * b.x + a.y * b.y

    /** The isotropic length of [px] screen pixels (handles keep their size on screen at any zoom). */
    fun isoLength(px: Float, view: MaskView): Float = px / (view.pixelScale * view.longEdge)

    @Suppress("unused")
    private fun length(v: Offset): Float = hypot(v.x, v.y)

    private const val FULL = 100f
    private const val HALF = 0.5f
    private const val EPS = 1e-6f
    private const val MIN_GAP = 0.002f
    const val MIN_RADIUS: Float = 0.005f
    private const val HALF_TURN = 180f
    private const val FULL_TURN = 360f
    private const val DIAGONAL = 0.70710677f
    private const val OUTLINE_POINTS = 72
}
