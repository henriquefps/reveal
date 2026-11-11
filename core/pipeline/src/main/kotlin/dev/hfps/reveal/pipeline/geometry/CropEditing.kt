@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.geometry

import dev.hfps.reveal.pipeline.recipe.Aspect
import dev.hfps.reveal.pipeline.recipe.AspectPreset
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.Transform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A handle of the crop frame: [hx] / [hy] are -1 (left/top), 0 (middle) or +1 (right/bottom). */
enum class CropHandle(val hx: Int, val hy: Int) {
    TOP_LEFT(-1, -1),
    TOP(0, -1),
    TOP_RIGHT(1, -1),
    RIGHT(1, 0),
    BOTTOM_RIGHT(1, 1),
    BOTTOM(0, 1),
    BOTTOM_LEFT(-1, 1),
    LEFT(-1, 0),
    ;

    val corner: Boolean get() = hx != 0 && hy != 0
}

/**
 * The crop mode's editing maths, pure and JVM-tested. The user works in the **editing plane**
 * ([GeometryMapping.editing]): the whole image straightened and oriented, with the crop frame as an axis-aligned
 * [Box] in it. Every function takes the recipe's [Geometry] and the image size and returns a new frame or geometry;
 * the recipe itself stays in image coordinates (docs/adr/0003).
 *
 * A frame is **valid** when its image rectangle lies inside the image (the recipe's normalised crop is in 0..1) and,
 * when [Geometry.constrain] is on, its straightened rectangle does too (no empty corner) and, with a Transform
 * (docs/adr/0015), each of its corners maps back into the lens-corrected image (the transformed image is the
 * projective image of a rectangle: convex, so the corners decide). Gestures move from a valid
 * frame toward the requested one and stop at the last valid position ([limit]).
 */
@Suppress("TooManyFunctions", "ReturnCount")
object CropEditing {
    /** Validity tolerance in pixels: covers the float rounding of the recipe's normalised crop. */
    private const val EPS = 1e-3

    /** Size of the probe frame used to find a valid centre. */
    private const val PROBE = 1e-6
    private const val SEARCH_STEPS = 24
    private const val FULL_EPS = 1e-5
    private const val SQUARE_ANGLE = 90.0
    private const val HALF_RIGHT_ANGLE = 45.0
    private const val STRAIGHT_ANGLE = 180.0

    /** The crop frame of [g] in the editing plane. */
    fun frame(g: Geometry, width: Int, height: Int): Box = GeometryMapping.frame(g, width, height)

    /** Size of the editing plane (the oriented image): `width x height`, swapped for odd quarter turns. */
    fun planeSize(g: Geometry, width: Int, height: Int): Pair<Int, Int> =
        if (g.rotate % 2 == 1) height to width else width to height

    /** [g] with its crop set from [frame] (editing plane); a frame covering the whole image stores no crop. */
    fun withFrame(g: Geometry, frame: Box, width: Int, height: Int): Geometry {
        val r = imageRect(g, frame, width, height)
        val left = (r.left / width).coerceIn(0.0, 1.0)
        val top = (r.top / height).coerceIn(0.0, 1.0)
        val right = (r.right / width).coerceIn(0.0, 1.0)
        val bottom = (r.bottom / height).coerceIn(0.0, 1.0)
        val full = left < FULL_EPS && top < FULL_EPS && right > 1 - FULL_EPS && bottom > 1 - FULL_EPS
        if (full) return g.copy(crop = null)
        val l = left.toFloat()
        val t = top.toFloat()
        val rr = maxOf(right.toFloat(), Math.nextUp(l))
        val b = maxOf(bottom.toFloat(), Math.nextUp(t))
        return g.copy(crop = CropRect(l, t, rr.coerceAtMost(1f), b.coerceAtMost(1f)))
    }

    /** The (un-rotated) image rectangle of [frame]: same centre through the plane mapping, size un-oriented. */
    fun imageRect(g: Geometry, frame: Box, width: Int, height: Int): Box {
        val (planeW, planeH) = planeSize(g, width, height)
        val back = GeometryMapping.orientation(g).inverse()
        val v = back.mapVector(frame.centerX - planeW / 2.0, frame.centerY - planeH / 2.0)
        val odd = g.rotate % 2 == 1
        val w = if (odd) frame.height else frame.width
        val h = if (odd) frame.width else frame.height
        return Box.centred(v.x + width / 2.0, v.y + height / 2.0, w, h)
    }

    /** Whether [frame] is allowed for [g] (see the class comment), within [tolerance] pixels. */
    fun isValid(g: Geometry, frame: Box, width: Int, height: Int, tolerance: Double = EPS): Boolean {
        if (frame.width <= 0.0 || frame.height <= 0.0) return false
        val r = imageRect(g, frame, width, height)
        val inside =
            r.left >= -tolerance && r.top >= -tolerance && r.right <= width + tolerance &&
                r.bottom <= height + tolerance
        if (!inside) return false
        if (!g.constrain) return true
        val rad = Math.toRadians(GeometryMapping.angle(g))
        val cs = abs(cos(rad))
        val sn = abs(sin(rad))
        val ex = cs * r.width / 2 + sn * r.height / 2
        val ey = sn * r.width / 2 + cs * r.height / 2
        val straightInside = r.centerX - ex >= -tolerance && r.centerX + ex <= width + tolerance &&
            r.centerY - ey >= -tolerance && r.centerY + ey <= height + tolerance
        if (!straightInside) return false
        if (g.transform.isIdentity) return true
        return cornersInsideTransformed(g, r, width, height, tolerance)
    }

    /** Whether the corners of [r] straightened by [g]'s angle map back into the corrected image (see the class). */
    private fun cornersInsideTransformed(g: Geometry, r: Box, width: Int, height: Int, tolerance: Double): Boolean {
        val toCorrected = Perspective.toCorrected(g.transform)
        val half = kotlin.math.hypot(width.toDouble(), height.toDouble()) / 2
        val turn = Affine.rotation(-GeometryMapping.angle(g))
        val limitX = width / 2.0 / half + tolerance / half
        val limitY = height / 2.0 / half + tolerance / half
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val v = turn.mapVector(sx * r.width / 2, sy * r.height / 2)
                val px = r.centerX + v.x
                val py = r.centerY + v.y
                val q = toCorrected.map((px - width / 2.0) / half, (py - height / 2.0) / half) ?: return false
                if (abs(q.x) > limitX || abs(q.y) > limitY) return false
            }
        }
        return true
    }

    /** The last valid frame on the straight path from the valid [from] to [to] ([to] itself when valid). */
    fun limit(g: Geometry, from: Box, to: Box, width: Int, height: Int): Box {
        // Searched strictly (no tolerance) so the result survives the float rounding of the stored crop.
        if (isValid(g, to, width, height, 0.0)) return to
        if (!isValid(g, from, width, height)) return from
        var lo = 0.0
        var hi = 1.0
        repeat(SEARCH_STEPS) {
            val mid = (lo + hi) / 2
            if (isValid(g, lerp(from, to, mid), width, height, 0.0)) lo = mid else hi = mid
        }
        return lerp(from, to, lo)
    }

    /**
     * A handle drag: [start] is the frame when the gesture began, ([dx], [dy]) the finger's travel in plane units.
     * With a [ratio] (width / height as seen) corners keep the opposite corner and edges keep the frame centred
     * across. The frame never gets smaller than [minSize] and stops at the last valid position.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun dragHandle(
        g: Geometry,
        start: Box,
        handle: CropHandle,
        dx: Double,
        dy: Double,
        ratio: Double?,
        width: Int,
        height: Int,
        minSize: Double,
    ): Box {
        if (ratio == null) {
            // Free: each axis on its own, so a corner that hits one side keeps sliding along the other.
            val xMoved = moveEdges(start, handle.hx, 0, dx, 0.0, minSize)
            val afterX = limit(g, start, xMoved, width, height)
            val yMoved = moveEdges(afterX, 0, handle.hy, 0.0, dy, minSize)
            return limit(g, afterX, yMoved, width, height)
        }
        val minW = max(minSize, minSize * ratio)
        // The search starts from the smallest frame of the ratio at the same anchor, so every step keeps the ratio
        // even when the starting frame did not have it yet (a preset picked on a free crop).
        val smallest = if (handle.corner) {
            val anchorX = if (handle.hx > 0) start.left else start.right
            val anchorY = if (handle.hy > 0) start.top else start.bottom
            Box.centred(anchorX + handle.hx * minW / 2, anchorY + handle.hy * minW / ratio / 2, minW, minW / ratio)
        } else if (handle.hx != 0) {
            val anchorX = if (handle.hx > 0) start.left else start.right
            Box.centred(anchorX + handle.hx * minW / 2, start.centerY, minW, minW / ratio)
        } else {
            val anchorY = if (handle.hy > 0) start.top else start.bottom
            Box.centred(start.centerX, anchorY + handle.hy * minW / ratio / 2, minW, minW / ratio)
        }
        val target = if (handle.corner) {
            val anchorX = if (handle.hx > 0) start.left else start.right
            val anchorY = if (handle.hy > 0) start.top else start.bottom
            val wanted = max(abs(start.width + handle.hx * dx), abs(start.height + handle.hy * dy) * ratio)
            val w = max(wanted, minW)
            val h = w / ratio
            Box(
                if (handle.hx > 0) anchorX else anchorX - w,
                if (handle.hy > 0) anchorY else anchorY - h,
                if (handle.hx > 0) anchorX + w else anchorX,
                if (handle.hy > 0) anchorY + h else anchorY,
            )
        } else if (handle.hx != 0) {
            val w = max(start.width + handle.hx * dx, minW)
            val left = if (handle.hx > 0) start.left else start.right - w
            Box.centred(left + w / 2, start.centerY, w, w / ratio)
        } else {
            val h = max(start.height + handle.hy * dy, minW / ratio)
            val top = if (handle.hy > 0) start.top else start.bottom - h
            Box.centred(start.centerX, top + h / 2, h * ratio, h)
        }
        if (!isValid(g, smallest, width, height)) return start
        return limit(g, smallest, target, width, height)
    }

    /** The photo dragged by ([dx], [dy]) plane units under the frame: the frame moves the other way, per axis. */
    fun pan(g: Geometry, start: Box, dx: Double, dy: Double, width: Int, height: Int): Box {
        val afterX = limit(g, start, start.offset(-dx, 0.0), width, height)
        return limit(g, afterX, afterX.offset(0.0, -dy), width, height)
    }

    /**
     * A pinch inside the crop: the photo grows by [factor] about the plane point ([focusX], [focusY]), so the frame
     * shrinks by `1 / factor` about it (aspect kept), never below [minSize].
     */
    @Suppress("LongParameterList")
    fun pinch(
        g: Geometry,
        start: Box,
        factor: Double,
        focusX: Double,
        focusY: Double,
        width: Int,
        height: Int,
        minSize: Double,
    ): Box {
        val s = max(1.0 / factor, minSize / min(start.width, start.height))
        val target = Box(
            focusX + (start.left - focusX) * s,
            focusY + (start.top - focusY) * s,
            focusX + (start.right - focusX) * s,
            focusY + (start.bottom - focusY) * s,
        )
        return limit(g, start, target, width, height)
    }

    /**
     * [box] made valid for [g]: shrunk about its centre (after moving the centre into the image if it lies
     * outside). Used when straightening, changing the aspect or turning the constraint on.
     */
    fun shrinkToFit(g: Geometry, box: Box, width: Int, height: Int): Box {
        if (isValid(g, box, width, height)) return box
        val tiny = { cx: Double, cy: Double -> Box.centred(cx, cy, PROBE, PROBE) }
        var cx = box.centerX
        var cy = box.centerY
        if (!isValid(g, tiny(cx, cy), width, height)) {
            val plane = GeometryMapping.editing(g, width, height)
            val centre = plane.imageToOutput(width / 2.0, height / 2.0)
            val from = tiny(centre.x, centre.y)
            val moved = limit(g, from, tiny(cx, cy), width, height)
            cx = moved.centerX
            cy = moved.centerY
        }
        return limit(g, tiny(cx, cy), Box.centred(cx, cy, box.width, box.height), width, height)
    }

    /**
     * Straightening to [angle] (degrees, clamped to the recipe range) from [start], the geometry when the gesture
     * began: the frame stays where it is in the plane (the image turns about the crop centre) and, with the
     * constraint on, shrinks about its centre so no empty corner enters it; turning back regrows it up to [start]'s.
     */
    fun straighten(start: Geometry, angle: Float, width: Int, height: Int): Geometry {
        val range = RecipeRanges.angle
        // One decimal exactly (0.1 steps counted from -45 in float would store 9.100002).
        val g = start.copy(angle = range.clamp(Math.round(angle * TENTHS) / TENTHS))
        if (!g.constrain) return g
        val box = shrinkToFit(g, frame(start, width, height), width, height)
        return withFrame(g, box, width, height)
    }

    /**
     * The angle that makes the line from ([x0], [y0]) to ([x1], [y1]) (editing plane, as seen) level, or plumb when
     * it is closer to vertical, added to [g]'s current angle; clamped to the recipe range.
     */
    fun horizonAngle(g: Geometry, x0: Double, y0: Double, x1: Double, y1: Double): Float {
        var seen = Math.toDegrees(atan2(y1 - y0, x1 - x0))
        // Direction does not matter: fold into (-90, 90], then a near-vertical line is made plumb.
        if (seen > SQUARE_ANGLE) seen -= STRAIGHT_ANGLE
        if (seen <= -SQUARE_ANGLE) seen += STRAIGHT_ANGLE
        if (seen > HALF_RIGHT_ANGLE) {
            seen -= SQUARE_ANGLE
        } else if (seen < -HALF_RIGHT_ANGLE) {
            seen += SQUARE_ANGLE
        }
        // Turning by -seen as seen; a mirrored picture turns the other way in recipe terms.
        val recipeDelta = -seen * mirrorSign(g)
        return RecipeRanges.angle.clamp((g.angle + recipeDelta).toFloat())
    }

    /**
     * The Transform set to [transform] from [start], the geometry when the gesture began: with the constraint on, the
     * frame shrinks about its centre so no empty corner enters it (going back regrows it up to [start]'s frame).
     */
    fun transform(start: Geometry, transform: Transform, width: Int, height: Int): Geometry {
        val g = start.copy(transform = transform)
        if (!g.constrain) return g
        val box = shrinkToFit(g, frame(start, width, height), width, height)
        return withFrame(g, box, width, height)
    }

    /** -1 when the picture is mirrored (exactly one flip): a clockwise turn as seen is anticlockwise in the recipe. */
    fun mirrorSign(g: Geometry): Int = if (g.flipH != g.flipV) -1 else 1

    /** The straighten angle as seen on screen (the dial's value): the recipe's, negated when mirrored. */
    fun seenAngle(g: Geometry): Float = if (mirrorSign(g) < 0) -g.angle else g.angle

    /** The recipe angle for an angle [seen] on screen. */
    fun recipeAngle(g: Geometry, seen: Float): Float = if (mirrorSign(g) < 0) -seen else seen

    /** A quarter turn of the whole picture as seen (the crop follows; the aspect is transposed). */
    fun rotate90(g: Geometry, clockwise: Boolean): Geometry {
        // D = F * Q^k: a turn as seen is Q * D, and Q * F = F * Q^-1 when exactly one flip is set.
        val step = (if (clockwise) 1 else -1) * mirrorSign(g)
        val k = Math.floorMod(g.rotate + step, QUARTERS)
        return g.copy(rotate = k, aspect = transposed(g.aspect))
    }

    /** Mirrors the picture as seen ([horizontal] = left/right). */
    fun flip(g: Geometry, horizontal: Boolean): Geometry =
        if (horizontal) g.copy(flipH = !g.flipH) else g.copy(flipV = !g.flipV)

    /** The frame's width / height as seen that [g]'s aspect asks for, or `null` for a free frame. */
    fun ratio(g: Geometry, width: Int, height: Int): Double? {
        val a = g.aspect
        return when (a.preset) {
            AspectPreset.FREE -> if (a.locked) frame(g, width, height).let { it.width / it.height } else null
            AspectPreset.ORIGINAL -> planeSize(g, width, height).let { (w, h) -> w.toDouble() / h }
            AspectPreset.SQUARE -> 1.0
            AspectPreset.R4_5 -> 4.0 / 5.0
            AspectPreset.R5_4 -> 5.0 / 4.0
            AspectPreset.R3_2 -> 3.0 / 2.0
            AspectPreset.R2_3 -> 2.0 / 3.0
            AspectPreset.R16_9 -> 16.0 / 9.0
            AspectPreset.R9_16 -> 9.0 / 16.0
            AspectPreset.CUSTOM -> a.w.toDouble() / a.h
        }
    }

    /**
     * Picks an aspect: a preset locks the frame to it and refits the frame (the largest frame of that ratio around
     * the current centre); [AspectPreset.FREE] unlocks.
     */
    fun withAspect(g: Geometry, aspect: Aspect, width: Int, height: Int): Geometry {
        val next = g.copy(aspect = if (aspect.preset == AspectPreset.FREE) aspect.copy(locked = false) else aspect)
        val r = ratio(next, width, height) ?: return next
        return withFrame(next, fitRatio(next, r, frame(g, width, height), width, height), width, height)
    }

    /** Locks the frame to its current shape, or unlocks it (any preset becomes free). */
    fun setLocked(g: Geometry, locked: Boolean): Geometry = if (locked) {
        g.copy(aspect = g.aspect.copy(locked = true))
    } else {
        g.copy(aspect = g.aspect.copy(preset = AspectPreset.FREE, locked = false))
    }

    /** Portrait <-> landscape: the aspect is transposed and the frame refitted with the swapped shape. */
    fun swapOrientation(g: Geometry, width: Int, height: Int): Geometry {
        val current = frame(g, width, height)
        val next = g.copy(
            aspect = when (g.aspect.preset) {
                // "Original" turned is a custom ratio: the original's height:width.
                AspectPreset.ORIGINAL -> planeSize(g, width, height).let { (w, h) ->
                    Aspect(AspectPreset.CUSTOM, h, w, true)
                }

                else -> transposed(g.aspect)
            },
        )
        val r = ratio(next, width, height) ?: (current.height / current.width)
        return withFrame(next, fitRatio(next, r, current, width, height), width, height)
    }

    /** Turns the "constrain to image" option on (shrinking the frame if needed) or off. */
    fun setConstrain(g: Geometry, on: Boolean, width: Int, height: Int): Geometry {
        val next = g.copy(constrain = on)
        if (!on) return next
        return withFrame(next, shrinkToFit(next, frame(g, width, height), width, height), width, height)
    }

    /** No crop, angle, turn or flip; the aspect and the constraint option are kept. */
    fun reset(g: Geometry): Geometry = Geometry(aspect = g.aspect.copy(), constrain = g.constrain)

    /**
     * The largest frame of [ratio] that fits the plane, centred on [around]'s centre (moved inside the plane when
     * needed), then made valid.
     */
    private fun fitRatio(g: Geometry, ratio: Double, around: Box, width: Int, height: Int): Box {
        val (planeW, planeH) = planeSize(g, width, height)
        val w = min(planeW.toDouble(), planeH * ratio)
        val h = w / ratio
        val cx = around.centerX.coerceIn(w / 2, planeW - w / 2)
        val cy = around.centerY.coerceIn(h / 2, planeH - h / 2)
        return shrinkToFit(g, Box.centred(cx, cy, w, h), width, height)
    }

    private fun transposed(a: Aspect): Aspect = when (a.preset) {
        AspectPreset.R4_5 -> a.copy(preset = AspectPreset.R5_4)
        AspectPreset.R5_4 -> a.copy(preset = AspectPreset.R4_5)
        AspectPreset.R3_2 -> a.copy(preset = AspectPreset.R2_3)
        AspectPreset.R2_3 -> a.copy(preset = AspectPreset.R3_2)
        AspectPreset.R16_9 -> a.copy(preset = AspectPreset.R9_16)
        AspectPreset.R9_16 -> a.copy(preset = AspectPreset.R16_9)
        AspectPreset.CUSTOM -> a.copy(w = a.h, h = a.w)
        AspectPreset.FREE, AspectPreset.ORIGINAL, AspectPreset.SQUARE -> a
    }

    @Suppress("LongParameterList")
    private fun moveEdges(b: Box, hx: Int, hy: Int, dx: Double, dy: Double, minSize: Double): Box {
        var left = b.left
        var right = b.right
        var top = b.top
        var bottom = b.bottom
        if (hx < 0) left = min(b.left + dx, b.right - minSize)
        if (hx > 0) right = max(b.right + dx, b.left + minSize)
        if (hy < 0) top = min(b.top + dy, b.bottom - minSize)
        if (hy > 0) bottom = max(b.bottom + dy, b.top + minSize)
        return Box(left, top, right, bottom)
    }

    private fun lerp(a: Box, b: Box, t: Double): Box = Box(
        a.left + (b.left - a.left) * t,
        a.top + (b.top - a.top) * t,
        a.right + (b.right - a.right) * t,
        a.bottom + (b.bottom - a.bottom) * t,
    )

    private const val QUARTERS = 4
    private const val TENTHS = 10f
}
