package dev.hfps.reveal.pipeline.zoom

import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.geometry.Point
import dev.hfps.reveal.pipeline.gl.ViewTransform
import kotlin.math.abs
import kotlin.math.floor

/**
 * Pixel-exact 1:1 views (docs/adr/0012, "Pixel alignment at 1:1").
 *
 * At exactly one screen pixel per image pixel the output pass reads the texel under each screen pixel centre with
 * `texelFetch(floor(p))`. A view centre that puts the screen pixel centres on image pixel **edges** (fraction 0 instead
 * of 0.5) leaves that floor to the last bit of float rounding, which differs per GPU and per row: some rows then show
 * the image row below, the next ones the right row again (a duplicated or skipped pixel row or column every few rows).
 * The editor's double-tap to 100 % on the Tab S9 Ultra landed exactly there (`EditorZoomTest`, max 110 levels on
 * Adreno 740, 0 on the phone's Adreno 750, whose rounding happened to agree with the CPU).
 *
 * [align] moves the view centre by less than half an image pixel so that every screen pixel centre lands on an image
 * pixel centre: `floor` then has half a pixel of margin on every GPU, and the CPU (tests, hit testing) agrees. It is
 * computed in double from the same mapping the shader uses; views that are not exactly 1:1 or not axis-aligned are
 * returned unchanged.
 */
object PixelAlignment {
    /**
     * [view] in a [windowWidth] x [windowHeight] window, moved so that window pixel centres map to pixel centres of
     * the image [toImage] returns for an output point of the view (proxy output -> image, or the full-resolution
     * chain of [ZoomView]). Only call it for 1:1 axis-aligned views (see [proxy] and [tiles]).
     */
    fun align(
        view: ViewTransform,
        windowWidth: Int,
        windowHeight: Int,
        toImage: (Double, Double) -> Point,
    ): ViewTransform {
        val scale = view.scale.toDouble()
        val cx = view.centerX.toDouble()
        val cy = view.centerY.toDouble()
        fun image(x: Double, y: Double): Point =
            toImage((HALF - windowWidth / 2.0) / scale + x, (HALF - windowHeight / 2.0) / scale + y)
        val p = image(cx, cy)
        // The mapping is affine in the centre: its columns per unit of centre move.
        val ex = image(cx + 1.0, cy)
        val ey = image(cx, cy + 1.0)
        val a = ex.x - p.x
        val b = ey.x - p.x
        val c = ex.y - p.y
        val d = ey.y - p.y
        val det = a * d - b * c
        if (abs(det) < EPS) return view
        val dx = nearestCentre(p.x) - p.x
        val dy = nearestCentre(p.y) - p.y
        val mx = (d * dx - b * dy) / det
        val my = (a * dy - c * dx) / det
        return ViewTransform(view.scale, (cx + mx).toFloat(), (cy + my).toFloat())
    }

    /** The proxy at 1:1 ([view] scale exactly 1, axis-aligned [mapping]): aligned on the proxy's pixels. */
    fun proxy(view: ViewTransform, windowWidth: Int, windowHeight: Int, mapping: GeometryMapping): ViewTransform =
        align(view, windowWidth, windowHeight) { x, y -> mapping.outputToImage(x, y) }

    /** The full-resolution tiles at 100 % ([zoom]`.fullScale` exactly 1, axis-aligned): aligned on the full image. */
    fun tiles(view: ViewTransform, windowWidth: Int, windowHeight: Int, zoom: ZoomView): ViewTransform =
        align(view, windowWidth, windowHeight) { x, y ->
            val k = zoom.k.toDouble()
            zoom.full.outputToImage(
                (x - zoom.proxyOutputWidth / 2.0) * k + zoom.full.outputWidth / 2.0,
                (y - zoom.proxyOutputHeight / 2.0) * k + zoom.full.outputHeight / 2.0,
            )
        }

    /** The pixel centre (`n + 0.5`) nearest to [v]. */
    private fun nearestCentre(v: Double): Double = floor(v) + HALF

    private const val HALF = 0.5
    private const val EPS = 1e-12
}
