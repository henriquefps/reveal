package dev.hfps.reveal.pipeline.zoom

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.export.PixelBox
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.gl.ViewFrame
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import org.junit.Test

/** The zoom's tile grid and the proxy view -> full-resolution mapping (docs/adr/0012). */
class ZoomGridTest {
    private val grid = ZoomGrid(6000, 4000)

    @Test
    fun `the grid covers the image with partial tiles at the right and bottom`() {
        assertThat(grid.columns).isEqualTo(12)
        assertThat(grid.rows).isEqualTo(8)
        assertThat(grid.core(11, 7)).isEqualTo(PixelBox(5632, 3584, 368, 416))
        assertThat(ZoomGrid.SLOT % 2).isEqualTo(0)
        assertThat(ZoomGrid.BORDER % 2).isEqualTo(0)
    }

    @Test
    fun `100 percent is one full resolution pixel per screen pixel`() {
        // A 24 MP photo on a 2400 x 1600 proxy: k = 2.5, the editor's 100 % is scale 2.5.
        val view = ZoomView(2.5f, 2400, 1600, GeometryMapping.identity(6000, 4000))
        assertThat(view.fullScale(2.5f)).isEqualTo(1f)
        // The proxy's centre is the full image's centre; one proxy pixel is 2.5 full pixels.
        assertThat(view.fullOutput(1200f, 800f)).isEqualTo(3000f to 2000f)
        assertThat(view.fullOutput(1201f, 800f)).isEqualTo(3002.5f to 2000f)
    }

    @Test
    fun `visible tiles at 100 percent are the ones under the pane, centre first`() {
        val view = ZoomView(2.5f, 2400, 1600, GeometryMapping.identity(6000, 4000))
        // A 1080 x 1300 frame at 100 % centred on full pixel (3000, 2000): x 2460..3540, y 1350..2650.
        val pane = ViewTransform(2.5f, 1200f, 800f)
        val tiles = view.visibleTiles(pane, 1080, 1300, null, grid)
        assertThat(tiles.toSet()).isEqualTo(setOf(4, 5, 6).flatMap { x -> (2..5).map { x to it } }.toSet())
        // Full pixel (3000, 2000) lies in tile (5, 3): the nearest tile centre comes first.
        assertThat(tiles.first()).isEqualTo(5 to 3)
        // A clip (the right half of a side-by-side) only shows its part.
        val clipped = view.visibleTiles(pane, 1080, 1300, ViewFrame(540f, 0f, 540f, 1300f), grid)
        assertThat(clipped.map { it.first }.toSet()).isEqualTo(setOf(5, 6))
    }

    @Test
    fun `below half the full resolution no tiles are wanted`() {
        val view = ZoomView(2.5f, 2400, 1600, GeometryMapping.identity(6000, 4000))
        assertThat(view.visibleTiles(ViewTransform(1.2f, 1200f, 800f), 1080, 1300, null, grid)).isEmpty()
        assertThat(view.visibleTiles(ViewTransform(1.25f, 1200f, 800f), 1080, 1300, null, grid)).isNotEmpty()
    }

    @Test
    fun `a cropped and turned photo maps through the full resolution geometry`() {
        val geometry = Geometry(crop = CropRect(0.5f, 0.5f, 1f, 1f), rotate = 1)
        val full = GeometryMapping.of(geometry, 6000, 4000)
        val proxy = GeometryMapping.of(geometry, 2400, 1600)
        val view = ZoomView(2.5f, proxy.outputWidth, proxy.outputHeight, full)
        // The centre of the output is the centre of the crop: full pixel (4500, 3000).
        val (x, y) = view.imagePoint(ViewTransform(2.5f, proxy.outputWidth / 2f, proxy.outputHeight / 2f), 100, 100, 50f, 50f)
        assertThat(x).isWithin(0.01f).of(4500f)
        assertThat(y).isWithin(0.01f).of(3000f)
    }
}
