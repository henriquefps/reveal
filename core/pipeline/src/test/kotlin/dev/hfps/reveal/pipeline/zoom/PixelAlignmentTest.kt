package dev.hfps.reveal.pipeline.zoom

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Test

/**
 * docs/adr/0012 "Pixel alignment at 1:1": after [PixelAlignment] every window pixel centre of a 1:1 view lands on an
 * image pixel centre (fraction 0.5), with the shader's float maths too, so `floor` cannot flip between rows; the view
 * moves by less than half a pixel. The views here are the worst case: pixel centres exactly on pixel edges, where the
 * editor's double-tap to 100 % landed on the Tab S9 Ultra.
 */
class PixelAlignmentTest {
    private val geometries = listOf(
        Geometry(),
        Geometry(crop = CropRect(0.1f, 0.13f, 0.87f, 0.9f)),
        Geometry(crop = CropRect(0.25f, 0.2f, 1f, 1f), rotate = 1),
        Geometry(rotate = 2, flipH = true),
        Geometry(crop = CropRect(0.05f, 0.31f, 0.77f, 0.97f), rotate = 3, flipV = true),
    )

    @Test
    fun `full resolution tiles at 100 percent land on pixel centres`() {
        val k = 1.3513514f
        for (geometry in geometries) {
            val full = GeometryMapping.of(geometry, FULL_W, FULL_H)
            val proxy = GeometryMapping.of(geometry, (FULL_W / k).toInt(), (FULL_H / k).toInt())
            val zoom = ZoomView(k, proxy.outputWidth, proxy.outputHeight, full)
            val edge = edgeView(k) { zoom.imagePoint(it, WINDOW_W, WINDOW_H, HALF, HALF) }
            assertThat(zoom.fullScale(edge.scale)).isEqualTo(1f)
            val aligned = PixelAlignment.tiles(edge, WINDOW_W, WINDOW_H, zoom)
            assertMoved(edge, aligned, k)
            assertCentres(geometry) { x, y -> zoom.imagePoint(aligned, WINDOW_W, WINDOW_H, x, y) }
            assertCentres(geometry) { x, y -> shaderTilePoint(aligned, zoom, x, y) }
        }
    }

    @Test
    fun `the proxy at scale 1 lands on pixel centres`() {
        for (geometry in geometries) {
            val mapping = GeometryMapping.of(geometry, PROXY_W, PROXY_H)
            val edge = edgeView(1f) { proxyPoint(it, mapping, HALF, HALF) }
            val aligned = PixelAlignment.proxy(edge, WINDOW_W, WINDOW_H, mapping)
            assertMoved(edge, aligned, 1f)
            assertCentres(geometry) { x, y -> proxyPoint(aligned, mapping, x, y) }
        }
    }

    @Test
    fun `an aligned view stays where it is`() {
        val mapping = GeometryMapping.identity(PROXY_W, PROXY_H)
        val view = ViewTransform(1f, PROXY_W / 2f, PROXY_H / 2f)
        val aligned = PixelAlignment.proxy(view, WINDOW_W, WINDOW_H, mapping)
        assertThat(aligned.centerX).isWithin(1e-3f).of(view.centerX)
        assertThat(aligned.centerY).isWithin(1e-3f).of(view.centerY)
        assertThat(PixelAlignment.proxy(aligned, WINDOW_W, WINDOW_H, mapping)).isEqualTo(aligned)
    }

    /**
     * A view of [scale] whose window pixel (0.5, 0.5) maps to an image pixel **edge** (both coordinates whole): Newton
     * steps on the view centre, with [image] = the image point of that window pixel under a view.
     */
    private fun edgeView(scale: Float, image: (ViewTransform) -> Pair<Float, Float>): ViewTransform {
        var view = ViewTransform(scale, START_X, START_Y)
        repeat(NEWTON_STEPS) {
            val (ix, iy) = image(view)
            val (jx, jy) = image(ViewTransform(scale, view.centerX + 1f, view.centerY))
            val (kx, ky) = image(ViewTransform(scale, view.centerX, view.centerY + 1f))
            val a = jx - ix
            val b = kx - ix
            val c = jy - iy
            val d = ky - iy
            val det = a * d - b * c
            val ex = ix - Math.round(ix)
            val ey = iy - Math.round(iy)
            view = ViewTransform(scale, view.centerX - (d * ex - b * ey) / det, view.centerY - (a * ey - c * ex) / det)
        }
        return view
    }

    /** The proxy output pass's image point of window pixel ([x], [y]) (`Shaders.OUTPUT_FRAG`, float maths). */
    private fun proxyPoint(view: ViewTransform, mapping: GeometryMapping, x: Float, y: Float): Pair<Float, Float> {
        val ox = (x - WINDOW_W / 2f) / view.scale + view.centerX
        val oy = (y - WINDOW_H / 2f) / view.scale + view.centerY
        val m = mapping.toImageFloats()
        return m[0] * ox + m[1] * oy + m[2] to m[3] * ox + m[4] * oy + m[5]
    }

    /** The output pass's full-resolution image point (`OUTPUT_FRAG` tile path, float maths in the shader's order). */
    private fun shaderTilePoint(view: ViewTransform, zoom: ZoomView, x: Float, y: Float): Pair<Float, Float> {
        val ox = (x - WINDOW_W / 2f) / view.scale + view.centerX
        val oy = (y - WINDOW_H / 2f) / view.scale + view.centerY
        val fx = (ox - zoom.proxyOutputWidth * HALF) * zoom.k + zoom.full.outputWidth * HALF
        val fy = (oy - zoom.proxyOutputHeight * HALF) * zoom.k + zoom.full.outputHeight * HALF
        val m = zoom.full.toImageFloats()
        return m[0] * fx + m[1] * fy + m[2] to m[3] * fx + m[4] * fy + m[5]
    }

    /** Every sampled window pixel centre maps to an image pixel centre (well inside its pixel). */
    private fun assertCentres(geometry: Geometry, image: (Float, Float) -> Pair<Float, Float>) {
        for (sy in 0 until WINDOW_H step STEP) {
            for (sx in 0 until WINDOW_W step STEP) {
                val (ix, iy) = image(sx + HALF, sy + HALF)
                val fx = ix - floor(ix)
                val fy = iy - floor(iy)
                assertCentre(geometry, sx, sy, fx, fy)
            }
        }
    }

    private fun assertCentre(geometry: Geometry, sx: Int, sy: Int, fx: Float, fy: Float) {
        val ok = abs(fx - HALF) < MARGIN && abs(fy - HALF) < MARGIN
        assertWithMessage("$geometry window $sx,$sy fraction $fx,$fy").that(ok).isTrue()
    }

    /** The aligned view is less than half an image pixel from [before] ([scale] screen pixels per proxy pixel). */
    private fun assertMoved(before: ViewTransform, after: ViewTransform, scale: Float) {
        assertThat(after.scale).isEqualTo(before.scale)
        assertThat(abs(after.centerX - before.centerX) * scale).isAtMost(HALF + 1e-3f)
        assertThat(abs(after.centerY - before.centerY) * scale).isAtMost(HALF + 1e-3f)
    }

    private companion object {
        const val FULL_W = 4000
        const val FULL_H = 3000
        const val PROXY_W = 2400
        const val PROXY_H = 1800
        const val WINDOW_W = 2960
        const val WINDOW_H = 1848
        const val STEP = 37
        const val HALF = 0.5f
        const val START_X = 811.37f
        const val START_Y = 603.71f
        const val NEWTON_STEPS = 3

        /** Far from the edges: float rounding is ~1e-4 px here, the margin before `floor` flips is 0.5. */
        const val MARGIN = 0.05f
    }
}
