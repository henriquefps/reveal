package dev.hfps.reveal.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.designsystem.components.slider.NumericInputTags
import dev.hfps.reveal.designsystem.components.slider.RevealSliderTags
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M1 editor on the real open flow (a synthetic JPEG through `ACTION_VIEW`, so it runs on any emulator): the
 * slider's relative drag, double-tap reset and numeric entry change the recipe (its value label) and the rendered
 * frame (screen pixels of the photo), the histogram updates, the clipping overlay shows, and holding ◐ shows the
 * original. Locale-independent: test tags and `ValueFormat` labels only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorInteractionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val exposure get() = compose.onNodeWithTag(EditorTags.EXPOSURE)

    /** The exposure value label (`ValueFormat`, locale-independent): the first part of the row's state description. */
    private val exposureValue: String
        get() = exposure.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
            .orEmpty()
            .substringBefore(", ")

    @Test
    fun sliderDragDoubleTapAndNumericEntryDriveTheRecipeAndTheFrame() = withPhoto {
        val base = photoLuma()
        assertThat(exposureValue).isEqualTo("0.00")

        // A tap at the far right does not move the value: no jump to the finger.
        exposure.performTouchInput { click(Offset(width * 0.95f, height * 0.7f)) }
        compose.waitForIdle()
        assertThat(exposureValue).isEqualTo("0.00")

        // Relative drag: a quarter of the row to the right, started on the left half.
        exposure.performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.7f), Offset(width * 0.45f, height * 0.7f), 400)
        }
        val dragged = exposureValue
        assertThat(dragged).startsWith("+")
        val brighter = settledLuma()
        Log.i(TAG, "drag: $dragged luma $base -> $brighter")
        assertThat(brighter).isGreaterThan(base + 5.0)

        // Double-tap resets.
        exposure.performTouchInput { doubleClick(Offset(width * 0.5f, height * 0.5f)) }
        compose.waitForIdle()
        assertThat(exposureValue).isEqualTo("0.00")
        assertThat(abs(settledLuma() - base)).isLessThan(2.0)

        // Tap the value: numeric input; a comma decimal works whatever the locale.
        compose.onNodeWithTag(
            EditorTags.EXPOSURE + RevealSliderTags.VALUE_SUFFIX,
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
        compose.onNodeWithTag(NumericInputTags.FIELD).performTextReplacement("-1,5")
        compose.onNodeWithTag(NumericInputTags.DONE).performClick()
        compose.waitForIdle()
        assertThat(exposureValue).isEqualTo("−1.50")
        assertThat(settledLuma()).isLessThan(base - 5.0)
    }

    @Test
    fun histogramUpdatesAndClippingOverlayShows() = withPhoto {
        val renderer = checkNotNull(EditorMetrics.renderer)
        waitUntil("first histogram") { renderer.histogramCount > 0 }
        compose.onNodeWithTag(EditorTags.HISTOGRAM).assertExistsCompat()
        val before = renderer.histogramCount
        typeExposure("+2.5")
        waitUntil("histogram after the edit") { renderer.histogramCount > before }
        Log.i(
            TAG,
            "histogram: GL thread ${renderer.histogramMs} ms (request ${renderer.histogramRequestMs}), latency ${renderer.histogramLatencyMs} ms, " +
                "${renderer.histogramCount} so far",
        )

        assertThat(countPixels(ClippingWarnings.HIGHLIGHT_RGB8)).isEqualTo(0)
        compose.onNodeWithTag(EditorTags.HISTOGRAM_HIGHLIGHTS).performClick()
        compose.waitForIdle()
        val red = settled { countPixels(ClippingWarnings.HIGHLIGHT_RGB8) }
        Log.i(TAG, "highlight overlay pixels: $red")
        assertThat(red).isGreaterThan(MIN_OVERLAY_PIXELS)
        compose.onNodeWithTag(EditorTags.HISTOGRAM_HIGHLIGHTS).performClick()
        compose.waitForIdle()
        assertThat(settled { countPixels(ClippingWarnings.HIGHLIGHT_RGB8) }).isEqualTo(0)
    }

    @Test
    fun holdingBeforeAfterShowsTheOriginal() = withPhoto {
        val original = photoLuma()
        typeExposure("+1.5")
        val edited = settledLuma()
        assertThat(edited).isGreaterThan(original + 5.0)

        val compare = compose.onNodeWithTag(EditorTags.COMPARE)
        compare.performTouchInput { down(center) }
        // Past a tap, short of the long press: a peek (the Compose clock does not follow real sleeps).
        compose.mainClock.advanceTimeBy(HOLD_MS)
        compose.waitForIdle()
        val held = settledLuma()
        compare.performTouchInput { up() }
        compose.waitForIdle()
        val released = settledLuma()
        Log.i(TAG, "hold: original $original edited $edited held $held released $released")
        assertThat(abs(held - original)).isLessThan(2.0)
        assertThat(abs(released - edited)).isLessThan(2.0)
    }

    // ------------------------------------------------------------------ helpers

    private fun withPhoto(block: () -> Unit) {
        val uri = TestPhotos.insert("reveal-card.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                compose.waitForIdle()
                Thread.sleep(SETTLE_MS)
                block()
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    private fun typeExposure(text: String) {
        compose.onNodeWithTag(
            EditorTags.EXPOSURE + RevealSliderTags.VALUE_SUFFIX,
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
        compose.onNodeWithTag(NumericInputTags.FIELD).performTextReplacement(text)
        compose.onNodeWithTag(NumericInputTags.DONE).performClick()
        compose.waitForIdle()
    }

    /** Window bounds of the photo slot. */
    private fun canvasBounds(): Rect {
        val b = compose.onNodeWithTag(EditorTags.CANVAS).fetchSemanticsNode().boundsInWindow
        return Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
    }

    private fun screenshot(): Bitmap =
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())

    /** Mean luma of the middle of the photo slot (where the photo is). */
    private fun photoLuma(): Double {
        val shot = screenshot()
        val r = canvasBounds()
        var sum = 0.0
        var n = 0
        val x0 = r.left + r.width() / 4
        val x1 = r.right - r.width() / 4
        val y0 = r.top + r.height() * 2 / 5
        val y1 = r.bottom - r.height() * 2 / 5
        for (y in y0 until y1 step STEP) {
            for (x in x0 until x1 step STEP) {
                val p = shot.getPixel(x, y)
                sum += 0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)
                n++
            }
        }
        return sum / n
    }

    private fun countPixels(rgb: IntArray): Int {
        val shot = screenshot()
        val r = canvasBounds()
        var n = 0
        for (y in r.top until r.bottom step STEP) {
            for (x in r.left until r.right step STEP) {
                val p = shot.getPixel(x, y)
                if (Color.red(p) == rgb[0] && Color.green(p) == rgb[1] && Color.blue(p) == rgb[2]) n++
            }
        }
        return n
    }

    /** [measure] once the GPU had time to present the latest state. */
    private fun <T> settled(measure: () -> T): T {
        Thread.sleep(SETTLE_MS)
        return measure()
    }

    private fun settledLuma(): Double = settled { photoLuma() }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun SemanticsNodeInteraction.assertExistsCompat() {
        fetchSemanticsNode()
    }

    private companion object {
        const val TAG = "REVEAL_EDITOR_TEST"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 400L
        const val POLL_MS = 20L
        const val STEP = 4
        const val MIN_OVERLAY_PIXELS = 50
        const val HOLD_MS = 300L
    }
}
