package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.LanczosResampleOp
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.cpu.OutputTransformOp
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.RenderCancelledException
import dev.hfps.reveal.pipeline.gl.TileSink
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.input.PipelineInputTileSource
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.Treatment
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
    fun colourToolsExportLikeThePreview() = runBlocking {
        // Tone curve, mixer and grading (docs/adr/0013), then black & white: the export binds the Tools block too.
        val tools = recipe.copy(
            light = recipe.light.copy(curve = Curves(rgb = Curves.STRONG_CONTRAST, red = Curves.MEDIUM_CONTRAST)),
            colour = Colour(
                mixer = ColourMixer(hue = BandValues(red = 40f), luminance = BandValues(blue = -50f)),
                grading = ColourGrading(shadows = GradeWheel(220f, 40f), highlights = GradeWheel(50f, 30f, 10f)),
            ),
        )
        val bw = tools.copy(
            profile = Profile(treatment = Treatment.BLACK_WHITE),
            colour = tools.colour.copy(greyMixer = BandValues(red = 50f, blue = -50f)),
        )
        val preview = GpuDevelopRenderer.create()
        try {
            preview.upload(image)
            for (r in listOf(tools, bw)) {
                val expected = preview.renderOutput(r)
                val actual = render(plan(256), with = r)
                val diff = maxDiff(expected, actual)
                Log.i(TAG, "colour tools, tiled vs preview renderer: max ${diff.first} levels, ${diff.second} differ")
                assertThat(diff.first).isAtMost(1)
                assertThat(actual.contentEquals(render(plan(256)))).isFalse()
            }
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
     * Optics and Transform (docs/adr/0015): a warped geometry (lens distortion + CA + the Transform + straightening),
     * vignetting and defringe through the tiles: they stitch bit-identically (the regions come from the warp's
     * `sourceRegion`, defringe's radius is in the apron), the export equals the preview renderer and the CPU reference.
     */
    @Test
    fun opticsAndTransformExportLikeThePreview() = runBlocking {
        val lensImage = image.withLens(GpuParityTest.PARITY_LENS)
        val edit = recipe.copy(
            optics = Optics(lensCorrections = true, removeCa = true, purpleAmount = 10f),
            geometry = Geometry(
                angle = 2.5f,
                transform = Transform(vertical = 28f, horizontal = -10f, rotate = 1.5f, scale = 105f),
            ),
        )
        val constrained = edit.copy(
            geometry = CropEditing.transform(
                Geometry(angle = 2.5f),
                edit.geometry.transform,
                image.width,
                image.height,
            ),
        )
        val reach = DevelopParams.reach(constrained)
        fun warpedPlan(tile: Int) = ExportPlan.of(
            image.width,
            image.height,
            constrained.geometry,
            ExportSize.Original,
            tile,
            reach,
            GpuParityTest.PARITY_LENS,
            constrained.optics,
        )
        fun renderLens(plan: ExportPlan): ByteArray {
            val sink = ArraySink(plan.output.width, plan.output.height)
            tiled.render(PipelineInputTileSource(lensImage), constrained, plan, OutputSettings(), sink)
            return sink.pixels
        }
        val small = warpedPlan(256)
        assertThat(small.mapping.warp).isNotNull()
        assertThat(small.outputTiles().size).isGreaterThan(9)
        val whole = renderLens(warpedPlan(1024))
        assertThat(renderLens(small).contentEquals(whole)).isTrue()
        val preview = GpuDevelopRenderer.create()
        try {
            preview.upload(lensImage)
            val vsPreview = maxDiff(preview.renderOutput(constrained), whole)
            val vsCpu = maxDiff(CpuDevelop.render(lensImage, constrained), whole)
            Log.i(TAG, "optics + transform: vs preview $vsPreview, vs CPU $vsCpu (max levels, values differing)")
            assertThat(vsPreview.first).isAtMost(1)
            assertThat(vsCpu.first).isAtMost(4)
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
        val reach = DevelopParams.reach(edit)
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
            render(
                detailPlan(256, straight),
                with = turned,
            ).contentEquals(render(detailPlan(1024, straight), with = turned)),
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

    /**
     * Effects (docs/adr/0014): texture's apron and the clarity/dehaze grid pre-pass make tiles stitch bit-identically,
     * also straightened and resized (vignette and grain at the export's own size); the export equals the preview
     * renderer and stays within the Effects tolerance of the CPU reference.
     */
    @Test
    fun effectsTilesStitchAndMatchThePreviewAndTheCpu() = runBlocking {
        // With a tone curve: its table shares the develop stage's units with nothing of slots 11-12 (docs/adr/0014).
        val edit = recipe.copy(
            light = recipe.light.copy(curve = Curves(rgb = Curves.MEDIUM_CONTRAST)),
            detail = Detail(nrColour = 40f),
            effects = Effects(
                texture = 60f,
                clarity = 50f,
                dehaze = 30f,
                vignetteAmount = -50f,
                grainAmount = 40f,
                grainSize = 40f,
            ),
        )
        val reach = DevelopParams.reach(edit)
        fun effectsPlan(tile: Int, geometry: Geometry = Geometry(), size: ExportSize = ExportSize.Original) =
            ExportPlan.of(image.width, image.height, geometry, size, tile, reach)
        val whole = render(effectsPlan(1024), with = edit)
        assertThat(render(effectsPlan(256), with = edit).contentEquals(whole)).isTrue()
        val straight = Geometry(crop = CropRect(0.1f, 0.15f, 0.85f, 0.9f), angle = -4.2f)
        val turned = edit.copy(geometry = straight)
        assertThat(
            render(effectsPlan(256, straight), with = turned)
                .contentEquals(render(effectsPlan(1024, straight), with = turned)),
        ).isTrue()
        val small = ExportSize.LongEdge(640)
        assertThat(
            render(effectsPlan(256, size = small), with = edit)
                .contentEquals(render(effectsPlan(1024, size = small), with = edit)),
        ).isTrue()
        val preview = GpuDevelopRenderer.create()
        try {
            preview.upload(image)
            val vsPreview = maxDiff(preview.renderOutput(edit), whole)
            val vsCpu = maxDiff(CpuDevelop.render(image, edit), whole)
            Log.i(TAG, "effects: vs preview $vsPreview, vs CPU $vsCpu (max levels, values differing)")
            assertThat(vsPreview.first).isAtMost(1)
            assertThat(vsCpu.first).isAtMost(8)
        } finally {
            preview.close()
        }
    }

    /**
     * Masks (docs/adr/0022): five masks of every type (brush, ranges, the fake AI source) export in tiles exactly like
     * one tile (no seam: coverage is pointwise, the brush is rasterised per tile from the same dabs), like the preview
     * renderer and within Detail's 8-bit tolerance of the CPU reference.
     */
    @Test
    fun maskedTilesStitchAndMatchThePreviewAndTheCpu() = runBlocking {
        // The five masks and a brush with M4/B's options (an auto-mask stroke: its dab colours come from a pre-pass).
        val edit = MaskFixtures.fiveMasks().let { it.copy(masks = it.masks + MaskFixtures.brushOptions) }
            .copy(light = recipe.light, detail = Detail(sharpenAmount = 30f))
        val reach = DevelopParams.reach(edit)
        assertThat(reach).isGreaterThan(0)
        fun maskPlan(tile: Int, geometry: Geometry = Geometry()) =
            ExportPlan.of(image.width, image.height, geometry, ExportSize.Original, tile, reach)
        fun renderMasked(plan: ExportPlan, with: Recipe = edit): ByteArray {
            val sink = ArraySink(plan.output.width, plan.output.height)
            tiled.render(
                PipelineInputTileSource(image),
                with,
                plan,
                OutputSettings(),
                sink,
                masks = MaskFixtures.context,
            )
            return sink.pixels
        }
        val whole = renderMasked(maskPlan(1024))
        val tiles = renderMasked(maskPlan(256))
        Log.i(TAG, "masks: 256 px tiles vs one tile ${maxDiff(tiles, whole)}")
        assertThat(tiles.contentEquals(whole)).isTrue()
        val straight = Geometry(crop = CropRect(0.05f, 0.1f, 0.9f, 0.95f), angle = 3.1f)
        val turned = edit.copy(geometry = straight)
        assertThat(
            renderMasked(maskPlan(256, straight), turned).contentEquals(renderMasked(maskPlan(1024, straight), turned)),
        ).isTrue()
        val preview = GpuDevelopRenderer.create()
        try {
            preview.upload(image)
            preview.setMaskSource(MaskFixtures.source, "fixture")
            val vsPreview = maxDiff(preview.renderOutput(edit), whole)
            val vsCpu = maxDiff(CpuDevelop.render(image, edit, masks = MaskFixtures.context), whole)
            Log.i(TAG, "masks: vs preview $vsPreview, vs CPU $vsCpu (max levels, values differing)")
            assertThat(vsPreview.first).isAtMost(1)
            assertThat(vsCpu.first).isAtMost(8)
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
