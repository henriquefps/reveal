package dev.hfps.reveal.editor

import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Recipe
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-tap Auto on the real open flow (docs/adr/0010): the strip's Auto sets the Light sliders and vibrance as one
 * history step, a second tap changes nothing (idempotent), undo goes back, and the Light panel's Auto puts the Light
 * group back on Auto's values. On the public CC0 ZV-E10 RAW (push it into `files/samples`, CLAUDE.md) and on a
 * synthetic JPEG (any emulator). Locale-independent: test tags only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorAutoTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun oneTapAutoOnTheCc0Raw() {
        val sample = TestPhotos.sample(ARW_SAMPLE)
        assumeTrue("push $ARW_SAMPLE into files/samples (CLAUDE.md)", sample != null)
        val uri = TestPhotos.insert("reveal-auto-${System.nanoTime()}.ARW", "image/x-sony-arw", sample!!)
        try {
            oneTap(uri, "image/x-sony-arw")
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun oneTapAutoOnAJpeg() {
        val uri = TestPhotos.insert("reveal-auto-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            oneTap(uri, "image/jpeg")
        } finally {
            TestPhotos.delete(uri)
        }
    }

    private fun oneTap(uri: Uri, mimeType: String) {
        TestPhotos.launchView(uri, mimeType).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
            compose.waitForIdle()
            Thread.sleep(SETTLE_MS)
            // A RAW with a lens profile opens with its corrections on (docs/adr/0015); nothing else is set.
            val opened = committed()
            assertThat(opened.copy(optics = Optics())).isEqualTo(Recipe.DEFAULT)

            click(EditorTags.tool(ToolId.AUTO))
            compose.waitUntil(TIMEOUT_MS) { committed() != opened }
            val auto = committed()
            assertThat(auto.light).isNotEqualTo(Light())
            assertThat(auto.colour.vibrance).isAtLeast(0f)
            assertThat(auto.profile).isEqualTo(Recipe.DEFAULT.profile)
            assertThat(working()).isEqualTo(auto)

            // Idempotent: a second tap gives the same values (and no new history step).
            click(EditorTags.tool(ToolId.AUTO))
            Thread.sleep(SETTLE_MS)
            compose.waitForIdle()
            assertThat(committed()).isEqualTo(auto)

            // One step: a single undo returns to the photo as opened, redo brings Auto back.
            button(EditorTags.UNDO).assertIsEnabled()
            click(EditorTags.UNDO)
            assertThat(committed()).isEqualTo(opened)
            click(EditorTags.REDO)
            assertThat(committed()).isEqualTo(auto)

            // The Light panel's Auto: Light only, back to the same values after a reset.
            // The editor opens on Light; tapping the open tool again would collapse it.
            if (compose.onAllNodesWithTag(EditorTags.PANEL_AUTO).fetchSemanticsNodes().isEmpty()) {
                click(EditorTags.tool(ToolId.LIGHT))
            }
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PANEL_AUTO), TIMEOUT_MS)
            click(EditorTags.PANEL_RESET)
            assertThat(committed().light).isEqualTo(Light())
            click(EditorTags.PANEL_AUTO)
            compose.waitUntil(TIMEOUT_MS) { committed().light != Light() }
            assertThat(committed().light).isEqualTo(auto.light)
            assertThat(committed().colour).isEqualTo(auto.colour)
        }
    }

    private fun committed(): Recipe = checkNotNull(EditorMetrics.recipes).invoke().second

    private fun working(): Recipe = checkNotNull(EditorMetrics.recipes).invoke().first

    /** The clickable inside a `RevealIconButton` (the tag sits on its tooltip wrapper, which has no enabled state). */
    private fun button(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).onChildren().filterToOne(hasClickAction())

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val ARW_SAMPLE = "sony-zv-e10.arw"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_000L
    }
}
