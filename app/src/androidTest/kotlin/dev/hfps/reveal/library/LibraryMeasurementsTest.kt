package dev.hfps.reveal.library

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.feature.library.LibraryMetrics
import java.util.Collections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library worst case (ADR 0006, "Measurements"): a single folder of 10,000 generated files (3,000 RAW+JPEG pairs
 * and 4,000 JPEGs, 7,000 photos, a sidecar on every tenth pair) behind the debug SAF provider (its own process).
 * Logs (`REVEAL_LIBRARY`): the first index (listing, saving, sidecars, headers), an unchanged re-index, the first grid
 * frame, and the frame times while flinging through the grid with thumbnails loading. Run on its own:
 * `-e class dev.hfps.reveal.library.LibraryMeasurementsTest`.
 */
@RunWith(AndroidJUnit4::class)
class LibraryMeasurementsTest {
    private val folder = SyntheticFolder("measure-10k")

    @After
    fun tearDown() = folder.delete()

    @Suppress("LongMethod") // One measurement script, read top to bottom.
    @Test
    fun tenThousandFiles() {
        val previews = (0 until PREVIEW_VARIANTS).map {
            folder.gradientJpeg(it, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_QUALITY)
        }
        val generated = System.nanoTime()
        for (i in 0 until PAIRS) {
            folder.pair("DSC%05d".format(i), i, previews[i % previews.size])
            if (i % SIDECAR_EVERY == 0) folder.sidecar("DSC%05d".format(i), rating = i % 6, creatorTool = "Hoard 1.7.2")
        }
        for (i in 0 until JPEGS) folder.jpeg("IMG_%05d.JPG".format(i), PAIRS + i, previews[i % previews.size])
        val files = folder.dir.listFiles()!!.count { !it.name.endsWith(".xmp") }
        log("generated $files files (${folder.dir.walk().sumOf { it.length() } / 1_000_000} MB) in ${ms(generated)} ms")

        val (sourceId, first) = folder.addAndIndex(timeoutMs = 600_000)
        log(
            "first index: ${first.files} files, ${first.photosAdded} photos, ${first.sidecarsRead} sidecars, " +
                "${first.headersRead} headers; list ${first.listMs} ms, save ${first.saveMs} ms, sidecars " +
                "${first.sidecarMs} ms, headers ${first.metadataMs} ms, total ${first.totalMs} ms",
        )
        val again = runBlocking {
            folder.library.index(sourceId).join()
            withTimeout(60_000) { folder.library.indexReports.first { it[sourceId] !== first } }.getValue(sourceId)
        }
        log("unchanged re-index: list ${again.listMs} ms, save ${again.saveMs} ms, total ${again.totalMs} ms")

        // No Compose test rule here: it drives frames only while it synchronizes, which would starve the real
        // touches below. The library reopens the folder it showed last, so launching lands on its grid.
        runBlocking { folder.library.setLastSelection("f:$sourceId") }
        LibraryMetrics.reset()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(20_000) { LibraryMetrics.firstGridFrameMs >= 0 }
            log("first rows ${LibraryMetrics.firstRowsMs} ms, first grid frame ${LibraryMetrics.firstGridFrameMs} ms")
            Thread.sleep(THUMBS_SETTLE_MS)
            // The grid as it looks once the first thumbnails are in (synthetic content), for the PR.
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { shot ->
                java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "library-10k.png")
                    .outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val frames = Collections.synchronizedList(mutableListOf<Long>())
            val phases = Collections.synchronizedList(mutableListOf<LongArray>())
            val missed = java.util.concurrent.atomic.AtomicInteger()
            val thread = HandlerThread("frame-metrics").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                phases += PHASES.map { metrics.getMetric(it) }.toLongArray()
                // A frame is late when it finished after the deadline the system gave it (API 31).
                if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) >
                    metrics.getMetric(FrameMetrics.DEADLINE)
                ) {
                    missed.incrementAndGet()
                }
            }
            var refreshHz = 60f
            scenario.onActivity {
                refreshHz = it.display.refreshRate
                it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            }
            // Real touches through the input system (`input swipe`), not the test rule's synchronous injection, which
            // holds the main thread and shows up as frame delay that a user never sees.
            var width = 0
            var height = 0
            scenario.onActivity {
                width = it.window.decorView.width
                height = it.window.decorView.height
            }
            val x = width / 2
            val low = (height * 0.75f).toInt()
            val high = (height * 0.3f).toInt()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            repeat(FLINGS) { i ->
                val (from, to) = if (i < FLINGS - 3) low to high else high to low
                val command = automation.executeShellCommand("input touchscreen swipe $x $from $x $to $SWIPE_MS")
                val output = ParcelFileDescriptor.AutoCloseInputStream(command).use { String(it.readBytes()) }
                if (i == 0) log("swipe $x $from -> $to: '${output.trim()}'")
                Thread.sleep(BETWEEN_FLINGS_MS)
            }
            Thread.sleep(SETTLE_MS)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            report(frames.map { it / 1_000_000.0 }.sorted(), refreshHz)
            reportPhases(phases.toList(), missed.get())
        }
    }

    private fun report(frames: List<Double>, refreshHz: Float) {
        if (frames.isEmpty()) {
            log("scroll: no frames")
            return
        }
        fun pct(p: Double) = frames[((frames.size - 1) * p).toInt()]
        val budget = 1000.0 / refreshHz
        val janky = frames.count { it > budget }
        val janky60 = frames.count { it > 1000.0 / 60 }
        log(
            "scroll: ${frames.size} frames at ${"%.0f".format(refreshHz)} Hz; p50 ${"%.1f".format(pct(0.5))} ms, p90 " +
                "${"%.1f".format(
                    pct(0.9),
                )} ms, p95 ${"%.1f".format(pct(0.95))} ms, p99 ${"%.1f".format(pct(0.99))} ms, " +
                "max ${"%.1f".format(frames.last())} ms; over ${"%.1f".format(budget)} ms: $janky " +
                "(${"%.1f".format(100.0 * janky / frames.size)} %); over 16.7 ms: $janky60 " +
                "(${"%.1f".format(100.0 * janky60 / frames.size)} %)",
        )
    }

    /** Mean and worst milliseconds of each frame phase: where a slow frame spends its time. */
    private fun reportPhases(phases: List<LongArray>, missed: Int) {
        if (phases.isEmpty()) return
        val main = phases.map { p -> MAIN_THREAD.sumOf { p[it] } / 1_000_000.0 }.sorted()
        fun pct(q: Double) = "%.1f".format(main[((main.size - 1) * q).toInt()])
        log(
            "main thread per frame: p50 ${pct(0.5)} ms, p90 ${pct(0.9)} ms, p99 ${pct(0.99)} ms, max ${pct(1.0)} ms; " +
                "frames past their deadline: $missed of ${phases.size} (${"%.1f".format(
                    100.0 * missed / phases.size,
                )} %)",
        )
        val text = PHASE_NAMES.indices.joinToString(", ") { i ->
            val values = phases.map { it[i] / 1_000_000.0 }
            "${PHASE_NAMES[i]} ${"%.1f".format(values.average())}/${"%.1f".format(values.max())}"
        }
        log("frame phases mean/max ms: $text")
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "REVEAL_LIBRARY"
        const val PAIRS = 3_000
        const val JPEGS = 4_000
        const val SIDECAR_EVERY = 10
        const val PREVIEW_VARIANTS = 24
        const val PREVIEW_WIDTH = 640
        const val PREVIEW_HEIGHT = 427
        const val PREVIEW_QUALITY = 80
        const val FLINGS = 20
        const val SETTLE_MS = 1_500L
        const val SWIPE_MS = 120
        const val THUMBS_SETTLE_MS = 2_000L
        const val POLL_MS = 20L
        const val BETWEEN_FLINGS_MS = 700L
        val PHASES = intArrayOf(
            FrameMetrics.UNKNOWN_DELAY_DURATION,
            FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION,
            FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION,
            FrameMetrics.COMMAND_ISSUE_DURATION,
            FrameMetrics.SWAP_BUFFERS_DURATION,
            FrameMetrics.GPU_DURATION,
        )
        val PHASE_NAMES = listOf("delay", "input", "animation", "layout", "draw", "sync", "issue", "swap", "gpu")

        /** Indices into PHASES of the main thread's work (input, animation incl. Compose, layout, draw). */
        val MAIN_THREAD = 1..4
    }
}
