package dev.hfps.reveal.editor

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.TargetedTarget
import dev.hfps.reveal.feature.editor.edit.CurveEditing
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Treatment
import kotlin.math.min
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The colour tools on the real open flow (docs/adr/0013): a synthetic JPEG through `ACTION_VIEW` (any emulator,
 * locale-independent: test tags and recipe values only). A curve point drag is one history step; dragging a point off
 * the editor removes it; the targeted adjustment moves the band under the finger and nothing else; B&W Auto mix
 * writes a grey mix.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ColourToolsInteractionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun recipes(): Pair<Recipe, Recipe> = checkNotNull(EditorMetrics.recipes).invoke()

    private val committed: Recipe get() = recipes().second

    @Test
    fun curvePointDragIsOneStepAndUndoRestoresTheLine() = withPhoto {
        openRow(EditorTags.TONE_CURVE_ROW)
        val editor = compose.onNodeWithTag(EditorTags.CURVE_EDITOR)
        val before = editor.fetchSemanticsNode().boundsInWindow
        // Drag from the middle of the diagonal upwards: adds a point there and moves it, in one gesture.
        editor.performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.5f), Offset(width * 0.5f, height * 0.3f), SWIPE_MS)
        }
        waitForCommit()
        val points = committed.light.curve.rgb
        Log.i(TAG, "curve after drag: $points, editor $before -> ${editor.fetchSemanticsNode().boundsInWindow}")
        assertThat(points).hasSize(3)
        assertThat(points[1].x).isWithin(0.03f).of(0.5f)
        assertThat(points[1].y).isGreaterThan(0.62f)
        assertThat(recipes().first).isEqualTo(committed)

        // One step: a single undo is the straight line again.
        compose.onNodeWithTag(EditorTags.UNDO).performClick()
        compose.waitForIdle()
        assertThat(committed.light.curve.rgb).isEmpty()
    }

    @Test
    fun draggingAPointOffTheEditorRemovesIt() = withPhoto {
        openRow(EditorTags.TONE_CURVE_ROW)
        val editor = compose.onNodeWithTag(EditorTags.CURVE_EDITOR)
        // The editor is a square, possibly clipped at the bottom by the panel: its side is the node's width.
        editor.performTouchInput {
            swipe(Offset(width * 0.25f, width * 0.75f), Offset(width * 0.25f, width * 0.65f), SWIPE_MS)
        }
        waitForCommit()
        assertThat(committed.light.curve.rgb).hasSize(3)
        val point = committed.light.curve.rgb[1]
        editor.performTouchInput {
            val from = Offset(point.x * width, (1f - point.y) * width)
            swipe(from, Offset(from.x, width * 1.6f), SWIPE_MS)
        }
        waitForCommit()
        assertThat(CurveEditing.shown(committed.light.curve.rgb)).hasSize(2)
    }

    @Test
    fun targetedAdjustmentMovesTheBandUnderTheFinger() = withPhoto {
        selectTool(ToolId.COLOUR)
        openRow(EditorTags.MIXER_ROW)
        compose.onNodeWithTag(EditorTags.TARGETED).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.targetedTarget(TargetedTarget.LUMINANCE)), TIMEOUT_MS)
        compose.onNodeWithTag(EditorTags.targetedTarget(TargetedTarget.LUMINANCE)).performClick()
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        // The blue patch of the test card: third of eight strips in the bottom half.
        compose.onNodeWithTag(EditorTags.CANVAS).performTouchInput {
            val scale = min(width / CARD_W, height / CARD_H)
            val left = (width - CARD_W * scale) / 2f
            val top = (height - CARD_H * scale) / 2f
            val at = Offset(left + CARD_W * scale * 2.5f / 8f, top + CARD_H * scale * 0.75f)
            swipe(at, at - Offset(0f, height * 0.25f), SWIPE_MS)
        }
        waitForCommit()
        val luminance = committed.colour.mixer.luminance
        Log.i(TAG, "targeted on blue: $luminance")
        assertThat(luminance.blue).isGreaterThan(5f)
        ColourBand.entries.filter { it != ColourBand.BLUE && it != ColourBand.PURPLE && it != ColourBand.AQUA }
            .forEach { assertThat(luminance[it]).isEqualTo(0f) }
        assertThat(luminance.blue).isGreaterThan(luminance.purple)
        assertThat(luminance.blue).isGreaterThan(luminance.aqua)
        assertThat(committed.colour.mixer.hue.isNeutral).isTrue()
        assertThat(committed.colour.mixer.saturation.isNeutral).isTrue()

        compose.onNodeWithTag(EditorTags.UNDO).performClick()
        compose.waitForIdle()
        assertThat(committed.colour.mixer.isNeutral).isTrue()
    }

    @Test
    fun blackAndWhiteAutoMixWritesAGreyMix() = withPhoto {
        selectTool(ToolId.COLOUR)
        // Every node is awaited (present and enabled) before it is touched: the panel, the grey mixer's row (shown
        // once the treatment is committed) and the sub-panel's Auto (enabled once the Auto sample is in memory) appear
        // after state changes that `waitForIdle` does not cover on a fast device.
        val blackWhite = EditorTags.treatment(Treatment.BLACK_WHITE)
        awaitEnabled(blackWhite)
        compose.onNodeWithTag(blackWhite).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { committed.profile.treatment == Treatment.BLACK_WHITE }
        openRow(EditorTags.GREY_MIXER_ROW)
        awaitEnabled(EditorTags.AUTO_MIX)
        compose.onNodeWithTag(EditorTags.AUTO_MIX).performClick()
        compose.waitUntil(TIMEOUT_MS) { !committed.colour.greyMixer.isNeutral }
        Log.i(TAG, "auto mix: ${committed.colour.greyMixer}")
        assertThat(recipes().first).isEqualTo(committed)
    }

    // ------------------------------------------------------------------ helpers

    private fun withPhoto(block: () -> Unit) {
        val uri = TestPhotos.insert(
            TestPhotos.uniqueName("reveal-colour-tools.jpg"),
            "image/jpeg",
            TestPhotos.syntheticJpeg(CARD_W.toInt(), CARD_H.toInt()),
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

    private fun selectTool(tool: ToolId) {
        awaitEnabled(EditorTags.tool(tool))
        compose.onNodeWithTag(EditorTags.tool(tool)).performClick()
        compose.waitForIdle()
    }

    private fun openRow(tag: String) {
        awaitEnabled(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    /** Waits until exactly one node with [tag] exists and is enabled (a condition wait, not a sleep). */
    private fun awaitEnabled(tag: String) {
        compose.waitUntilExactlyOneExists(hasTestTag(tag) and isEnabled(), TIMEOUT_MS)
    }

    /** Waits until the working recipe is committed and differs from where the gesture started (or is equal again). */
    private fun waitForCommit() {
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) { recipes().let { it.first == it.second } }
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val TAG = "REVEAL_EDITOR_TEST"
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 500L
        const val SWIPE_MS = 600L
        const val CARD_W = 1200f
        const val CARD_H = 800f
    }
}
