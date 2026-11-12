package dev.hfps.reveal.editor

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.editor.EditorTags
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
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PresetsMeasurementsTest {
    @get:org.junit.Rule
    val compose = createEmptyComposeRule()

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
        val group = runBlocking { presets.library().first() }.groups.first { it.rowId == groupId }
        val sample = TestPhotos.sample("sony-zv-e10.arw")
        val uri = if (sample != null) {
            TestPhotos.insert("reveal-measure-${System.nanoTime()}.arw", "image/x-sony-arw", sample)
        } else {
            TestPhotos.insert("reveal-measure-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        }
        try {
            TestPhotos.launchView(uri, if (sample != null) "image/x-sony-arw" else "image/jpeg").use { scenario ->
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                click(EditorTags.tool(ToolId.PRESETS))
                click(EditorTags.presetChip(group.uuid))
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PRESET_GRID), TIMEOUT_MS)
                // Expand the portrait panel (drag its header up) so the grid has room to scroll.
                val header = compose.onNodeWithTag(EditorTags.PANEL_HEADER).fetchSemanticsNode().boundsInWindow
                val hx = header.center.x.toInt()
                val hy = header.center.y.toInt()
                val drag = instrumentation.uiAutomation.executeShellCommand(
                    "input touchscreen swipe $hx $hy $hx ${(hy * HEADER_TARGET).toInt()} $SWIPE_MS",
                )
                ParcelFileDescriptor.AutoCloseInputStream(drag).use { it.readBytes() }
                Thread.sleep(SETTLE_MS)
                compose.waitForIdle()
                val bounds = compose.onNodeWithTag(EditorTags.PRESET_GRID).fetchSemanticsNode().boundsInWindow
                val x = bounds.center.x.toInt()
                val top = (bounds.top + bounds.height * EDGE).toInt()
                val bottom = (bounds.bottom - bounds.height * EDGE).toInt()
                log("grid ${bounds.width.toInt()}x${bounds.height.toInt()} at y ${bounds.top.toInt()}, $PRESETS presets")

                val frames = Collections.synchronizedList(mutableListOf<Long>())
                val missed = AtomicInteger()
                val thread = HandlerThread("frame-metrics").apply { start() }
                val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > metrics.getMetric(FrameMetrics.DEADLINE)) {
                        missed.incrementAndGet()
                    }
                }
                var refreshHz = 60f
                scenario.onActivity {
                    refreshHz = it.display.refreshRate
                    it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                }
                val automation = instrumentation.uiAutomation
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
            }
        } finally {
            TestPhotos.delete(uri)
            runBlocking { presets.deleteGroup(groupId) }
        }
    }

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
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
        const val TAG = "REVEAL_PRESETS"
        const val PRESETS = 60
        const val SWIPES = 20
        const val SWIPE_MS = 250
        const val BETWEEN_MS = 600L
        const val SETTLE_MS = 1_500L
        const val TIMEOUT_MS = 60_000L
        const val EDGE = 0.15f
        const val HEADER_TARGET = 0.3f
        const val NS_PER_MS = 1_000_000.0
        const val MS_PER_S = 1000.0
        const val PCT = 100.0
        const val P50 = 0.5
        const val P90 = 0.9
        const val P95 = 0.95
        const val P99 = 0.99
    }
}
