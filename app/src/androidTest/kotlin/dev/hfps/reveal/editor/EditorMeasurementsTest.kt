package dev.hfps.reveal.editor

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.pipeline.gl.FrameStats
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measurements for ADR 0002/0004 on the real open flow: time to first image (preview), time to the editable GPU proxy
 * (first developed frame), frames while a finger drags the exposure slider in real time (two `input swipe`
 * gestures, 4 s), peak Java and native heap. Logs one line per file under `REVEAL_MEASURE`
 * (`adb logcat -d -s REVEAL_MEASURE`). Files: the public samples in `files/samples`, or, opt-in with
 * `-e private true`, the owner's copies in `files/private` (logged as `file#N` only; delete them afterwards).
 */
@RunWith(AndroidJUnit4::class)
class EditorMeasurementsTest {
    // No Compose test rule on purpose: it drives composition from a test clock, so a drag would not render live.
    @Volatile private var sampling = false

    @Before
    fun measureGpu() {
        EditorMetrics.measureGpuTime = true
    }

    @After
    fun stop() {
        EditorMetrics.measureGpuTime = false
        sampling = false
    }

    @Test
    fun measureOpenAndDrag() {
        val private = InstrumentationRegistry.getArguments().getString("private") == "true"
        val files = if (private) TestPhotos.privateFiles() else listOfNotNull(TestPhotos.sample("sony-zv-e10.arw"))
        assumeTrue("no files to measure", files.isNotEmpty())
        repeat(RUNS) { run ->
            files.forEachIndexed { index, file ->
                val label = if (private) "file#${index + 1}" else file.name
                measure(file, "$label run${run + 1}", private)
            }
        }
    }

    @Suppress("LongMethod")
    private fun measure(file: File, label: String, private: Boolean) {
        val name = if (private) "photo.ARW" else file.name
        val uri = TestPhotos.insert(name, "image/x-sony-arw", file)
        try {
            sampling = true
            val sampler = thread(name = "memory-sampler") {
                while (sampling) {
                    EditorMetrics.sampleMemory()
                    Thread.sleep(SAMPLE_MS)
                }
            }
            TestPhotos.launchView(uri, "image/x-sony-arw").use { scenario ->
                waitUntil("developed frame") { EditorMetrics.isDeveloped }
                Thread.sleep(SETTLE_MS)
                val opened = EditorMetrics.summary()
                val before = EditorMetrics.frames.size
                val renderer = checkNotNull(EditorMetrics.renderer)
                val histogramsBefore = renderer.histogramCount
                val slider = exposureSliderBounds(scenario)
                dragInRealTime(
                    slider.left + slider.width() * 0.1f,
                    slider.right - slider.width() * 0.1f,
                    (
                        slider.bottom -
                            SLIDER_INSET
                        ).toFloat(),
                )
                Thread.sleep(SETTLE_MS)
                sampling = false
                sampler.join()
                val frames = EditorMetrics.frames.toList().drop(before)
                val times = EditorMetrics.frameTimesNanos.toList().drop(before)
                assertThat(frames.size).isAtLeast(MIN_FRAMES)
                val intervals = times.zipWithNext { a, b -> (b - a) / NANOS_PER_MS }.sorted()
                val gpu = frames.map { it.gpuWallMs }.sorted()
                val coalesced = coalescedUpdates(frames)
                val line = String.format(
                    Locale.ROOT,
                    "%s %s | drag 4 s: %d frames (%d updates coalesced), interval p50 %.1f p95 %.1f max %.1f ms, " +
                        "render+swap p50 %.1f p95 %.1f ms | peakHeap %.0f MB peakNative %.0f MB",
                    label,
                    opened,
                    frames.size,
                    coalesced,
                    intervals.percentile(0.5),
                    intervals.percentile(0.95),
                    intervals.last(),
                    gpu.percentile(0.5),
                    gpu.percentile(0.95),
                    EditorMetrics.peakHeapMb,
                    EditorMetrics.peakNativeMb,
                )
                Log.i(TAG, line)
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT,
                        "%s histogram on: %d histograms during the drag, last: GL thread %.2f ms, latency %.1f ms",
                        label,
                        renderer.histogramCount - histogramsBefore,
                        renderer.histogramMs,
                        renderer.histogramLatencyMs,
                    ),
                )
            }
        } finally {
            sampling = false
            TestPhotos.delete(uri)
        }
    }

    /**
     * A finger on the slider for [DRAG_MS] in real time: two `input swipe` gestures (there and back), injected by the
     * shell so the timing is the device's, not the test clock's.
     */
    private fun dragInRealTime(fromX: Float, toX: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val half = DRAG_MS / 2
        for ((a, b) in listOf(fromX to toX, toX to fromX)) {
            val command = "input swipe ${a.toInt()} ${y.toInt()} ${b.toInt()} ${y.toInt()} $half"
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    /** Window bounds of the exposure slider, from the Compose semantics tree of the activity. */
    private fun exposureSliderBounds(scenario: ActivityScenario<MainActivity>): Rect {
        var bounds: Rect? = null
        scenario.onActivity { activity ->
            val root = checkNotNull(findRoot(activity.window.decorView)) { "no Compose root" }
            val node = checkNotNull(findTagged(root.semanticsOwner.unmergedRootSemanticsNode, EditorTags.EXPOSURE))
            val b = node.boundsInWindow
            bounds = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        }
        return checkNotNull(bounds)
    }

    private fun findTagged(node: SemanticsNode, tag: String): SemanticsNode? =
        if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) {
            node
        } else {
            node.children.firstNotNullOfOrNull { findTagged(it, tag) }
        }

    private fun findRoot(view: View): RootForTest? = when {
        view is RootForTest -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findRoot(view.getChildAt(it)) }
        else -> null
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun List<Float>.percentile(p: Double): Float = this[((size - 1) * p).toInt()]

    private companion object {
        const val TAG = "REVEAL_MEASURE"
        const val RUNS = 3
        const val DRAG_MS = 4_000L
        const val MIN_FRAMES = 30
        const val SETTLE_MS = 300L
        const val SLIDER_INSET = 40
        const val POLL_MS = 20L
        const val SAMPLE_MS = 10L
        const val TIMEOUT_MS = 60_000L
        const val NANOS_PER_MS = 1_000_000f
    }

    /** Updates the renderer coalesced: cumulative counters, not the number of polled samples (0 or more). */
    private fun coalescedUpdates(frames: List<FrameStats>): Long =
        (frames.last().updates - frames.first().updates - (frames.last().frames - frames.first().frames))
            .coerceAtLeast(0L)
}
