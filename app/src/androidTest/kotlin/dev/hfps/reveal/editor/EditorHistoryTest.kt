package dev.hfps.reveal.editor

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.designsystem.components.slider.NumericInputTags
import dev.hfps.reveal.designsystem.components.slider.RevealSliderTags
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Undo/redo, the history list and their persistence on the real open flow: a synthetic JPEG opened through
 * `ACTION_VIEW` is filed in the library (ADR 0011), so its history is saved per version and comes back when the
 * photo is opened again. Locale-independent: test tags and `ValueFormat` labels only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorHistoryTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val exposureValue: String
        get() = compose.onNodeWithTag(EditorTags.EXPOSURE).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)
            .orEmpty()
            .substringBefore(", ")

    @Test
    fun undoRedoHistoryJumpAndBranchPersistAcrossReopening() {
        val uri = TestPhotos.insert("reveal-history-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            open(uri) {
                button(EditorTags.UNDO).assertIsNotEnabled()
                button(EditorTags.REDO).assertIsNotEnabled()

                typeExposure("+1,5")
                typeExposure("+0,5")
                assertThat(exposureValue).isEqualTo("+0.50")

                click(EditorTags.UNDO)
                assertThat(exposureValue).isEqualTo("+1.50")
                click(EditorTags.UNDO)
                assertThat(exposureValue).isEqualTo("0.00")
                button(EditorTags.UNDO).assertIsNotEnabled()
                click(EditorTags.REDO)
                assertThat(exposureValue).isEqualTo("+1.50")
                button(EditorTags.REDO).assertIsEnabled()

                // The history list: tap the newest step (still on the redo branch), then the original.
                click(EditorTags.MORE)
                click(EditorTags.MENU_HISTORY)
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.HISTORY_LIST), TIMEOUT_MS)
                click(EditorTags.historyStep(1))
                button(EditorTags.REDO).assertIsNotEnabled()
                click(EditorTags.historyStep(-1))
                button(EditorTags.UNDO).assertIsNotEnabled()
                showLight()
                assertThat(exposureValue).isEqualTo("0.00")

                // A new edit from the original starts a new branch: nothing left to redo.
                typeExposure("-1")
                assertThat(exposureValue).isEqualTo("−1.00")
                button(EditorTags.REDO).assertIsNotEnabled()

                // A panel reset is one undoable step.
                click(EditorTags.PANEL_RESET)
                assertThat(exposureValue).isEqualTo("0.00")
                click(EditorTags.UNDO)
                assertThat(exposureValue).isEqualTo("−1.00")
            }
            // Reopened (a new activity and ViewModel, read back from Room): the recipe and the history are there.
            open(uri) {
                assertThat(exposureValue).isEqualTo("−1.00")
                button(EditorTags.UNDO).assertIsEnabled()
                button(EditorTags.REDO).assertIsEnabled()
                click(EditorTags.UNDO)
                assertThat(exposureValue).isEqualTo("0.00")
                click(EditorTags.REDO)
                click(EditorTags.REDO)
                assertThat(exposureValue).isEqualTo("0.00")
                button(EditorTags.REDO).assertIsNotEnabled()
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    private fun open(uri: Uri, block: () -> Unit) {
        TestPhotos.launchView(uri, "image/jpeg").use {
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
            compose.waitForIdle()
            block()
        }
        // Let the history writer land its last transaction before the next launch reads it.
        Thread.sleep(WRITE_MS)
    }

    /** The clickable inside a `RevealIconButton` (the tag sits on its tooltip wrapper, which has no enabled state). */
    private fun button(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).onChildren().filterToOne(hasClickAction())

    private fun showLight() {
        click(EditorTags.tool(ToolId.LIGHT))
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.EXPOSURE), TIMEOUT_MS)
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollToIfPossible().performClick()
        compose.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIfPossible() =
        apply { runCatching { performScrollTo() } }

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

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val WRITE_MS = 500L
    }
}
