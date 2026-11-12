package dev.hfps.reveal.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.designsystem.components.slider.NumericInputTags
import dev.hfps.reveal.designsystem.components.slider.RevealSliderTags
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.recipe.ControlId
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Effects panel in the real editor (docs/adr/0014; a synthetic JPEG through `ACTION_VIEW`, any emulator): the tool
 * opens, the shaping sliders are disabled until their amount is on, numeric entry sets clarity, the vignette and the
 * grain, and the photo on screen follows (local contrast, darker frame edges, grain), undo restores it. Locale-
 * independent: test tags and `ValueFormat` labels only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorEffectsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun effectsPanelDrivesThePhoto() = withPhoto {
        compose.onNodeWithTag(EditorTags.tool(ToolId.EFFECTS)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.control(ControlId.CLARITY)), TIMEOUT_MS)
        assertThat(enabled(ControlId.VIGNETTE_MIDPOINT)).isFalse()
        assertThat(enabled(ControlId.GRAIN_SIZE)).isFalse()

        val base = stats()
        type(ControlId.CLARITY, "80")
        assertThat(value(ControlId.CLARITY)).isEqualTo("+80")
        val clear = settled { stats() }
        val changed = base.centreValues.indices.sumOf { abs(base.centreValues[it] - clear.centreValues[it]) } /
            base.centreValues.size
        Log.i(TAG, "clarity +80: centre std ${base.std} -> ${clear.std}, mean change $changed levels")
        assertThat(changed).isGreaterThan(0.3)

        type(ControlId.VIGNETTE_AMOUNT, "-100")
        assertThat(value(ControlId.VIGNETTE_AMOUNT)).isEqualTo("−100")
        assertThat(enabled(ControlId.VIGNETTE_MIDPOINT)).isTrue()
        val vignetted = settled { stats() }
        Log.i(
            TAG,
            "vignette -100: edge luma ${clear.edge} -> ${vignetted.edge}, centre ${clear.centre} -> " +
                "${vignetted.centre}",
        )
        assertThat(vignetted.edge).isLessThan(clear.edge - 10.0)
        assertThat(abs(vignetted.centre - clear.centre)).isLessThan(clear.edge - vignetted.edge)

        type(ControlId.GRAIN_AMOUNT, "100")
        assertThat(enabled(ControlId.GRAIN_SIZE)).isTrue()
        val grainy = settled { stats() }
        Log.i(TAG, "grain 100: centre roughness ${vignetted.roughness} -> ${grainy.roughness}")
        assertThat(grainy.roughness).isGreaterThan(vignetted.roughness * 1.2)

        // Undo three steps: back to the photo as opened.
        repeat(3) {
            compose.onNodeWithTag(EditorTags.UNDO).performClick()
            compose.waitForIdle()
        }
        val undone = settled { stats() }
        assertThat(abs(undone.edge - base.edge)).isLessThan(2.0)
        assertThat(value(ControlId.CLARITY)).isEqualTo("0")
    }

    // ------------------------------------------------------------------ helpers

    private fun withPhoto(block: () -> Unit) {
        val uri = TestPhotos.insert(
            TestPhotos.uniqueName("reveal-effects.jpg"),
            "image/jpeg",
            TestPhotos.syntheticJpeg(),
        )
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

    private fun value(id: ControlId): String = compose.onNodeWithTag(EditorTags.control(id)).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.StateDescription).orEmpty().substringBefore(", ")

    private fun enabled(id: ControlId): Boolean = !compose.onNodeWithTag(EditorTags.control(id))
        .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)

    /**
     * Numeric entry of [text] on [id]'s slider, each step awaited on a condition: no entry dialog open, then exactly one
     * field, then the dialog gone after Done, then the value label changed (retries once: on a busy phone the first
     * tap can land before the row settles after scrolling; a second dialog is never opened over the first).
     */
    private fun type(id: ControlId, text: String) {
        val before = value(id)
        repeat(2) {
            compose.waitUntilDoesNotExist(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
            compose.onNodeWithTag(EditorTags.control(id)).performScrollTo()
            compose.waitForIdle()
            compose.onNodeWithTag(EditorTags.control(id) + RevealSliderTags.VALUE_SUFFIX, useUnmergedTree = true)
                .performClick()
            compose.waitUntilExactlyOneExists(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
            compose.onNodeWithTag(NumericInputTags.FIELD).performTextReplacement(text)
            compose.onNodeWithTag(NumericInputTags.DONE).performClick()
            compose.waitUntilDoesNotExist(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
            val changed = runCatching { compose.waitUntil(RETRY_MS) { value(id) != before } }.isSuccess
            if (changed) return
        }
    }

    /**
     * Luma statistics of the photo on screen: [centre] mean of the middle, [edge] mean of a band along the left and
     * right edges of the photo (found as the non-surround columns of the middle row), [std] spread of the middle and
     * [roughness] the mean absolute difference between horizontal neighbours there (grain).
     */
    private class Stats(
        val centre: Double,
        val edge: Double,
        val std: Double,
        val roughness: Double,
        val centreValues: DoubleArray,
    )

    private fun stats(): Stats {
        val shot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val b = compose.onNodeWithTag(EditorTags.CANVAS).fetchSemanticsNode().boundsInWindow
        val r = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        val surround = shot.getPixel(r.left + 2, r.top + 2)
        val mid = r.centerY()
        var left = r.left
        while (left < r.centerX() && shot.getPixel(left, mid) == surround) left++
        var right = r.right - 1
        while (right > r.centerX() && shot.getPixel(right, mid) == surround) right--
        val band = maxOf(4, (right - left) / 20)
        val edge = (
            mean(shot, left + 2, left + band, mid - band, mid + band) +
                mean(shot, right - band, right - 2, mid - band, mid + band)
            ) / 2
        val cx0 = r.centerX() - r.width() / 8
        val cx1 = r.centerX() + r.width() / 8
        val cy0 = mid - r.height() / 8
        val cy1 = mid + r.height() / 8
        var s = 0.0
        var s2 = 0.0
        var rough = 0.0
        var n = 0
        val values = ArrayList<Double>()
        for (y in cy0 until cy1) {
            for (x in cx0 until cx1) {
                val l = luma(shot.getPixel(x, y))
                values += l
                s += l
                s2 += l * l
                rough += abs(l - luma(shot.getPixel(x + 1, y)))
                n++
            }
        }
        val m = s / n
        return Stats(m, edge, sqrt(maxOf(s2 / n - m * m, 0.0)), rough / n, values.toDoubleArray())
    }

    private fun mean(shot: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var s = 0.0
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                s += luma(shot.getPixel(x, y))
                n++
            }
        }
        return s / maxOf(n, 1)
    }

    private fun luma(p: Int): Double = 0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)

    private fun <T> settled(measure: () -> T): T {
        Thread.sleep(SETTLE_MS)
        return measure()
    }

    private companion object {
        const val TAG = "REVEAL_EDITOR_TEST"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 600L
        const val RETRY_MS = 3_000L
        const val POLL_MS = 50L
    }
}
