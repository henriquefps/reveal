package dev.hfps.reveal.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.DetailParams
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.export.PixelBox
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.input.BitmapTileSource
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.zoom.ZoomGrid
import dev.hfps.reveal.pipeline.zoom.ZoomView
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-resolution zoom in the real editor (docs/adr/0012): a 12 MP synthetic JPEG with detail finer than the proxy can
 * hold (gratings of a few pixels, seeded noise), Detail on, double-tap to 100 %: the tiles appear and what the window
 * shows matches the CPU reference of the full-resolution image pixel for pixel, across the tile edges that cross the
 * middle of the view (no seam, no proxy). A second test measures the zoom on the CC0 ZV-E10 ARW (`REVEAL_BENCH`) when
 * it is in `files/samples`. Locale-independent: test tags only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorZoomTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun hundredPercentShowsFullResolutionTilesThatMatchTheCpu() {
        val jpeg = fineJpeg()
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-zoom.jpg"), "image/jpeg", jpeg)
        try {
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                waitUntil("full resolution") { EditorMetrics.fullResolutionMs >= 0 }
                val renderer = checkNotNull(EditorMetrics.renderer)
                for ((id, value) in EDIT) runOnMain { checkNotNull(EditorMetrics.setControl)(id, value) }
                compose.onNodeWithTag(EditorTags.CANVAS).performTouchInput { doubleClick(center) }
                compose.waitForIdle()
                val stats = runBlocking { withTimeout(TIMEOUT_MS) { renderer.awaitZoom(ViewTransform(), 1, 1) } }
                Log.i(TAG, "zoom: $stats")
                assertThat(stats.wanted).isGreaterThan(0)
                assertThat(stats.shown).isEqualTo(stats.wanted)
                val shown = runBlocking { renderer.renderShown(OutputSettings(dither = false)) }
                val view = ZoomView(
                    shown.pixelScale,
                    shown.proxyOutputWidth,
                    shown.proxyOutputHeight,
                    GeometryMapping.identity(WIDTH, HEIGHT),
                )
                assertThat(view.fullScale(shown.view.scale)).isEqualTo(1f)
                val recipe = checkNotNull(EditorMetrics.recipes)().first
                compare(shown, view, recipe, jpeg)
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    /** The middle [AREA] x [AREA] window pixels of the frame against the CPU reference of the same image pixels. */
    private fun compare(
        shown: dev.hfps.reveal.pipeline.gl.ShownView,
        view: ZoomView,
        recipe: dev.hfps.reveal.pipeline.recipe.Recipe,
        jpeg: ByteArray,
    ) {
        val x0 = (shown.frame.centerX - AREA / 2).toInt()
        val y0 = (shown.frame.centerY - AREA / 2).toInt()
        fun imagePixel(x: Int, y: Int): Pair<Int, Int> {
            val (ix, iy) = view.imagePoint(shown.view, shown.width, shown.height, x + HALF, y + HALF)
            return floor(ix).toInt() to floor(iy).toInt()
        }
        val (ix0, iy0) = imagePixel(x0, y0)
        // The view crosses tile edges (a seam would show there).
        val (ix1, iy1) = imagePixel(x0 + AREA - 1, y0 + AREA - 1)
        assertThat(ix0 / ZoomGrid.TILE).isLessThan(ix1 / ZoomGrid.TILE)
        assertThat(iy0 / ZoomGrid.TILE).isLessThan(iy1 / ZoomGrid.TILE)
        // CPU reference of that region with the Detail apron (only global and Detail operators: exact on a crop).
        val apron = DetailParams.from(recipe.detail, 1f).reach + 2
        val region = ExportPlan.grow(PixelBox(ix0, iy0, ix1 - ix0 + 1, iy1 - iy0 + 1), apron, WIDTH, HEIGHT)
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val source = BitmapTileSource(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options))
        val rgba = FloatArray(region.width * region.height * 4)
        source.read(region.x, region.y, region.width, region.height, rgba)
        val rgb = FloatArray(region.width * region.height * 3) { rgba[it / 3 * 4 + it % 3] }
        val input = PipelineInput(
            InputKind.DISPLAY_REFERRED,
            region.width,
            region.height,
            rgb,
            source.colour.cameraToXyz,
            null,
        )
        source.close()
        val cpu = CpuDevelop.render(input, recipe, OutputSettings(dither = false))
        var worst = 0
        var sum = 0L
        for (y in 0 until AREA) {
            for (x in 0 until AREA) {
                val (ix, iy) = imagePixel(x0 + x, y0 + y)
                val s = ((y0 + y) * shown.width + x0 + x) * 4
                val c = ((iy - region.y) * region.width + ix - region.x) * 4
                for (k in 0 until 3) {
                    val d = abs((shown.rgba[s + k].toInt() and BYTE) - (cpu[c + k].toInt() and BYTE))
                    worst = maxOf(worst, d)
                    sum += d
                }
            }
        }
        val mean = sum.toDouble() / (AREA * AREA * 3)
        Log.i(TAG, "100 % in the editor vs CPU: max $worst levels, mean $mean (image $ix0,$iy0 .. $ix1,$iy1)")
        assertThat(worst).isAtMost(MAX_LEVELS)
        assertThat(mean).isAtMost(MEAN_LEVELS)
    }

    /**
     * The orchestrator's phone measurement: the CC0 ZV-E10 ARW (`files/samples/sony-zv-e10.arw`), double-tap to 100 %,
     * logs the full-resolution decode, the grids, the tile times and the time until the view is sharp.
     */
    @Test
    fun zoomMeasurementsOnTheSampleRaw() {
        val sample = TestPhotos.sample(SAMPLE)
        assumeTrue("$SAMPLE not in files/samples", sample != null)
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-zoom.arw"), "image/x-sony-arw", checkNotNull(sample))
        try {
            TestPhotos.launchView(uri, "image/x-sony-arw").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                waitUntil("full resolution") { EditorMetrics.fullResolutionMs >= 0 }
                val renderer = checkNotNull(EditorMetrics.renderer)
                Thread.sleep(PREFETCH_MS)
                compose.onNodeWithTag(EditorTags.CANVAS).performTouchInput { doubleClick(center) }
                compose.waitForIdle()
                val stats = runBlocking { withTimeout(TIMEOUT_MS) { renderer.awaitZoom(ViewTransform(), 1, 1) } }
                Log.i(BENCH, "zoom on $SAMPLE: open ${EditorMetrics.summary()}")
                Log.i(
                    BENCH,
                    "zoom on $SAMPLE: full resolution ready at ${EditorMetrics.fullResolutionMs} ms; " +
                        "${stats.shown} tiles, mean ${stats.meanTileMs} ms per tile, sharp ${stats.lastSettleMs} ms " +
                        "after the zoom; grids ${stats.lastPrepareMs} ms",
                )
                assertThat(stats.shown).isGreaterThan(0)
                // An edit while zoomed: the proxy shows at once, the tiles come back when the edit settles.
                runOnMain { checkNotNull(EditorMetrics.setControl)(ControlId.NR_LUMINANCE, NR_AFTER) }
                // Let a frame pick the edit up (the tiles are then stale until the new ones are developed).
                Thread.sleep(EDIT_FRAME_MS)
                val after = runBlocking { withTimeout(TIMEOUT_MS) { renderer.awaitZoom(ViewTransform(), 1, 1) } }
                Log.i(BENCH, "zoom on $SAMPLE after a Detail edit: sharp ${after.lastSettleMs} ms after the edit")
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    private fun runOnMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TAG = "REVEAL_EDITOR_TEST"
        const val BENCH = "REVEAL_BENCH"
        const val SAMPLE = "sony-zv-e10.arw"
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L
        const val PREFETCH_MS = 1_500L
        const val WIDTH = 4000
        const val HEIGHT = 3000
        const val AREA = 320
        const val HALF = 0.5f
        const val BYTE = 0xff
        const val MAX_LEVELS = 4
        const val MEAN_LEVELS = 0.3
        const val NR_AFTER = 30f
        const val EDIT_FRAME_MS = 50L
        val EDIT = listOf(
            ControlId.EXPOSURE to 0.3f,
            ControlId.CONTRAST to 15f,
            ControlId.NR_LUMINANCE to 40f,
            ControlId.NR_COLOUR to 40f,
            ControlId.SHARPEN_AMOUNT to 60f,
            ControlId.SHARPEN_MASKING to 10f,
        )

        /** A 4000 x 3000 JPEG (quality 100): colour gratings with 3-7 px periods and seeded noise. */
        @Suppress("MagicNumber")
        fun fineJpeg(): ByteArray {
            val random = Random(7)
            val pixels = IntArray(WIDTH * HEIGHT)
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val r = 128 + 60 * sin(x * 2.1) + random.nextInt(-20, 21)
                    val g = 128 + 60 * sin(y * 1.3 + x * 0.4) + random.nextInt(-20, 21)
                    val b = 110 + 50 * sin((x + y) * 0.9) + random.nextInt(-20, 21)
                    pixels[y * WIDTH + x] = Color.rgb(
                        r.toInt().coerceIn(0, 255),
                        g.toInt().coerceIn(0, 255),
                        b.toInt().coerceIn(0, 255),
                    )
                }
            }
            val bitmap = Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
        }
    }
}
