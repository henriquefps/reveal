package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.TileSink
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.input.PipelineInputTileSource
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import dev.hfps.reveal.pipeline.zoom.ZoomGrid
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's full-resolution zoom (docs/adr/0012): at 100 % the view shows developed full-resolution tiles that match
 * the CPU reference of the whole full-resolution image (no seam where tiles meet) and are bit-identical to the export
 * of the same pixels. A synthetic "photo" of 1200 x 800 with a 2x smaller proxy; tiles of 512 px, so a pane centred on
 * full-resolution pixel (512, 512) shows four tiles meeting there. Numbers under `REVEAL_ZOOM`.
 */
@RunWith(AndroidJUnit4::class)
class ZoomTilesTest {
    private lateinit var renderer: GpuDevelopRenderer

    private val full = SyntheticImages.clippedHighlights(FULL_W, FULL_H)
    private val proxy = halve(full)

    @Before
    fun setUp(): Unit = runBlocking {
        renderer = GpuDevelopRenderer.create()
        renderer.upload(proxy, K)
        check(renderer.setFullResolution(PipelineInputTileSource(full), K))
    }

    @After
    fun tearDown() {
        renderer.close()
    }

    private val edit = Recipe(
        light = Light(0.3f, contrast = 20f, highlights = -50f, shadows = 40f),
        detail = Detail(sharpenAmount = 70f, sharpenMasking = 20f, nrLuminance = 45f, nrColour = 50f),
    )

    @Test
    fun hundredPercentShowsTilesThatMatchTheCpuAndTheExport() = runBlocking {
        renderer.setRecipe(edit)
        // 100 %: the editor's scale is k screen pixels per proxy pixel; proxy output point (256, 256) is full
        // (512, 512).
        val view = ViewTransform(K, 256f, 256f)
        val stats = withTimeout(TIMEOUT_MS) { renderer.awaitZoom(view, PANE_W, PANE_H) }
        Log.i(TAG, "100 %: $stats")
        assertThat(stats.shown).isEqualTo(4)
        val shot = renderer.renderView(view, PANE_W, PANE_H, OutputSettings(dither = false))
        // Screen pixel (x, y) shows full-resolution pixel (x + 512 - PANE_W / 2, y + 512 - PANE_H / 2).
        val x0 = ZoomGrid.TILE - PANE_W / 2
        val y0 = ZoomGrid.TILE - PANE_H / 2
        val cpu = CpuDevelop.render(full, edit, OutputSettings(dither = false))
        var worst = 0
        var worstSeam = 0
        var sum = 0L
        var signed = 0L
        for (y in 0 until PANE_H) {
            for (x in 0 until PANE_W) {
                for (c in 0 until 3) {
                    val a = shot[(y * PANE_W + x) * 4 + c].toInt() and 0xff
                    val b = cpu[((y + y0) * FULL_W + x + x0) * 4 + c].toInt() and 0xff
                    val d = abs(a - b)
                    signed += a - b
                    worst = maxOf(worst, d)
                    sum += d
                    // The columns and rows next to the tiles' common edges.
                    if (abs(x + x0 - ZoomGrid.TILE) <= 1 || abs(y + y0 - ZoomGrid.TILE) <= 1) {
                        worstSeam = maxOf(worstSeam, d)
                    }
                }
            }
        }
        val mean = sum.toDouble() / (PANE_W * PANE_H * 3)
        Log.i(
            TAG,
            "100 % vs CPU: max $worst levels (at the tile edges $worstSeam), mean $mean, " +
                "signed mean ${signed.toDouble() / (PANE_W * PANE_H * 3)}",
        )
        assertThat(worst).isAtMost(MAX_LEVELS)
        assertThat(worstSeam).isAtMost(MAX_LEVELS)
        assertThat(mean).isAtMost(MEAN_LEVELS)
        // The export renders the same pixels with the same code: bit-identical.
        val export = exportFull(edit)
        var differ = 0
        for (y in 0 until PANE_H) {
            for (x in 0 until PANE_W) {
                for (c in 0 until 3) {
                    val a = shot[(y * PANE_W + x) * 4 + c]
                    val b = export[((y + y0) * FULL_W + x + x0) * 4 + c]
                    if (a != b) differ++
                }
            }
        }
        Log.i(TAG, "100 % vs export: $differ values differ")
        assertThat(differ).isEqualTo(0)
    }

    /**
     * Masks at 100 % (docs/adr/0022): the tiles develop the masks at full resolution (brush, ranges, the fake AI
     * source), match the CPU reference of the full image and are bit-identical to the export of the same pixels.
     */
    @Test
    fun maskedTilesMatchTheCpuAndTheExport() = runBlocking {
        renderer.setMaskSource(MaskFixtures.source, "fixture")
        val masked = MaskFixtures.fiveMasks().copy(light = edit.light, detail = edit.detail)
        renderer.setRecipe(masked)
        val view = ViewTransform(K, 256f, 256f)
        val stats = withTimeout(TIMEOUT_MS) { renderer.awaitZoom(view, PANE_W, PANE_H) }
        assertThat(stats.shown).isEqualTo(4)
        val shot = renderer.renderView(view, PANE_W, PANE_H, OutputSettings(dither = false))
        val x0 = ZoomGrid.TILE - PANE_W / 2
        val y0 = ZoomGrid.TILE - PANE_H / 2
        val cpu = CpuDevelop.render(full, masked, OutputSettings(dither = false), masks = MaskFixtures.context)
        val export = exportFull(masked)
        var worst = 0
        var sum = 0L
        var differ = 0
        for (y in 0 until PANE_H) {
            for (x in 0 until PANE_W) {
                for (c in 0 until 3) {
                    val a = shot[(y * PANE_W + x) * 4 + c].toInt() and 0xff
                    val i = ((y + y0) * FULL_W + x + x0) * 4 + c
                    val d = abs(a - (cpu[i].toInt() and 0xff))
                    worst = maxOf(worst, d)
                    sum += d
                    if (shot[(y * PANE_W + x) * 4 + c] != export[i]) differ++
                }
            }
        }
        val mean = sum.toDouble() / (PANE_W * PANE_H * 3)
        Log.i(TAG, "masks 100 % vs CPU: max $worst levels, mean $mean; vs export $differ values differ")
        assertThat(worst).isAtMost(MAX_LEVELS)
        assertThat(mean).isAtMost(MEAN_LEVELS)
        assertThat(differ).isEqualTo(0)
    }

    /**
     * The worst 100 % view (docs/adr/0012 "Pixel alignment at 1:1"): screen pixel centres exactly on full-resolution
     * pixel edges, where `floor` would follow the GPU's last-bit rounding and duplicate or skip rows (the Tab S9
     * Ultra's Adreno 740 did after a double-tap). The renderer aligns the view: the pane is the export shifted by one
     * constant offset, every value identical.
     */
    @Test
    fun aHundredPercentViewOnPixelEdgesShowsEveryPixelOnce() = runBlocking {
        // The editor's ratio on the Tab S9 Ultra (a 4000 px photo, a 2960 px proxy): not a power of two, so the
        // shader's float maths round differently from row to row. The proxy only has to have that size.
        val odd = GpuDevelopRenderer.create()
        try {
            val k = FULL_W / ODD_PROXY_W.toFloat()
            odd.upload(pointSample(full, ODD_PROXY_W, (FULL_H / k).toInt()), k)
            check(odd.setFullResolution(PipelineInputTileSource(full), k))
            odd.setRecipe(edit)
            // Centred on full pixel (512, 400) plus half a pixel: window pixel centres fall on full pixel edges.
            val edge = 0.5f / k
            val view = ViewTransform(
                k,
                (FULL_W / k) / 2f + (ZoomGrid.TILE - FULL_W / 2f) / k + edge,
                (FULL_H / k) / 2f + edge,
            )
            withTimeout(TIMEOUT_MS) { odd.awaitZoom(view, PANE_W, PANE_H) }
            val shot = odd.renderView(view, PANE_W, PANE_H, OutputSettings(dither = false))
            val export = exportFull(edit)
            // The pane at one constant offset of the export (which of the two pixels a centre on an edge picks is
            // the alignment's choice; what must not happen is a different choice on some rows or columns).
            val cx = ZoomGrid.TILE - PANE_W / 2
            val cy = FULL_H / 2 - PANE_H / 2
            val differ = (-1..1).flatMap { dx -> (-1..1).map { dy -> dx to dy } }.associateWith { (dx, dy) ->
                var n = 0
                for (y in 0 until PANE_H) {
                    for (x in 0 until PANE_W) {
                        for (c in 0 until 3) {
                            val e = export[((y + cy + dy) * FULL_W + x + cx + dx) * 4 + c]
                            if (shot[(y * PANE_W + x) * 4 + c] != e) n++
                        }
                    }
                }
                n
            }
            Log.i(TAG, "100 % on pixel edges (k $k) vs export by offset: $differ")
            assertThat(differ.values.min()).isEqualTo(0)
        } finally {
            odd.close()
        }
    }

    @Test
    fun straightenedHundredPercentMatchesTheExport() = runBlocking {
        val geometry = Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f), angle = 4.2f)
        val turned = edit.copy(geometry = geometry)
        renderer.setRecipe(turned)
        // The middle of the output, which is the middle of both images.
        val proxyOut = CpuDevelop.mapping(proxy, turned)
        val view = ViewTransform(K, proxyOut.outputWidth / 2f, proxyOut.outputHeight / 2f)
        val stats = withTimeout(TIMEOUT_MS) { renderer.awaitZoom(view, PANE_W, PANE_H) }
        assertThat(stats.shown).isAtLeast(1)
        val shot = renderer.renderView(view, PANE_W, PANE_H, OutputSettings(dither = false))
        val fullOut = CpuDevelop.mapping(full, turned)
        val export = exportFull(turned)
        val x0 = fullOut.outputWidth / 2 - PANE_W / 2
        val y0 = fullOut.outputHeight / 2 - PANE_H / 2
        var worst = 0
        for (y in 0 until PANE_H) {
            for (x in 0 until PANE_W) {
                for (c in 0 until 3) {
                    val a = shot[(y * PANE_W + x) * 4 + c].toInt() and 0xff
                    val b = export[((y + y0) * fullOut.outputWidth + x + x0) * 4 + c].toInt() and 0xff
                    worst = maxOf(worst, abs(a - b))
                }
            }
        }
        Log.i(TAG, "straightened 100 % vs export: max $worst levels")
        assertThat(worst).isAtMost(1)
    }

    /** A crop changes no pixel of a tile: the tiles stay and are drawn through the new geometry at once. */
    @Test
    fun aCropKeepsTheTilesAndMovesThem() = runBlocking {
        renderer.setRecipe(edit)
        val before = withTimeout(TIMEOUT_MS) { renderer.awaitZoom(ViewTransform(K, 256f, 256f), PANE_W, PANE_H) }
        val cropped = edit.copy(geometry = Geometry(crop = CropRect(0.25f, 0.25f, 1f, 1f), rotate = 1))
        renderer.setRecipe(cropped)
        val proxyOut = CpuDevelop.mapping(proxy, cropped)
        val view = ViewTransform(K, proxyOut.outputWidth / 2f, proxyOut.outputHeight / 2f)
        val after = withTimeout(TIMEOUT_MS) { renderer.awaitZoom(view, PANE_W, PANE_H) }
        val shot = renderer.renderView(view, PANE_W, PANE_H, OutputSettings(dither = false))
        val fullOut = CpuDevelop.mapping(full, cropped)
        val export = exportFull(cropped)
        val x0 = fullOut.outputWidth / 2 - PANE_W / 2
        val y0 = fullOut.outputHeight / 2 - PANE_H / 2
        var differ = 0
        for (y in 0 until PANE_H) {
            for (x in 0 until PANE_W) {
                for (c in 0 until 3) {
                    if (shot[(y * PANE_W + x) * 4 + c] != export[((y + y0) * fullOut.outputWidth + x + x0) * 4 + c]) {
                        differ++
                    }
                }
            }
        }
        Log.i(
            TAG,
            "crop after the tiles: ${after.tiles - before.tiles} new tiles, $differ values differ from the export",
        )
        assertThat(differ).isEqualTo(0)
    }

    @Test
    fun tilesBelowHundredPercentAgreeWithTheProxyAndWaitForTheEditToSettle() = runBlocking {
        renderer.setRecipe(edit)
        val view = ViewTransform(K * 0.7f, 300f, 200f)
        val stats = withTimeout(TIMEOUT_MS) { renderer.awaitZoom(view, PANE_W, PANE_H) }
        assertThat(stats.shown).isAtLeast(1)
        val tiles = renderer.renderView(view, PANE_W, PANE_H, OutputSettings(dither = false))
        // A new edit: the tiles of the old one are not shown, the proxy is.
        val next = edit.copy(light = edit.light.copy(exposureEv = 0.31f))
        renderer.setRecipe(next)
        val proxyOnly = renderer.renderView(view, PANE_W, PANE_H, OutputSettings(dither = false))
        var mean = 0.0
        for (i in tiles.indices) {
            if (i % 4 !=
                3
            ) {
                mean += abs((tiles[i].toInt() and 0xff) - (proxyOnly[i].toInt() and 0xff))
            }
        }
        mean /= PANE_W * PANE_H * 3
        Log.i(TAG, "70 % tiles vs proxy: mean $mean levels")
        // Same tone and colour, more detail: the mean difference stays small.
        assertThat(mean).isAtMost(PROXY_MEAN_LEVELS)
        assertThat(mean).isGreaterThan(0.0)
    }

    private fun exportFull(recipe: Recipe): ByteArray {
        val tiled = TiledRenderer.create(1024)
        try {
            val reach = DevelopParams.reach(recipe)
            val plan = ExportPlan.of(FULL_W, FULL_H, recipe.geometry, ExportSize.Original, 1024, reach)
            val width = plan.output.width
            val pixels = ByteArray(width * plan.output.height * 4)
            tiled.render(
                PipelineInputTileSource(full),
                recipe,
                plan,
                OutputSettings(dither = false),
                object : TileSink {
                    override fun accept(x: Int, y: Int, w: Int, h: Int, rgba: ByteBuffer) {
                        for (r in 0 until h) {
                            rgba.position(r * w * 4)
                            rgba.get(pixels, ((y + r) * width + x) * 4, w * 4)
                        }
                    }
                },
                masks = MaskFixtures.context,
            )
            return pixels
        } finally {
            tiled.close()
        }
    }

    private companion object {
        const val TAG = "REVEAL_ZOOM"
        const val FULL_W = 1200
        const val FULL_H = 800
        const val K = 2f
        const val PANE_W = 400
        const val PANE_H = 300
        const val TIMEOUT_MS = 60_000L
        const val MAX_LEVELS = 4
        const val MEAN_LEVELS = 0.15
        const val PROXY_MEAN_LEVELS = 3.0
        const val ODD_PROXY_W = 888

        /** A [width] x [height] nearest-pixel copy of [image] (a proxy of any size; its content does not matter). */
        fun pointSample(image: PipelineInput, width: Int, height: Int): PipelineInput {
            val out = FloatArray(width * height * 3)
            for (y in 0 until height) {
                val sy = (y * image.height / height).coerceAtMost(image.height - 1)
                for (x in 0 until width) {
                    val sx = (x * image.width / width).coerceAtMost(image.width - 1)
                    for (c in 0 until 3) {
                        out[(y * width + x) * 3 + c] = image.linearRgb[(sy * image.width + sx) * 3 + c]
                    }
                }
            }
            return PipelineInput(
                image.kind,
                width,
                height,
                out,
                image.cameraToXyz,
                image.asShotMultipliers,
                image.saturation,
            )
        }

        /** The proxy: 2x2 means of the full-resolution image. */
        @Suppress("NestedBlockDepth")
        fun halve(image: PipelineInput): PipelineInput {
            val w = image.width / 2
            val h = image.height / 2
            val out = FloatArray(w * h * 3)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    for (c in 0 until 3) {
                        var s = 0f
                        for (dy in 0..1) {
                            for (dx in 0..1) {
                                s +=
                                    image.linearRgb[((2 * y + dy) * image.width + 2 * x + dx) * 3 + c]
                            }
                        }
                        out[(y * w + x) * 3 + c] = s / 4f
                    }
                }
            }
            return PipelineInput(image.kind, w, h, out, image.cameraToXyz, image.asShotMultipliers, image.saturation)
        }
    }
}
