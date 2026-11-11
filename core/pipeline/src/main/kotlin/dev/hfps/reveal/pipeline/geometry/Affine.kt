package dev.hfps.reveal.pipeline.geometry

import kotlin.math.cos
import kotlin.math.sin

/** A point in pixel coordinates (x right, y down). */
data class Point(val x: Double, val y: Double)

/** An axis-aligned rectangle in pixel coordinates (x right, y down). */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0

    /** This box moved by ([dx], [dy]). */
    fun offset(dx: Double, dy: Double): Box = Box(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        /** The box of [width] x [height] centred on ([cx], [cy]). */
        fun centred(cx: Double, cy: Double, width: Double, height: Double): Box =
            Box(cx - width / 2.0, cy - height / 2.0, cx + width / 2.0, cy + height / 2.0)
    }
}

/**
 * A 2D affine map `(x, y) -> (a x + b y + tx, c x + d y + ty)`, in double precision. Composition reads like
 * function application: `(f * g)(p) = f(g(p))`.
 */
data class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val tx: Double,
    val ty: Double,
) {
    fun mapX(x: Double, y: Double): Double = a * x + b * y + tx

    fun mapY(x: Double, y: Double): Double = c * x + d * y + ty

    fun map(p: Point): Point = Point(mapX(p.x, p.y), mapY(p.x, p.y))

    /** The linear part applied to the vector ([x], [y]) (no translation). */
    fun mapVector(x: Double, y: Double): Point = Point(a * x + b * y, c * x + d * y)

    /** `this(other(p))`. */
    operator fun times(other: Affine): Affine = Affine(
        a = a * other.a + b * other.c,
        b = a * other.b + b * other.d,
        c = c * other.a + d * other.c,
        d = c * other.b + d * other.d,
        tx = a * other.tx + b * other.ty + tx,
        ty = c * other.tx + d * other.ty + ty,
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        require(det != 0.0) { "singular affine map" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    /** The bounding box of [box] mapped by this transform. */
    fun mapBounds(box: Box): Box {
        val xs = doubleArrayOf(
            mapX(box.left, box.top),
            mapX(box.right, box.top),
            mapX(box.left, box.bottom),
            mapX(box.right, box.bottom),
        )
        val ys = doubleArrayOf(
            mapY(box.left, box.top),
            mapY(box.right, box.top),
            mapY(box.left, box.bottom),
            mapY(box.right, box.bottom),
        )
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }

    companion object {
        val IDENTITY: Affine = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translate(x: Double, y: Double): Affine = Affine(1.0, 0.0, 0.0, 1.0, x, y)

        /**
         * Rotation by [degrees] **clockwise as seen** in y-down pixel coordinates: `(1, 0)` turned by 90 points down
         * `(0, 1)`. Multiples of 90 are exact (no `sin`/`cos` rounding).
         */
        fun rotation(degrees: Double): Affine {
            val (cs, sn) = when (degrees) {
                0.0 -> 1.0 to 0.0
                90.0 -> 0.0 to 1.0
                180.0 -> -1.0 to 0.0
                270.0, -90.0 -> 0.0 to -1.0
                else -> Math.toRadians(degrees).let { cos(it) to sin(it) }
            }
            return Affine(cs, -sn, sn, cs, 0.0, 0.0)
        }

        /** Mirror: [horizontal] negates x, [vertical] negates y (about the origin). */
        fun flip(horizontal: Boolean, vertical: Boolean): Affine =
            Affine(if (horizontal) -1.0 else 1.0, 0.0, 0.0, if (vertical) -1.0 else 1.0, 0.0, 0.0)
    }
}
