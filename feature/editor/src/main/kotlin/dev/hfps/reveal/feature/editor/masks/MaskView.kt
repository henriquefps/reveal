package dev.hfps.reveal.feature.editor.masks

import androidx.compose.ui.geometry.Offset
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.geometry.Point
import dev.hfps.reveal.pipeline.geometry.Warp
import dev.hfps.reveal.pipeline.gl.ViewTransform
import kotlin.math.abs
import kotlin.math.max

/**
 * Where a mask's image coordinates are on screen (docs/adr/0022: positions are fractions of the upright image, before
 * crop and transform): image fraction -> proxy pixel -> the geometry operator's plane (through the inverse of the lens
 * and Transform [Warp], solved numerically) -> its output ([GeometryMapping.toOutput]: crop, straighten, turns, flips)
 * -> the photo slot through the zoom [view]. So handles and strokes follow crop, rotation, flips, lens corrections and
 * zoom like the pixels do. [toImage] is the exact inverse (the warp's forward direction is closed-form).
 *
 * @param slotWidth the photo slot's size in pixels (the view's centre is the slot's centre).
 */
class MaskView(val mapping: GeometryMapping, val view: ViewTransform, val slotWidth: Float, val slotHeight: Float) {
    val imageWidth: Int get() = mapping.imageWidth
    val imageHeight: Int get() = mapping.imageHeight

    /** The image's long edge in proxy pixels (lengths of masks are fractions of it). */
    val longEdge: Float get() = max(mapping.imageWidth, mapping.imageHeight).toFloat()

    /** Screen pixels per proxy pixel (the zoom scale; crop and straighten keep the size). */
    val pixelScale: Float get() = view.scale

    /** The slot point of image fraction ([x], [y]); `null` when it is behind the Transform's horizon. */
    fun toSlot(x: Float, y: Float): Offset? {
        val px = x.toDouble() * mapping.imageWidth
        val py = y.toDouble() * mapping.imageHeight
        val plane = mapping.warp?.let { invertWarp(it, px, py) ?: return null } ?: Point(px, py)
        val out = mapping.imageToOutput(plane.x, plane.y)
        return Offset(
            ((out.x - view.centerX) * view.scale + slotWidth / 2.0).toFloat(),
            ((out.y - view.centerY) * view.scale + slotHeight / 2.0).toFloat(),
        )
    }

    /** The image fraction under slot point [p] (outside the image too: shapes may extend past it); `null` if none. */
    fun toImage(p: Offset): Offset? {
        val ox = view.centerX + (p.x - slotWidth / 2.0) / view.scale
        val oy = view.centerY + (p.y - slotHeight / 2.0) / view.scale
        val plane = mapping.outputToImage(ox, oy)
        val src = mapping.warp?.let { it.source(plane.x, plane.y, GREEN) ?: return null } ?: plane
        return Offset((src.x / mapping.imageWidth).toFloat(), (src.y / mapping.imageHeight).toFloat())
    }

    /** Image fraction -> the isotropic plane of docs/adr/0022 (`u = x W / L`, `v = y H / L`). */
    fun toIso(p: Offset): Offset = Offset(p.x * imageWidth / longEdge, p.y * imageHeight / longEdge)

    /** The isotropic plane -> image fraction. */
    fun fromIso(q: Offset): Offset = Offset(q.x * longEdge / imageWidth, q.y * longEdge / imageHeight)

    /** The slot point of isotropic point [q]. */
    fun isoToSlot(q: Offset): Offset? = fromIso(q).let { toSlot(it.x, it.y) }

    /** The isotropic point under slot point [p]. */
    fun slotToIso(p: Offset): Offset? = toImage(p)?.let(::toIso)

    companion object {
        private const val GREEN = 1
        private const val MAX_STEPS = 12
        private const val TOLERANCE = 1e-3
        private const val STEP = 0.5

        /**
         * The plane point whose warped source is ([sx], [sy]) (green channel): Newton's method with a finite-difference
         * Jacobian, started at the source point itself (the lens and the Transform move points by a few percent).
         */
        @Suppress("ReturnCount")
        fun invertWarp(warp: Warp, sx: Double, sy: Double): Point? {
            var x = sx
            var y = sy
            repeat(MAX_STEPS) {
                val s = warp.source(x, y, GREEN) ?: return null
                val ex = s.x - sx
                val ey = s.y - sy
                if (abs(ex) < TOLERANCE && abs(ey) < TOLERANCE) return Point(x, y)
                val sxp = warp.source(x + STEP, y, GREEN) ?: return null
                val syp = warp.source(x, y + STEP, GREEN) ?: return null
                val a = (sxp.x - s.x) / STEP
                val b = (syp.x - s.x) / STEP
                val c = (sxp.y - s.y) / STEP
                val d = (syp.y - s.y) / STEP
                val det = a * d - b * c
                if (abs(det) < 1e-12) return null
                x -= (d * ex - b * ey) / det
                y -= (-c * ex + a * ey) / det
            }
            val s = warp.source(x, y, GREEN) ?: return null
            return if (abs(s.x - sx) < 1.0 && abs(s.y - sy) < 1.0) Point(x, y) else null
        }
    }
}
