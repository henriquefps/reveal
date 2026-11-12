package dev.hfps.reveal.editor

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The presets panel's live previews while scrolling (docs/adr/0019 "Previews"): 60 presets in one group on the open
 * photo (the CC0 ZV-E10 sample in `files/samples` when present, else a synthetic JPEG), the panel expanded, then real
 * touches (`input swipe`) scroll the grid up and down while the thumbnails render. Logs (`REVEAL_PRESETS`) the frame
 * times (FrameMetrics); the thumbnail render times are logged per thumbnail under the same tag. Run on its own:
 * `-e class dev.hfps.reveal.editor.PresetsMeasurementsTest` (excluded from the full suite like the other measurements).
 */
@RunWith(AndroidJUnit4::class)
class PresetsMeasurementsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)

    @Suppress("LongMethod") // One measurement script, read top to bottom.
    @Test
    fun scrollingSixtyPreviews() {
        val presets = access.presets()
        val groupId = runBlocking { presets.createGroup("Measure ${System.nanoTime()}") }
        runBlocking {
            repeat(PRESETS) { i ->
                val recipe = Recipe(
                    profile = Profile(BaseLook.entries[i % BaseLook.entries.size]),
                    light = Light(contrast = (i % 9 - 4) * 10f, exposureEv = (i % 5 - 2) * 0.2f),
                    colour = Colour(vibrance = (i % 7) * 8f, saturation = (i % 3 - 1) * 15f),
                    effects = Effects(clarity = (i % 4) * 10f, vignetteAmount = -(i % 6) * 8f),
                )
                presets.create("Look $i", PresetPatch.fromSections(recipe, PresetSection.entries), groupId)
            }
        }
        // First in the panel (it opens on the first group with presets).
        runBlocking {
            val others = presets.library().first().groups.mapNotNull { it.rowId }.filter { it != groupId }
            presets.reorderGroups(listOf(groupId) + others)
        }
        val sample = TestPhotos.sample("sony-zv-e10.arw")
        val uri = if (sample != null) {
            TestPhotos.insert("reveal-measure-${System.nanoTime()}.arw", "image/x-sony-arw", sample)
        } else {
            TestPhotos.insert("reveal-measure-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        }
        try {
            TestPhotos.launchView(uri, if (sample != null) "image/x-sony-arw" else "image/jpeg").use { scenario ->
                waitFor(TIMEOUT_MS) { EditorMetrics.renderer != null }
                Thread.sleep(SETTLE_MS)
                // No Compose test rule (it holds frames while it synchronizes): the editor's measurement seams.
                checkNotNull(EditorMetrics.selectTool).invoke(ToolId.PRESETS)
                waitFor(TIMEOUT_MS) { EditorMetrics.presetGrid != null }
                Thread.sleep(SETTLE_MS)
                val g = checkNotNull(EditorMetrics.presetGrid)
                val bounds = Rect(g[0], g[1], g[2], g[3])
                val x = bounds.centerX()
                val top = (bounds.top + bounds.height() * EDGE).toInt()
                val bottom = (bounds.bottom - bounds.height() * EDGE).toInt()
                log("grid ${bounds.width()}x${bounds.height()} at y ${bounds.top}, $PRESETS presets")
                // `-e baseline true`: the same swipes on the Light panel (a plain scrolling column), for comparison.
                val baseline = InstrumentationRegistry.getArguments().getString("baseline") == "true"
                if (baseline) {
                    checkNotNull(EditorMetrics.selectTool).invoke(ToolId.LIGHT)
                    Thread.sleep(SETTLE_MS)
                    log("baseline: scrolling the Light panel instead")
                }

                val frames = Collections.synchronizedList(mutableListOf<Long>())
                val parts = Collections.synchronizedList(mutableListOf<LongArray>())
                val missed = AtomicInteger()
                val thread = HandlerThread("frame-metrics").apply { start() }
                val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    parts += PARTS.map { metrics.getMetric(it) }.toLongArray()
                    if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > metrics.getMetric(FrameMetrics.DEADLINE)) {
                        missed.incrementAndGet()
                    }
                }
                var refreshHz = 60f
                scenario.onActivity {
                    refreshHz = it.display.refreshRate
                    it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                }
                // Without the accessibility connection: a connected UiAutomation makes Compose compute and send its
                // whole semantics tree every frame (as with TalkBack on), which is not what the owner's phone does.
                val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
                repeat(SWIPES) { i ->
                    val (from, to) = if (i < SWIPES / 2) bottom to top else top to bottom
                    val command = automation.executeShellCommand("input touchscreen swipe $x $from $x $to $SWIPE_MS")
                    ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
                    Thread.sleep(BETWEEN_MS)
                }
                Thread.sleep(SETTLE_MS)
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                thread.quitSafely()
                report(frames.map { it / NS_PER_MS }.sorted(), refreshHz, missed.get())
                // Where the slow frames spend their time (p90 of each stage over all frames).
                val names = listOf("input", "animation", "layout", "draw", "sync", "commands", "swap", "gpu")
                log(
                    "stages p90 ms: " + names.indices.joinToString { i ->
                        val v = parts.map { it[i] / NS_PER_MS }.sorted()
                        "${names[i]} ${"%.1f".format(v[((v.size - 1) * P90).toInt()])}"
                    },
                )
            }
        } finally {
            TestPhotos.delete(uri)
            runBlocking { presets.deleteGroup(groupId) }
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun report(frames: List<Double>, refreshHz: Float, missed: Int) {
        if (frames.isEmpty()) {
            log("presets scroll: no frames")
            return
        }
        fun pct(p: Double) = "%.1f".format(frames[((frames.size - 1) * p).toInt()])
        val budget = MS_PER_S / refreshHz
        val janky = frames.count { it > budget }
        log(
            "presets scroll: ${frames.size} frames at ${"%.0f".format(refreshHz)} Hz; p50 ${pct(P50)} ms, " +
                "p90 ${pct(P90)} ms, p95 ${pct(P95)} ms, p99 ${pct(P99)} ms, max ${pct(1.0)} ms; over " +
                "${"%.1f".format(budget)} ms: $janky (${"%.1f".format(PCT * janky / frames.size)} %); " +
                "past deadline $missed (${"%.1f".format(PCT * missed / frames.size)} %)",
        )
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        val PARTS = intArrayOf(
            FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION,
            FrameMetrics.DRAW_DURATION,
            FrameMetrics.SYNC_DURATION,
            FrameMetrics.COMMAND_ISSUE_DURATION,
            FrameMetrics.SWAP_BUFFERS_DURATION,
            FrameMetrics.GPU_DURATION,
        ).toList()
        const val TAG = "REVEAL_PRESETS"
        const val PRESETS = 60
        const val SWIPES = 20
        const val SWIPE_MS = 250
        const val BETWEEN_MS = 600L
        const val SETTLE_MS = 1_500L
        const val TIMEOUT_MS = 60_000L
        const val EDGE = 0.15f
        const val POLL_MS = 100L
        const val NS_PER_MS = 1_000_000.0
        const val MS_PER_S = 1000.0
        const val PCT = 100.0
        const val P50 = 0.5
        const val P90 = 0.9
        const val P95 = 0.95
        const val P99 = 0.99
    }
}
