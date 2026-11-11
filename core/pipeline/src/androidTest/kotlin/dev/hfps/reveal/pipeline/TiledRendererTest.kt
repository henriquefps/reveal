package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.DetailParams
import dev.hfps.reveal.pipeline.cpu.LanczosResampleOp
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.cpu.OutputTransformOp
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.RenderCancelledException
import dev.hfps.reveal.pipeline.gl.TileSink
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.input.PipelineInputTileSource
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tiled export renderer (docs/adr/0009): tiles stitch bit-identically to an untiled render, the untiled render
 * equals the preview renderer's readback, and the resampled export matches the CPU reference. Numbers under
 * `REVEAL_EXPORT`.
 */
@RunWith(AndroidJUnit4::class)
class TiledRendererTest {
    private lateinit var tiled: TiledRenderer

    @Before
    fun setUp() {
        tiled = TiledRenderer.create(1024)
    }

    @After
    fun tearDown() {
        tiled.close()
    }

    /** Clipped coloured highlights (reconstruction) and every Light slider (guide): both grid pre-passes run. */
    private val image = SyntheticImages.clippedHighlights(1100, 740)
    private val recipe = Recipe(
        light = Light(0.3f, contrast = 25f, highlights = -60f, shadows = 45f, whites = 10f, blacks = -10f),
    )

    private fun render(
        plan: ExportPlan,
        settings: OutputSettings = OutputSettings(),
        with: Recipe = recipe,
    ): ByteArray {
        val sink = ArraySink(plan.output.width, plan.output.height)
        tiled.render(PipelineInputTileSource(image), with, plan, settings, sink)
        return sink.pixels
    }

    private fun plan(tile: Int, size: ExportSize = ExportSize.Original, geometry: Geometry = Geometry()) =
        ExportPlan.of(image.width, image.height, geometry, size, tile)

    @Test
    fun tilesStitchBitIdenticallyToAnUntiledRender() {
        val whole = render(plan(1024))
        val tiles = render(plan(256))
        val p3 = OutputSettings(OutputSpace.DISPLAY_P3)
        assertThat(plan(256).outputTiles().size).isGreaterThan(9)
        assertThat(tiles.contentEquals(whole)).isTrue()
        assertThat(render(plan(256), p3).contentEquals(render(plan(1024), p3))).isTrue()
    }

    @Test
    fun untiledRenderEqualsThePreviewRenderer() = runBlocking {
        val preview = GpuDevelopRenderer.create()
        try {
            preview.upload(image)
            val expected = preview.renderOutput(recipe)
            val actual = render(plan(256))
            val diff = maxDiff(expected, actual)
            Log.i(TAG, "tiled vs preview renderer: max ${diff.first} levels, ${diff.second} values differ")
            assertThat(diff.first).isAtMost(1)
        } finally {
            preview.close()
        }
    }

    @Test
    fun resampledTilesStitchAndMatchTheCpuReference() {
        val crop = Geometry(crop = CropRect(37f / 1100, 21f / 740, 1037f / 1100, 721f / 740))
        val size = ExportSize.LongEdge(333)
        val whole = render(plan(1024, size, crop))
        val tiled = plan(256, size, crop)
        val tiles = render(tiled)
        assertThat(tiled.outputTiles().size).isGreaterThan(1)
        assertThat(tiles.contentEquals(whole)).isTrue()
        val g = tiled.geometrySize
        val out = tiled.output
        val cropped = CpuDevelop.renderGeometry(image, Recipe(light = recipe.light, geometry = crop))
        val small = LanczosResampleOp.resample(
            cropped,
            g.width,
            g.height,
            0,
            0,
            g.width,
            g.height,
            out.width,
            out.height,
        )
        val cpu = OutputTransformOp.apply(small, out.width, out.height)
        val diff = maxDiff(cpu, tiles)
        val mean = meanDiff(cpu, tiles)
        Log.i(TAG, "resampled GPU vs CPU: max ${diff.first} levels, mean $mean")
        assertThat(diff.first).isAtMost(3)
        assertThat(mean).isAtMost(0.1)
    }

    /** Crop + straighten + quarter turn + flip, unconstrained (empty corners): tiles, preview and CPU agree. */
    @Test
    fun geometryTilesStitchAndMatchThePreviewAndTheCpu() = runBlocking {
        val geometries = listOf(
            Geometry(crop = CropRect(0.05f, 0f, 0.6f, 0.75f), rotate = 1, flipH = true),
            Geometry(crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f), angle = 7.3f),
            Geometry(angle = -31.7f, rotate = 1, constrain = false),
        )
        val preview = GpuDevelopRenderer.create()
        try {
            preview.upload(image)
            for (geometry in geometries) {
                val edit = recipe.copy(geometry = geometry)
                val tiles = render(plan(256, geometry = geometry), with = edit)
                assertThat(tiles.contentEquals(render(plan(1024, geometry = geometry), with = edit))).isTrue()
                val vsPreview = maxDiff(preview.renderOutput(edit), tiles)
                val vsCpu = maxDiff(CpuDevelop.render(image, edit), tiles)
                Log.i(TAG, "geometry $geometry: vs preview $vsPreview, vs CPU $vsCpu (max levels, values differing)")
                assertThat(vsPreview.first).isAtMost(1)
                assertThat(vsCpu.first).isAtMost(3)
            }
        } finally {
            preview.close()
        }
    }

    /**
     * Detail (slot 11, docs/adr/0012): with its apron the tiles stitch bit-identically (with and without geometry), the
     * export equals the preview renderer and stays within the Detail tolerance of the CPU reference.
     */
    @Test
    fun detailTilesStitchAndMatchThePreviewAndTheCpu() = runBlocking {
        val edit = recipe.copy(
            detail = Detail(sharpenAmount = 80f, sharpenMasking = 25f, nrLuminance = 50f, nrColour = 60f),
        )
        val reach = DetailParams.from(edit.detail, 1f).reach
        assertThat(reach).isGreaterThan(0)
        fun detailPlan(tile: Int, geometry: Geometry = Geometry()) =
            ExportPlan.of(image.width, image.height, geometry, ExportSize.Original, tile, reach)
        val whole = render(detailPlan(1024), with = edit)
        val tiles = render(detailPlan(256), with = edit)
        assertThat(detailPlan(256).outputTiles().size).isGreaterThan(9)
        assertThat(tiles.contentEquals(whole)).isTrue()
        val straight = Geometry(crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f), angle = 7.3f)
        val turned = edit.copy(geometry = straight)
        assertThat(
            render(detailPlan(256, straight), with = turned).contentEquals(render(detailPlan(1024, straight), with = turned)),
        ).isTrue()
        val preview = GpuDevelopRenderer.create()
        try {
            preview.upload(image)
            val vsPreview = maxDiff(preview.renderOutput(edit), whole)
            val vsCpu = maxDiff(CpuDevelop.render(image, edit), whole)
            Log.i(TAG, "detail: vs preview $vsPreview, vs CPU $vsCpu (max levels, values differing)")
            assertThat(vsPreview.first).isAtMost(1)
            assertThat(vsCpu.first).isAtMost(4)
        } finally {
            preview.close()
        }
    }

    @Test
    fun highDepthOutputQuantisesToTheEightBitOutput() {
        val plan = plan(512)
        val eight = render(plan, OutputSettings(dither = false))
        val sink = ArraySink(image.width, image.height)
        tiled.render(PipelineInputTileSource(image), recipe, plan, OutputSettings(), sink, high = true)
        var worst = 0
        for (i in eight.indices) {
            if (i % 4 == 3) continue
            val v = (sink.high[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            worst = maxOf(worst, abs(v - (eight[i].toInt() and 0xff)))
        }
        assertThat(worst).isAtMost(0)
    }

    @Test
    fun cancellationStopsBetweenTiles() {
        var calls = 0
        assertThrows(RenderCancelledException::class.java) {
            tiled.render(
                PipelineInputTileSource(image),
                recipe,
                plan(256),
                OutputSettings(),
                ArraySink(image.width, image.height),
                cancel = { ++calls > 3 },
            )
        }
    }

    /** Collects the bands into one top-first RGBA array. */
    private class ArraySink(private val width: Int, height: Int) : TileSink {
        val pixels = ByteArray(width * height * 4)
        val high = FloatArray(width * height * 4)

        override fun accept(x: Int, y: Int, w: Int, h: Int, rgba: ByteBuffer) {
            for (r in 0 until h) {
                rgba.position(r * w * 4)
                rgba.get(pixels, ((y + r) * width + x) * 4, w * 4)
            }
        }

        override fun acceptHigh(x: Int, y: Int, w: Int, h: Int, rgba: FloatBuffer) {
            for (r in 0 until h) {
                rgba.position(r * w * 4)
                rgba.get(high, ((y + r) * width + x) * 4, w * 4)
            }
        }
    }

    private fun maxDiff(a: ByteArray, b: ByteArray): Pair<Int, Int> {
        var worst = 0
        var count = 0
        for (i in a.indices) {
            val d = abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
            if (d > 0) count++
            worst = maxOf(worst, d)
        }
        return worst to count
    }

    private fun meanDiff(a: ByteArray, b: ByteArray): Double {
        var sum = 0L
        for (i in a.indices) if (i % 4 != 3) sum += abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
        return (sum * 4.0 / 3.0 / a.size * 100).roundToInt() / 100.0
    }

    private companion object {
        const val TAG = "REVEAL_EXPORT"
    }
}
