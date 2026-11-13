package dev.hfps.reveal.editor

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.mask.AiMaskState
import dev.hfps.reveal.data.mask.AiMasks
import dev.hfps.reveal.designsystem.components.slider.NumericInputTags
import dev.hfps.reveal.designsystem.components.slider.RevealSliderTags
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.masks.MaskTags
import dev.hfps.reveal.feature.editor.masks.MasksUiState
import dev.hfps.reveal.feature.editor.masks.NewComponent
import dev.hfps.reveal.feature.editor.masks.PhotoTool
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Masks tool on the real editor (docs/adr/0022 "M4/B (editor)"): every hand-made type is created, edited and
 * undone as named history steps and comes back when the photo is opened again; the AI kinds run on public-domain
 * photos (a portrait's subject, hair and eyes; a landscape's sky); a recipe naming a model this app does not have still
 * opens. Locale-independent (test tags); unique photo content per run, so the suite can run twice without `pm clear`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MasksFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val history: EditHistory get() = checkNotNull(EditorMetrics.history).invoke()
    private val committed: Recipe get() = checkNotNull(EditorMetrics.recipes).invoke().second
    private val masks: MasksUiState get() = checkNotNull(EditorMetrics.masks).invoke()
    private val selected: Mask get() = committed.masks.first { it.id == masks.selected }

    @Test
    fun everyHandMadeTypeIsCreatedEditedUndoneAndKept() {
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-masks.jpg"), "image/jpeg", TestPhotos.syntheticJpeg())
        var kept: List<Mask> = emptyList()
        try {
            open(uri) {
                click(EditorTags.tool(ToolId.MASKS))
                compose.waitUntilAtLeastOneExists(hasTestTag(MaskTags.EMPTY), TIMEOUT_MS)

                // Linear: one step; a local exposure on it covers the top of the photo, not the bottom.
                click(MaskTags.choice(NewComponent.Linear))
                waitSteps(1)
                assertThat(history.entries.last().label.changes.map { it.field }).contains(HistoryField.MASK_ADD)
                typeValue(MaskTags.adjustment(LocalAdjustment.EXPOSURE), "+1")
                assertThat(selected.adjustments.exposure).isEqualTo(1f)
                assertThat(history.entries.last().label.changes.map { it.field }).contains(HistoryField.MASK_ADJUST)
                val coverage = coverage(selected.id)
                val (w, h) = proxySize()
                assertThat(coverage[(h / 10) * w + w / 2]).isGreaterThan(0.9f)
                assertThat(coverage[(h * 9 / 10) * w + w / 2]).isLessThan(0.1f)

                // Radial from "+".
                add(NewComponent.Radial)
                assertThat(committed.masks).hasSize(2)
                assertThat(selected.components.single()).isInstanceOf(MaskComponent.Radial::class.java)

                // Brush: a stroke is one step; erase mode writes an erasing stroke.
                add(NewComponent.Brush)
                assertThat(masks.tool).isEqualTo(PhotoTool.BRUSH)
                val before = history.entries.size
                swipeOnPhoto(Offset(0.2f, 0.5f), Offset(0.8f, 0.5f))
                compose.waitUntil(TIMEOUT_MS) { history.entries.size == before + 1 }
                val brush = selected.components.single() as MaskComponent.Brush
                assertThat(brush.strokes).hasSize(1)
                assertThat(brush.strokes.single().erase).isFalse()
                assertThat(brush.strokes.single().decoded.size).isAtLeast(2)
                click(MaskTags.BRUSH_ERASE)
                swipeOnPhoto(Offset(0.4f, 0.5f), Offset(0.6f, 0.5f))
                compose.waitUntil(TIMEOUT_MS) { (selected.components.single() as MaskComponent.Brush).strokes.size == 2 }
                assertThat((selected.components.single() as MaskComponent.Brush).strokes.last().erase).isTrue()

                // Colour range: the eyedropper is on for a new one; a tap adds a sample.
                add(NewComponent.ColourRange)
                assertThat(masks.tool).isEqualTo(PhotoTool.PICK_COLOUR)
                tapOnPhoto(Offset(0.3f, 0.4f))
                compose.waitUntil(TIMEOUT_MS) {
                    (selected.components.single() as MaskComponent.ColourRange).samples.size == 1
                }

                // Luminance range, then "subtract" a radial from it.
                add(NewComponent.LuminanceRange)
                typeValue(MaskTags.LUMA_LOW, "30")
                assertThat((selected.components.single() as MaskComponent.LuminanceRange).low).isEqualTo(30f)
                click(MaskTags.combine(MaskOp.SUBTRACT))
                click(MaskTags.choice(NewComponent.Radial))
                compose.waitUntil(TIMEOUT_MS) { selected.components.size == 2 }
                assertThat(selected.components[1].op).isEqualTo(MaskOp.SUBTRACT)

                // Rename and hide through the header.
                click(MaskTags.MENU)
                click(MaskTags.option("rename"))
                compose.onNodeWithTag(MaskTags.RENAME_FIELD).performTextReplacement("Shadows")
                click(MaskTags.RENAME_SAVE)
                compose.waitUntil(TIMEOUT_MS) { selected.name == "Shadows" }
                click(MaskTags.EYE)
                compose.waitUntil(TIMEOUT_MS) { !selected.enabled }
                assertThat(committed.masks).hasSize(5)

                // Undo walks back step by step; redo returns.
                val steps = history.entries.size
                click(EditorTags.UNDO)
                compose.waitUntil(TIMEOUT_MS) { committed.masks.last().enabled }
                click(EditorTags.UNDO)
                compose.waitUntil(TIMEOUT_MS) { committed.masks.last().name != "Shadows" }
                click(EditorTags.REDO)
                click(EditorTags.REDO)
                compose.waitUntil(TIMEOUT_MS) { history.cursor == steps - 1 }
                assertThat(committed.masks.last().name).isEqualTo("Shadows")

                // Delete the radial mask (confirmed): one step.
                click(MaskTags.mask(1))
                click(MaskTags.MENU)
                click(MaskTags.option("delete"))
                click(MaskTags.option("confirm"))
                compose.waitUntil(TIMEOUT_MS) { committed.masks.size == 4 }
                assertThat(history.entries.last().label.changes.map { it.field }).contains(HistoryField.MASK_REMOVE)
                kept = committed.masks
            }
            // Reopened from Room: the same masks.
            open(uri) {
                assertThat(committed.masks).isEqualTo(kept)
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun theSubjectHairAndEyesOfAPortrait() {
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-masks-portrait.jpg"), "image/jpeg", asset("studio-portrait.jpg"))
        try {
            open(uri) {
                click(EditorTags.tool(ToolId.MASKS))
                click(MaskTags.choice(NewComponent.Ai(MaskKind.SUBJECT)))
                val subject = awaitAiReady()
                val cover = coverage(subject.id).average()
                assertThat(cover).isGreaterThan(0.05)
                assertThat(cover).isLessThan(0.9)

                // A tap with "remove an object" adds a subtracting subject part seeded by the tap only.
                click(MaskTags.SUBJECT_SUBTRACT)
                tapOnPhoto(Offset(0.5f, 0.45f))
                compose.waitUntil(TIMEOUT_MS) { selected.components.size == 2 }
                val removed = selected.components[1] as MaskComponent.Ai
                assertThat(removed.op).isEqualTo(MaskOp.SUBTRACT)
                assertThat(removed.autoSeeds).isFalse()
                assertThat(removed.prompts).hasSize(1)

                // People parts.
                add(NewComponent.Ai(MaskKind.HAIR), people = true)
                assertThat(coverage(awaitAiReady().id).average()).isGreaterThan(0.005)
                add(NewComponent.Ai(MaskKind.EYES), people = true)
                awaitAiReady()
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun theSkyOfALandscape() {
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-masks-sky.jpg"), "image/jpeg", asset("landscape.jpg"))
        try {
            open(uri) {
                click(EditorTags.tool(ToolId.MASKS))
                click(MaskTags.choice(NewComponent.Ai(MaskKind.SKY)))
                val sky = awaitAiReady()
                val c = coverage(sky.id)
                val (w, h) = proxySize()
                val top = (0 until w).map { c[(h / 20) * w + it] }.average()
                val bottom = (0 until w).map { c[(h * 19 / 20) * w + it] }.average()
                assertThat(top).isGreaterThan(0.5)
                assertThat(bottom).isLessThan(0.2)
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun aMaskFromANewerAppOpensAndSaysItIsUnavailable() {
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-masks-newer.jpg"), "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            open(uri) {
                val newer = Mask(
                    "newer",
                    "Future",
                    adjustments = LocalAdjustments(exposure = 1f),
                    components = listOf(
                        MaskComponent.Ai(model = "sky-net@9", kind = "SKY"),
                        MaskComponent.Unknown(buildJsonObject { put("type", "depth") }),
                        MaskComponent.Ai(model = "future@1", kind = "TAIL"),
                    ),
                )
                compose.runOnIdle { checkNotNull(EditorMetrics.applyRecipe).invoke(committed.copy(masks = listOf(newer))) }
                click(EditorTags.tool(ToolId.MASKS))
                click(MaskTags.mask(0))
                compose.waitUntil(TIMEOUT_MS) {
                    masks.ai[AiMasks.key(newer.components[0] as MaskComponent.Ai)] == AiMaskState.UNAVAILABLE
                }
                // The status line is inside the part's (merged) row.
                compose.waitUntil(TIMEOUT_MS) {
                    compose.onAllNodesWithTag(MaskTags.AI_STATUS, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                // The photo still renders and the unknown parts round-trip.
                assertThat(committed.masks.single().components[1]).isInstanceOf(MaskComponent.Unknown::class.java)
                assertThat(coverage("newer").maxOrNull()).isEqualTo(0f)
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("masks/$name").use { it.readBytes() }

    private fun open(uri: Uri, block: () -> Unit) {
        TestPhotos.launchView(uri, "image/jpeg").use {
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
            compose.waitForIdle()
            block()
        }
        Thread.sleep(WRITE_MS)
    }

    /** The "+" chooser's [kind] (under People for its parts); waits for the new mask. */
    private fun add(kind: NewComponent, people: Boolean = false) {
        val count = committed.masks.size
        click(MaskTags.ADD)
        compose.waitUntilAtLeastOneExists(hasTestTag(MaskTags.CHOOSER), TIMEOUT_MS)
        if (people) click(MaskTags.choice("people-group"))
        click(MaskTags.choice(kind))
        compose.waitUntil(TIMEOUT_MS) { committed.masks.size == count + 1 && masks.selected == committed.masks.last().id }
    }

    /** Waits until the selected mask's AI parts are ready (the first run compiles the model). */
    private fun awaitAiReady(): Mask {
        compose.waitUntil(AI_TIMEOUT_MS) {
            val m = committed.masks.firstOrNull { it.id == masks.selected } ?: return@waitUntil false
            m.components.filterIsInstance<MaskComponent.Ai>().all { masks.ai[AiMasks.key(it)] == AiMaskState.READY }
        }
        return selected
    }

    private fun waitSteps(n: Int) = compose.waitUntil(TIMEOUT_MS) { history.entries.size == n }

    private fun proxySize(): Pair<Int, Int> = checkNotNull(EditorMetrics.proxySize)

    private fun coverage(id: String): FloatArray = runBlocking {
        checkNotNull(checkNotNull(EditorMetrics.renderer).readCoverage(committed, id))
    }

    private fun typeValue(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag + RevealSliderTags.VALUE_SUFFIX, useUnmergedTree = true).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
        compose.onNodeWithTag(NumericInputTags.FIELD).performTextReplacement(text)
        compose.onNodeWithTag(NumericInputTags.DONE).performClick()
        compose.waitForIdle()
    }

    private fun swipeOnPhoto(from: Offset, to: Offset) {
        compose.onNodeWithTag(EditorTags.CANVAS).performTouchInput {
            swipe(Offset(width * from.x, height * from.y), Offset(width * to.x, height * to.y), SWIPE_MS)
        }
        compose.waitForIdle()
    }

    private fun tapOnPhoto(at: Offset) {
        compose.onNodeWithTag(EditorTags.CANVAS).performTouchInput {
            down(Offset(width * at.x, height * at.y))
            up()
        }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val AI_TIMEOUT_MS = 180_000L
        const val WRITE_MS = 500L
        const val SWIPE_MS = 600L
    }
}
