package dev.hfps.reveal.editor

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Choreographer
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
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.gl.FrameStats
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
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

    /**
     * Each frame ends with `glFinish` so its render+swap time is the GPU's (`-e gpu false` keeps the frames
     * pipelined as in the app: the intervals are then the display rate, and render+swap is the CPU's submission).
     */
    @Before
    fun measureGpu() {
        EditorMetrics.measureGpuTime = InstrumentationRegistry.getArguments().getString("gpu") != "false"
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

    @Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
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
                val renderer = checkNotNull(EditorMetrics.renderer)
                // `-e histogram false` measures the drag without the live histogram (default: on, as in the app).
                val args = InstrumentationRegistry.getArguments()
                val histogram = args.getString("histogram") != "false"
                renderer.histogramEnabled = histogram
                // `-e light true`: the Light sliders are non-neutral (local tone on) while dragging.
                if (args.getString("light") == "true") {
                    val set = checkNotNull(EditorMetrics.setControl)
                    LIGHT_EDIT.forEach { (id, value) -> set(id, value) }
                    Thread.sleep(SETTLE_MS)
                }
                // `-e all true`: every tool on (Light, colour tools, Detail, Effects: docs/adr/0012, "M2/P").
                if (args.getString("all") == "true") {
                    val set = checkNotNull(EditorMetrics.setControl)
                    (LIGHT_EDIT + ALL_EDIT).forEach { (id, value) -> set(id, value) }
                    Thread.sleep(SETTLE_MS)
                }
                // `-e drag <control>`: the slider dragged (default exposure), its panel opened first.
                // `-e profile true`: per-pass times.
                val dragged = args.getString("drag")?.let { ControlId.valueOf(it.uppercase()) } ?: ControlId.EXPOSURE
                PANELS.entries.firstOrNull { dragged in it.value }?.let { (tool, _) ->
                    tap(scenario, EditorTags.tool(tool))
                    Thread.sleep(PANEL_MS)
                }
                val profile = args.getString("profile") == "true"
                renderer.profilePasses = profile
                val histogramsBefore = renderer.histogramCount
                // `-e vsync true`: the value changes on every display frame (a perfectly smooth finger, set on the UI
                // thread's Choreographer like a slider's drag), so the intervals measure the renderer, not the cadence
                // of the injected touch events; otherwise a finger on the slider (`input swipe`). On a phone with an
                // adaptive refresh rate the display may slow down without a touch: compare like with like.
                val vsync = args.getString("vsync") == "true"
                val slider = if (vsync) null else visibleSlider(scenario, EditorTags.control(dragged))
                // Frames from the start of the drag (opening and scrolling the panel draw frames too).
                Thread.sleep(SETTLE_MS)
                val before = EditorMetrics.frames.size
                if (slider == null) {
                    dragEveryVsync(dragged)
                } else {
                    dragInRealTime(
                        slider.left + slider.width() * 0.1f,
                        slider.right - slider.width() * 0.1f,
                        (slider.bottom - SLIDER_INSET).toFloat(),
                    )
                }
                Thread.sleep(SETTLE_MS)
                sampling = false
                sampler.join()
                if (profile) {
                    val passes = runBlocking { renderer.passProfile() }
                    val cpu = runBlocking { renderer.passProfileCpu() }
                    renderer.profilePasses = false
                    val text = passes.entries.joinToString { (k, v) ->
                        String.format(Locale.ROOT, "%s %.2f (GL thread %.2f)", k, v, cpu[k] ?: 0f)
                    }
                    val how = if (renderer.passProfileOnGpu) "GPU timer queries" else "glFinish each"
                    Log.i(TAG, "$label drag ${dragged.name.lowercase()} passes (ms, $how): $text")
                }
                val frames = EditorMetrics.frames.toList().drop(before)
                val times = EditorMetrics.frameTimesNanos.toList().drop(before)
                assertThat(frames.size).isAtLeast(MIN_FRAMES)
                val intervals = times.zipWithNext { a, b -> (b - a) / NANOS_PER_MS }.sorted()
                // The frame rate while the finger moves: intervals under GAP_MS (the pause between the two gestures and
                // any idle time excluded), and the share of frames on time for 120 Hz (one vsync, 8.33 ms + jitter).
                val moving = intervals.filter { it < GAP_MS }
                val fps = if (moving.isEmpty()) 0f else MS_PER_S * moving.size / moving.sum()
                val onTime = moving.count { it <= ON_TIME_MS } * PERCENT / moving.size.coerceAtLeast(1)
                val gpu = frames.map { it.gpuWallMs }.sorted()
                val coalesced = coalescedUpdates(frames)
                val line = String.format(
                    Locale.ROOT,
                    "%s %s | drag 4 s: %d frames (%d updates coalesced), %.0f fps while moving, %.0f %% on time, " +
                        "interval p50 %.1f p95 %.1f max %.1f ms, " +
                        "render+swap p50 %.1f p95 %.1f ms | peakHeap %.0f MB peakNative %.0f MB",
                    label,
                    opened,
                    frames.size,
                    coalesced,
                    fps,
                    onTime,
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
                        "%s histogram %s: %d histograms during the drag, last: GL thread %.2f ms, latency %.1f ms",
                        label,
                        if (histogram) "on" else "off",
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

    /**
     * Taps the centre of the node tagged [tag] (a shell `input tap`, as a finger would), first swiping the tool strip
     * until the node is on screen (the strip scrolls).
     */
    private fun tap(scenario: ActivityScenario<MainActivity>, tag: String) {
        var width = 0
        scenario.onActivity { width = it.window.decorView.width }
        repeat(STRIP_SWIPES) {
            // Stable bounds: a swipe's fling may still be scrolling the strip.
            var b = sliderBounds(scenario, tag)
            while (true) {
                Thread.sleep(POLL_MS * 5)
                val again = sliderBounds(scenario, tag)
                if (again == b) break
                b = again
            }
            if (!b.isEmpty && b.centerX() in 1 until width - 1) {
                shell("input tap ${b.centerX()} ${b.centerY()}")
                return
            }
            val strip = sliderBounds(scenario, EditorTags.TOOL_STRIP)
            // Off-screen nodes have empty (clipped) bounds: the strip's tools run left to right, scroll it forward.
            val (from, to) = if (b.isEmpty ||
                b.centerX() >= width
            ) {
                width * 3 / 4 to width / 4
            } else {
                width / 4 to width * 3 / 4
            }
            shell("input swipe $from ${strip.centerY()} $to ${strip.centerY()} $SWIPE_MS")
            Thread.sleep(SETTLE_MS)
        }
        error("$tag is not on screen")
    }

    /**
     * The window bounds of the slider tagged [tag] once it is laid out and fully above the tool strip, scrolling the
     * panel up (a finger swipe above the strip) while it is not.
     */
    private fun visibleSlider(scenario: ActivityScenario<MainActivity>, tag: String): Rect {
        repeat(STRIP_SWIPES * 2) {
            val strip = sliderBounds(scenario, EditorTags.TOOL_STRIP)
            val b = runCatching { sliderBounds(scenario, tag) }.getOrNull()
            if (b != null && !b.isEmpty && b.bottom < strip.top - SLIDER_INSET) return b
            val x = strip.centerX()
            shell("input swipe $x ${strip.top - PANEL_SWIPE / 4} $x ${strip.top - PANEL_SWIPE} $SWIPE_MS")
            Thread.sleep(PANEL_MS)
        }
        error("$tag is not on screen")
    }

    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    /**
     * The value of [id] moved across its range and back over [DRAG_MS], one step per display frame from the main
     * thread's Choreographer (as a slider's drag delivers it).
     */
    private fun dragEveryVsync(id: ControlId) {
        val set = checkNotNull(EditorMetrics.setControl)
        val range = RecipeRanges[id]
        val done = java.util.concurrent.CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val start = System.nanoTime()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val t = (frameTimeNanos - start) / (DRAG_MS * NANOS_PER_MS)
                    if (t >= 1f) {
                        done.countDown()
                        return
                    }
                    val x = if (t < 0.5f) 2f * t else 2f - 2f * t
                    set(id, range.min + (range.max - range.min) * (0.1f + 0.8f * x))
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
        }
        done.await()
    }

    /** Window bounds of the node tagged [tag], from the Compose semantics tree of the activity. */
    private fun sliderBounds(scenario: ActivityScenario<MainActivity>, tag: String): Rect {
        var bounds: Rect? = null
        scenario.onActivity { activity ->
            val root = checkNotNull(findRoot(activity.window.decorView)) { "no Compose root" }
            val node = checkNotNull(findTagged(root.semanticsOwner.unmergedRootSemanticsNode, tag))
            val b = node.boundsInWindow
            bounds = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        }
        return checkNotNull(bounds)
    }

    /** The laid-out node tagged [tag] (the largest: a tool can also exist unplaced, e.g. in a hidden layout). */
    private fun findTagged(node: SemanticsNode, tag: String): SemanticsNode? {
        val found = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) found += n
            n.children.forEach(::walk)
        }
        walk(node)
        return found.maxByOrNull { it.boundsInWindow.width * it.boundsInWindow.height }
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
        /** A typical Light edit: every tone slider away from neutral, so the local (guided-filter) path runs. */
        val LIGHT_EDIT: List<Pair<ControlId, Float>> = listOf(
            ControlId.CONTRAST to 25f,
            ControlId.HIGHLIGHTS to -50f,
            ControlId.SHADOWS to 40f,
            ControlId.WHITES to 15f,
            ControlId.BLACKS to -10f,
        )

        /** Every colour, Detail and Effects tool on (with [LIGHT_EDIT]): what `-e all true` sets before the drag. */
        val ALL_EDIT: List<Pair<ControlId, Float>> = listOf(
            ControlId.VIBRANCE to 15f,
            ControlId.CURVE_HIGHLIGHTS to -10f,
            ControlId.HUE_ORANGE to 10f,
            ControlId.SAT_BLUE to 20f,
            ControlId.GRADE_SHADOWS_SAT to 15f,
            ControlId.GRADE_HIGHLIGHTS_SAT to 10f,
            ControlId.NR_LUMINANCE to 40f,
            ControlId.NR_COLOUR to 50f,
            ControlId.SHARPEN_AMOUNT to 50f,
            ControlId.SHARPEN_MASKING to 30f,
            ControlId.TEXTURE to 30f,
            ControlId.CLARITY to 25f,
            ControlId.DEHAZE to 20f,
            ControlId.VIGNETTE_AMOUNT to -30f,
            ControlId.GRAIN_AMOUNT to 25f,
        )

        /** The panel holding each dragged slider other than the Light panel's (opened by a tap on its tool). */
        val PANELS: Map<ToolId, Set<ControlId>> = mapOf(
            ToolId.DETAIL to setOf(
                ControlId.SHARPEN_AMOUNT, ControlId.SHARPEN_RADIUS, ControlId.SHARPEN_DETAIL, ControlId.SHARPEN_MASKING,
                ControlId.NR_LUMINANCE, ControlId.NR_LUMINANCE_DETAIL, ControlId.NR_LUMINANCE_CONTRAST,
                ControlId.NR_COLOUR, ControlId.NR_COLOUR_DETAIL, ControlId.NR_COLOUR_SMOOTHNESS,
            ),
            ToolId.EFFECTS to setOf(
                ControlId.TEXTURE,
                ControlId.CLARITY,
                ControlId.DEHAZE,
                ControlId.VIGNETTE_AMOUNT,
                ControlId.GRAIN_AMOUNT,
            ),
        )
        const val PANEL_MS = 800L
        const val GAP_MS = 100f
        const val ON_TIME_MS = 9f
        const val MS_PER_S = 1000f
        const val PERCENT = 100f
        const val STRIP_SWIPES = 4
        const val SWIPE_MS = 300L
        const val PANEL_SWIPE = 600
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
