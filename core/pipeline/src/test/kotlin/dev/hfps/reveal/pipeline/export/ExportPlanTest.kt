package dev.hfps.reveal.pipeline.export

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.LanczosResampleOp
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import org.junit.Test

class ExportPlanTest {
    @Test
    fun `sizes keep the aspect and never upscale`() {
        assertThat(ExportSize.Original.outputSize(6000, 4000)).isEqualTo(PixelSize(6000, 4000))
        assertThat(ExportSize.WEB.outputSize(6000, 4000)).isEqualTo(PixelSize(2048, 1365))
        assertThat(ExportSize.WEB.outputSize(1000, 800)).isEqualTo(PixelSize(1000, 800))
        assertThat(ExportSize.Percent(50).outputSize(6000, 4000)).isEqualTo(PixelSize(3000, 2000))
        assertThat(ExportSize.Megapixels(6f).outputSize(6000, 4000)).isEqualTo(PixelSize(3000, 2000))
        assertThat(ExportSize.LongEdge(1000).outputSize(4000, 6000)).isEqualTo(PixelSize(667, 1000))
    }

    @Test
    fun `instagram fits inside 1080 x 1350 and says when it is not 4 to 5`() {
        val box = ExportSize.INSTAGRAM_PORTRAIT as ExportSize.FitBox
        assertThat(box.outputSize(4000, 5000)).isEqualTo(PixelSize(1080, 1350))
        assertThat(box.fitsExactly(4000, 5000)).isTrue()
        // A 3:2 landscape fits by its width.
        assertThat(box.outputSize(6000, 4000)).isEqualTo(PixelSize(1080, 720))
        assertThat(box.fitsExactly(6000, 4000)).isFalse()
    }

    private fun plan(w: Int, h: Int, geometry: Geometry, size: ExportSize, tile: Int) =
        ExportPlan.of(w, h, geometry, size, tile)

    @Test
    fun `the output is the geometry operator's output, sized`() {
        val geometry = Geometry(crop = CropRect(0.1f, 0.25f, 0.6f, 1f), rotate = 1)
        val turned = plan(6000, 4000, geometry, ExportSize.Original, 2048)
        assertThat(turned.geometrySize).isEqualTo(PixelSize(3000, 3000))
        assertThat(plan(6000, 4000, Geometry(rotate = 1), ExportSize.WEB, 2048).output).isEqualTo(PixelSize(1365, 2048))
        assertThat(plan(6000, 4000, Geometry(), ExportSize.Original, 2048).transforms).isFalse()
    }

    @Test
    fun `prepass tiles are cell aligned and cover the image once`() {
        val plan = plan(6024, 4024, Geometry(), ExportSize.Original, 2048)
        val tiles = plan.prepassTiles(24)
        var covered = 0L
        for (t in tiles) {
            assertThat(t.x % 24).isEqualTo(0)
            assertThat(t.width % 24 == 0 || t.right == 6024).isTrue()
            assertThat(t.width).isAtMost(2048)
            covered += t.width.toLong() * t.height
        }
        assertThat(covered).isEqualTo(6024L * 4024)
    }

    @Test
    fun `full size output tiles are their own input`() {
        val tiles = plan(5000, 3000, Geometry(), ExportSize.Original, 2048).outputTiles()
        assertThat(tiles).hasSize(3 * 2)
        tiles.forEach {
            assertThat(it.input).isEqualTo(it.output)
            assertThat(it.geometry).isEqualTo(it.output)
        }
    }

    @Test
    fun `resampled tiles read every tap inside a geometry box that fits the tile`() {
        val plan = plan(6024, 4024, Geometry(crop = CropRect(0.02f, 0.01f, 0.98f, 0.99f)), ExportSize.WEB, 1024)
        assertThat(plan.resamples).isTrue()
        var outPixels = 0L
        for (t in plan.outputTiles()) {
            assertThat(checkNotNull(t.input).width).isAtMost(1024)
            assertThat(checkNotNull(t.input).height).isAtMost(1024)
            val first = LanczosResampleOp.tapRange(t.output.x, 0, plan.scaleX).first.coerceAtLeast(0)
            val last = LanczosResampleOp.tapRange(t.output.right - 1, 0, plan.scaleX).last
                .coerceAtMost(plan.geometrySize.width - 1)
            assertThat(t.geometry.x).isAtMost(first)
            assertThat(t.geometry.right - 1).isAtLeast(last)
            outPixels += t.output.width.toLong() * t.output.height
        }
        assertThat(outPixels).isEqualTo(plan.output.pixels)
    }

    @Test
    fun `straightened tiles get an input region that fits and covers the mapped box`() {
        val geometry = Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f), angle = 31.7f, rotate = 3, flipH = true)
        val plan = plan(6024, 4024, geometry, ExportSize.Original, 1024)
        val mapping = GeometryMapping.of(geometry, 6024, 4024)
        for (t in plan.outputTiles()) {
            val input = checkNotNull(t.input)
            assertThat(input.width).isAtMost(1024)
            assertThat(input.height).isAtMost(1024)
            // Every corner of the box maps inside the region (the filter margin is on top).
            for ((x, y) in listOf(t.geometry.x to t.geometry.y, t.geometry.right to t.geometry.bottom)) {
                val p = mapping.outputToImage(x.toDouble(), y.toDouble())
                if (p.x in 0.0..6024.0 && p.y in 0.0..4024.0) {
                    assertThat(p.x).isAtLeast(input.x.toDouble() - 1)
                    assertThat(p.x).isAtMost(input.right.toDouble() + 1)
                }
            }
        }
    }

    @Test
    fun `the detail apron grows every input region and shrinks the tiles`() {
        val plain = ExportPlan.of(1100, 740, Geometry(), ExportSize.Original, 256)
        val apron = ExportPlan.of(1100, 740, Geometry(), ExportSize.Original, 256, reach = 72)
        assertThat(plain.geometryPass).isFalse()
        assertThat(apron.geometryPass).isTrue()
        assertThat(apron.geometryBudget).isEqualTo(256 - 2 * 72)
        for (tile in apron.outputTiles()) {
            val box = tile.geometry
            val input = checkNotNull(tile.input)
            assertThat(input.width).isAtMost(256)
            assertThat(input.height).isAtMost(256)
            // At least the apron around the box, unless the image edge clamps it.
            assertThat(box.x - input.x).isEqualTo(minOf(72, box.x))
            assertThat(input.right - box.right).isEqualTo(minOf(72, 1100 - box.right))
        }
    }
}
