package dev.hfps.reveal.editor

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlin.math.min
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Crop mode on the real open flow (a synthetic 3:2 JPEG through `ACTION_VIEW`): a corner handle drag crops, rotate
 * right turns, the straighten dial tilts, and nothing is committed until Done, which commits it all as one step;
 * Cancel reverts. Locale-independent (test tags, recipe state).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CropModeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val working: Recipe get() = checkNotNull(EditorMetrics.recipes).invoke().first
    private val committed: Recipe get() = checkNotNull(EditorMetrics.recipes).invoke().second

    @Test
    fun dragRotateStraightenThenDoneCommitsOneStep() = withPhoto {
        openCrop()

        // The bottom-right corner of the frame (the whole 3:2 image, fitted with the overlay's margin) moved in.
        val overlay = compose.onNodeWithTag(EditorTags.CROP_OVERLAY)
        val corner =
            frameCorner(
                overlay.fetchSemanticsNode().size.width.toFloat(),
                overlay.fetchSemanticsNode().size.height.toFloat(),
            )
        overlay.performTouchInput { swipe(corner, corner - Offset(width * 0.25f, height * 0.15f), SWIPE_MS) }
        compose.waitForIdle()
        val crop = checkNotNull(working.geometry.crop) { "the drag set a crop" }
        Log.i(TAG, "after drag: $crop")
        assertThat(crop.right).isLessThan(0.95f)
        assertThat(crop.bottom).isLessThan(0.95f)
        assertThat(crop.left).isWithin(0.01f).of(0f)
        assertThat(committed.geometry.crop).isNull()

        compose.onNodeWithTag(EditorTags.CROP_ROTATE_RIGHT).performClick()
        compose.waitForIdle()
        assertThat(working.geometry.rotate).isEqualTo(1)

        compose.onNodeWithTag(EditorTags.CROP_DIAL).performTouchInput {
            swipe(Offset(width * 0.6f, height * 0.8f), Offset(width * 0.4f, height * 0.8f), SWIPE_MS)
        }
        compose.waitForIdle()
        val angle = working.geometry.angle
        Log.i(TAG, "after dial: angle $angle, ${working.geometry.crop}")
        assertThat(angle).isGreaterThan(1f)
        assertThat(committed.geometry).isEqualTo(Recipe.DEFAULT.geometry)

        compose.onNodeWithTag(EditorTags.MODE_DONE).performClick()
        compose.waitForIdle()
        assertThat(committed).isEqualTo(working)
        assertThat(committed.geometry.rotate).isEqualTo(1)
        assertThat(committed.geometry.angle).isEqualTo(angle)
        assertThat(compose.onAllNodes(hasTestTag(EditorTags.MODE_CONTROLS)).fetchSemanticsNodes()).isEmpty()
        // The editor now frames the cropped, turned picture: the photo is portrait on screen.
        Thread.sleep(SETTLE_MS)
    }

    @Test
    fun cancelRevertsEverythingDoneInTheMode() = withPhoto {
        openCrop()
        compose.onNodeWithTag(EditorTags.CROP_ROTATE_LEFT).performClick()
        compose.onNodeWithTag(EditorTags.CROP_FLIP_H).performClick()
        compose.waitForIdle()
        assertThat(working.geometry.rotate).isEqualTo(3)
        assertThat(working.geometry.flipH).isTrue()

        compose.onNodeWithTag(EditorTags.MODE_CANCEL).performClick()
        compose.waitForIdle()
        assertThat(working.geometry).isEqualTo(Recipe.DEFAULT.geometry)
        assertThat(committed.geometry).isEqualTo(Recipe.DEFAULT.geometry)
    }

    private fun openCrop() {
        compose.onNodeWithTag(EditorTags.tool(ToolId.CROP)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.CROP_OVERLAY), TIMEOUT_MS)
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    /** The bottom-right corner of the full-image frame in an overlay of [w] x [h] pixels (CropView.fit). */
    private fun frameCorner(w: Float, h: Float): Offset {
        val margin =
            MARGIN_DP * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val scale = min((w - 2 * margin) / IMAGE_W, (h - 2 * margin) / IMAGE_H)
        // A little inside the corner so the touch lands on the handle, not past it.
        return Offset(w / 2f + IMAGE_W * scale / 2f - INSET, h / 2f + IMAGE_H * scale / 2f - INSET)
    }

    private fun withPhoto(block: () -> Unit) {
        val uri = TestPhotos.insert("reveal-crop.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
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
        const val SWIPE_MS = 500L
        const val IMAGE_W = 1200f
        const val IMAGE_H = 800f
        const val MARGIN_DP = 28f
        const val INSET = 4f
    }
}
