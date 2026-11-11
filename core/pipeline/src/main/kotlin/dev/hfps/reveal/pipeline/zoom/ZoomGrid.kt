// Numeric and geometric code: early returns validate, loops mirror the shaders, constants are the maths.
@file:Suppress("ReturnCount")

package dev.hfps.reveal.pipeline.zoom

import dev.hfps.reveal.pipeline.export.PixelBox
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.gl.ViewFrame
import dev.hfps.reveal.pipeline.gl.ViewTransform
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The full-resolution tile grid of the editor's zoom (docs/adr/0012): square cores of [tile] pixels over the upright
 * [imageWidth] x [imageHeight] image, row-major. Each tile is stored in an atlas slot with a [BORDER] of neighbours on
 * every side, so the output pass can filter across a core's edge without reading another slot. Pure, JVM-tested.
 */
class ZoomGrid(val imageWidth: Int, val imageHeight: Int, val tile: Int = TILE) {
    init {
        require(imageWidth > 0 && imageHeight > 0) { "empty image" }
    }

    val columns: Int = (imageWidth + tile - 1) / tile
    val rows: Int = (imageHeight + tile - 1) / tile

    /** The core (image pixels) of tile ([tx], [ty]). */
    fun core(tx: Int, ty: Int): PixelBox {
        val x = tx * tile
        val y = ty * tile
        return PixelBox(x, y, min(tile, imageWidth - x), min(tile, imageHeight - y))
    }

    /** A stable key of tile ([tx], [ty]). */
    fun key(tx: Int, ty: Int): Int = ty * columns + tx

    companion object {
        /** Core edge in pixels. */
        const val TILE: Int = 512

        /**
         * Neighbours kept around a core in its slot: Catmull-Rom reads 2, bilinear 1, and level 1 (the 2x2 means for
         * views between 50 % and 100 %) needs 1 there, i.e. 2 at level 0; 8 keeps both levels aligned with room.
         */
        const val BORDER: Int = 8

        /** Slot edge in the atlas (even, so level 1 of a slot is exactly half). */
        const val SLOT: Int = TILE + 2 * BORDER

        /** Smallest full-resolution view scale that shows tiles (level 1 of the slots covers 50-100 %). */
        const val MIN_SCALE: Float = 0.5f
    }
}

/**
 * How the editor's view maps to the full-resolution image (docs/adr/0012): the editor zooms on the proxy's geometry
 * output; [k] full-resolution pixels per proxy pixel (the long-edge ratio the 100 % zoom uses), so a view of scale `s`
 * shows the full-resolution output at `s / k`. The full-resolution output point of a proxy output point `o` is
 * `(o - proxyOutput / 2) * k + fullOutput / 2` (the same centre; crop rounding differs by under a proxy pixel), and the
 * image point is [full]'s mapping of it.
 */
class ZoomView(val k: Float, val proxyOutputWidth: Int, val proxyOutputHeight: Int, val full: GeometryMapping) {
    /** Screen pixels per full-resolution pixel of a view of [scale] (exactly 1 at the editor's 100 %). */
    fun fullScale(scale: Float): Float = scale / k

    /** Full-resolution output point of proxy output point ([x], [y]). */
    fun fullOutput(x: Float, y: Float): Pair<Float, Float> = (x - proxyOutputWidth / 2f) * k + full.outputWidth / 2f to
        (y - proxyOutputHeight / 2f) * k + full.outputHeight / 2f

    /**
     * The tiles of [grid] a pane shows: [view] in a [windowWidth] x [windowHeight] surface (the output pass's maths:
     * output point `(sp - window / 2) / scale + centre`), clipped to [clip] (window pixels; null = whole window),
     * plus [margin] tiles around (prefetch), nearest to the pane's centre first. Empty below [ZoomGrid.MIN_SCALE].
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun visibleTiles(
        view: ViewTransform,
        windowWidth: Int,
        windowHeight: Int,
        clip: ViewFrame?,
        grid: ZoomGrid,
        margin: Int = 0,
    ): List<Pair<Int, Int>> {
        if (fullScale(view.scale) < ZoomGrid.MIN_SCALE) return emptyList()
        val frame = clip ?: ViewFrame.whole(windowWidth, windowHeight)
        // The frame's border (corners suffice for an affine mapping; a warp bends it, docs/adr/0015).
        val steps = if (full.warp == null) 1 else WARP_EDGE_STEPS
        val border = ArrayList<Pair<Float, Float>>()
        for (k in 0..steps) {
            val t = k.toFloat() / steps
            border += frame.left + frame.width * t to frame.top
            border += frame.left + frame.width * t to frame.top + frame.height
            border += frame.left to frame.top + frame.height * t
            border += frame.left + frame.width to frame.top + frame.height * t
        }
        val corners = border.mapNotNull { (sx, sy) -> sourcePoint(view, windowWidth, windowHeight, sx, sy) }
        if (corners.isEmpty()) return emptyList()
        val minX = corners.minOf { it.first }
        val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }
        val maxY = corners.maxOf { it.second }
        val outside = maxX < 0f || maxY < 0f || minX >= grid.imageWidth
        if (outside || minY >= grid.imageHeight) return emptyList()
        val tx0 = max(0, floor(minX / grid.tile).toInt() - margin)
        val ty0 = max(0, floor(minY / grid.tile).toInt() - margin)
        val tx1 = min(grid.columns - 1, floor(maxX / grid.tile).toInt() + margin)
        val ty1 = min(grid.rows - 1, floor(maxY / grid.tile).toInt() + margin)
        val (cx, cy) = sourcePoint(view, windowWidth, windowHeight, frame.centerX, frame.centerY)
            ?: imagePoint(view, windowWidth, windowHeight, frame.centerX, frame.centerY)
        val out = ArrayList<Pair<Int, Int>>()
        for (ty in ty0..ty1) for (tx in tx0..tx1) out += tx to ty
        return out.sortedBy { (tx, ty) ->
            val dx = (tx + HALF) * grid.tile - cx
            val dy = (ty + HALF) * grid.tile - cy
            dx * dx + dy * dy
        }
    }

    /**
     * Full-resolution **source** point (where the tiles are) of window pixel ([sx], [sy]): [imagePoint] through the
     * mapping's warp (green), or `null` behind the horizon.
     */
    fun sourcePoint(
        view: ViewTransform,
        windowWidth: Int,
        windowHeight: Int,
        sx: Float,
        sy: Float,
    ): Pair<Float, Float>? {
        val (x, y) = imagePoint(view, windowWidth, windowHeight, sx, sy)
        val warp = full.warp ?: return x to y
        val s = warp.source(x.toDouble(), y.toDouble(), 1) ?: return null
        return s.x.toFloat() to s.y.toFloat()
    }

    /** Full-resolution image point (the geometry's plane) of window pixel ([sx], [sy]) under [view]. */
    fun imagePoint(view: ViewTransform, windowWidth: Int, windowHeight: Int, sx: Float, sy: Float): Pair<Float, Float> {
        val ox = (sx - windowWidth / 2f) / view.scale + view.centerX
        val oy = (sy - windowHeight / 2f) / view.scale + view.centerY
        val (fx, fy) = fullOutput(ox, oy)
        val p = full.outputToImage(fx.toDouble(), fy.toDouble())
        return p.x.toFloat() to p.y.toFloat()
    }

    private companion object {
        const val HALF = 0.5f
        const val WARP_EDGE_STEPS = 8
    }
}
