package dev.hfps.reveal.editor

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.geometry.Perspective
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.UprightMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Crop mode's Transform tab on the real open flow (docs/adr/0015), on a synthetic facade with converging
 * verticals (`TestPhotos.facadeJpeg`, the truth known): Upright Vertical writes the transform sliders and Done commits
 * exactly one step; guide lines drawn by touch drive Guided Upright. Locale-independent (test tags, recipe state).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TransformModeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val working: Recipe get() = checkNotNull(EditorMetrics.recipes).invoke().first
    private val committed: Recipe get() = checkNotNull(EditorMetrics.recipes).invoke().second
    private val history: EditHistory get() = checkNotNull(EditorMetrics.history).invoke()

    @Test
    fun uprightVerticalCommitsOneStepAndSetsTheSliders() = withFacade {
        val stepsBefore = history.entries.size
        openTransform()
        compose.onNodeWithTag(EditorTags.upright(UprightMode.VERTICAL)).performScrollTo().performClick()
        waitFor { working.geometry.transform.upright == UprightMode.VERTICAL }
        val t = working.geometry.transform
        Log.i(TAG, "upright vertical: $t, crop ${working.geometry.crop}")
        assertThat(t.vertical.toDouble()).isWithin(VERTICAL_TOLERANCE).of(TRUTH.vertical.toDouble())
        assertThat(abs(t.rotate)).isLessThan(0.5f)
        // The constrained crop refitted (no empty corner): a crop is set.
        assertThat(working.geometry.crop).isNotNull()
        // Nothing is committed until Done.
        assertThat(committed.geometry.transform).isEqualTo(Transform())
        assertThat(history.entries.size).isEqualTo(stepsBefore)

        compose.onNodeWithTag(EditorTags.MODE_DONE).performClick()
        compose.waitForIdle()
        assertThat(history.entries.size).isEqualTo(stepsBefore + 1)
        assertThat(committed).isEqualTo(working)
        assertThat(committed.geometry.transform.vertical).isEqualTo(t.vertical)
        val label = history.entries.last().label
        assertThat(label.changes.first().field).isEqualTo(HistoryField.UPRIGHT)
        assertThat(label.changes.map { it.field }).contains(HistoryField.TRANSFORM_VERTICAL)
    }

    @Test
    fun guidesDrawnByTouchDriveGuidedUpright() = withFacade {
        openTransform()
        compose.onNodeWithTag(EditorTags.upright(UprightMode.GUIDED)).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.GUIDES_HINT), TIMEOUT_MS)
        val overlay = compose.onNodeWithTag(EditorTags.CROP_OVERLAY)
        val size = overlay.fetchSemanticsNode().size
        // The facade's left and right edges (world x = -+0.62) where the camera saw them, on screen.
        for (worldX in listOf(-0.62, 0.62)) {
            val top = screen(worldX, -0.38, size.width.toFloat(), size.height.toFloat())
            val bottom = screen(worldX, 0.34, size.width.toFloat(), size.height.toFloat())
            overlay.performTouchInput { swipe(top, bottom, SWIPE_MS) }
            compose.waitForIdle()
        }
        waitFor { working.geometry.transform.guides.size == 2 && working.geometry.transform.vertical != 0f }
        val t = working.geometry.transform
        Log.i(TAG, "guided: $t")
        assertThat(t.upright).isEqualTo(UprightMode.GUIDED)
        assertThat(t.vertical.toDouble()).isWithin(GUIDED_TOLERANCE).of(TRUTH.vertical.toDouble())
        compose.onNodeWithTag(EditorTags.MODE_DONE).performClick()
        compose.waitForIdle()
        assertThat(committed.geometry.transform.guides).hasSize(2)
    }

    /**
     * Screen point (overlay pixels) of world point ([wx], [wy]): the camera's image of it (the inverse of the
     * correction), then the Crop mode's fit of the whole 3:2 image with the overlay's margin.
     */
    private fun screen(wx: Double, wy: Double, w: Float, h: Float): Offset {
        val half = hypot(IMAGE_W.toDouble(), IMAGE_H.toDouble()) / 2
        val p = checkNotNull(Perspective.forward(TRUTH).inverse().map(wx, wy))
        val ix = p.x * half + IMAGE_W / 2
        val iy = p.y * half + IMAGE_H / 2
        val margin =
            MARGIN_DP * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val scale = min((w - 2 * margin) / IMAGE_W, (h - 2 * margin) / IMAGE_H)
        return Offset((w / 2f + (ix - IMAGE_W / 2) * scale).toFloat(), (h / 2f + (iy - IMAGE_H / 2) * scale).toFloat())
    }

    private fun openTransform() {
        compose.onNodeWithTag(EditorTags.tool(ToolId.CROP)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.CROP_OVERLAY), TIMEOUT_MS)
        compose.onNodeWithTag(EditorTags.CROP_TAB_TRANSFORM).performClick()
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(POLL_MS)
            compose.waitForIdle()
        }
    }

    private fun withFacade(block: () -> Unit) {
        val bytes = TestPhotos.facadeJpeg(IMAGE_W.toInt(), IMAGE_H.toInt(), TRUTH)
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-facade.jpg"), "image/jpeg", bytes)
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

    private companion object {
        const val TAG = "REVEAL_EDITOR_TEST"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 400L
        const val POLL_MS = 100L
        const val SWIPE_MS = 600L
        const val IMAGE_W = 1200f
        const val IMAGE_H = 800f
        const val MARGIN_DP = 28f
        const val VERTICAL_TOLERANCE = 3.0
        const val GUIDED_TOLERANCE = 4.0
        val TRUTH = Transform(vertical = 30f)
    }
}
