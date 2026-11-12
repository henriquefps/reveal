package dev.hfps.reveal.editor

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.preset.BuiltInPresetNames
import dev.hfps.reveal.pipeline.preset.BuiltInPresets
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.preset.SmartRules
import dev.hfps.reveal.pipeline.preset.smartRules
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smart presets on the real editor (docs/adr/0020): on a public-domain portrait the faces are found in the background
 * at open; tapping the built-in "Natural portrait" is **one** named history step whose correction (exposure, tone,
 * Auto white balance, vibrance) and style land as plain slider values, shown by the Light panel's sliders.
 * Locale-independent (test tags, resources); unique photo content per run.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmartPresetsFlowTest {
    @get:org.junit.Rule
    val compose = createEmptyComposeRule()

    private val history: EditHistory get() = checkNotNull(EditorMetrics.history).invoke()

    private fun portrait(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("faces/studio-trio.jpg").use { it.readBytes() }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aSmartPresetIsOneStepOfPlainSliderValues() {
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-smart.jpg"), "image/jpeg", portrait())
        val preset = checkNotNull(BuiltInPresets.all.firstOrNull { it.key == "smart_natural_portrait" })
        val group = BuiltInPresets.idOf("group_${BuiltInPresets.Group.SMART.key}")
        try {
            open(uri) {
                // The background analysis finds the three faces.
                compose.waitUntil(TIMEOUT_MS) { EditorMetrics.faces != null }
                assertThat(EditorMetrics.faces).isEqualTo(FACES)
                click(EditorTags.tool(ToolId.PRESETS))
                click(EditorTags.presetChip(group))
                click(EditorTags.preset(preset.preset.id))
                compose.waitUntil(TIMEOUT_MS) { history.entries.size == 1 }
                val step = history.entries.single()
                assertThat(step.label.action).isEqualTo(HistoryAction.PRESET)
                assertThat(
                    step.label.name,
                ).isEqualTo(context.getString(checkNotNull(BuiltInPresetNames.name(preset.key))))
                val r = history.current
                // The correction: computed values, Auto white balance; the style: the portrait look's texture.
                assertThat(r.colour.whiteBalance.mode).isEqualTo(WbMode.AUTO)
                assertThat(r.light.exposureEv).isNotEqualTo(0f)
                assertThat(r.colour.vibrance).isAtMost(12f)
                assertThat(r.effects.texture).isEqualTo(-10f)
                // Shown as ordinary sliders.
                click(EditorTags.tool(ToolId.LIGHT))
                for ((id, value) in listOf(
                    ControlId.EXPOSURE to r.light.exposureEv,
                    ControlId.CONTRAST to r.light.contrast,
                    ControlId.HIGHLIGHTS to r.light.highlights,
                    ControlId.SHADOWS to r.light.shadows,
                )) {
                    val node = compose.onNodeWithTag(EditorTags.control(id)).apply { runCatching { performScrollTo() } }
                    val info = node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
                    assertThat(info.current).isWithin(1e-3f).of(value)
                }
                // Tapping it again replaces its step (ADR 0019) with the same values: the correction is deterministic.
                click(EditorTags.tool(ToolId.PRESETS))
                click(EditorTags.preset(preset.preset.id))
                Thread.sleep(WRITE_MS)
                compose.waitForIdle()
                assertThat(history.entries).hasSize(1)
                assertThat(history.current).isEqualTo(r)
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun anyUserPresetCanBeMadeSmart() {
        val presets = EntryPointAccessors.fromApplication(context, DebugAccess::class.java).presets()
        val style = PresetPatch.fromSections(
            Recipe(light = Light(exposureEv = 1.5f, contrast = 30f)),
            PresetSection.entries,
        )
        val (groupId, uuid) = runBlocking {
            val g = presets.createGroup("Smart test ${System.nanoTime()}")
            g to presets.create("Mine ${System.nanoTime()}", style, g)
        }
        val groupUuid = runBlocking { presets.library().first() }.groups.first { it.rowId == groupId }.uuid
        val uri = TestPhotos.insert(TestPhotos.uniqueName("reveal-make-smart.jpg"), "image/jpeg", portrait())
        try {
            open(uri) {
                compose.waitUntil(TIMEOUT_MS) { EditorMetrics.faces != null }
                click(EditorTags.tool(ToolId.PRESETS))
                click(EditorTags.presetChip(groupUuid))
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.preset(uuid)), TIMEOUT_MS)
                compose.onNodeWithTag(EditorTags.preset(uuid)).performTouchInput { longClick() }
                click(EditorTags.presetOption("smart"))
                // The default choice (every correction but straighten), saved.
                click("${EditorTags.PRESET_SMART}-confirm")
                compose.waitUntil(TIMEOUT_MS) { runBlocking { presets.preset(uuid) }?.smart != null }
                val smart = checkNotNull(runBlocking { presets.preset(uuid) })
                assertThat(checkNotNull(smart.smartRules).kinds).doesNotContain(SmartRules.Kind.STRAIGHTEN)
                // The style no longer sets what the corrections set.
                assertThat(smart.patch.paths).containsNoneOf("light.exposure", "light.contrast")
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.preset(uuid)), TIMEOUT_MS)
                click(EditorTags.preset(uuid))
                compose.waitUntil(TIMEOUT_MS) { history.entries.size == 1 }
                val r = history.current
                assertThat(r.colour.whiteBalance.mode).isEqualTo(WbMode.AUTO)
                assertThat(r.light.exposureEv).isNotEqualTo(1.5f)
            }
        } finally {
            TestPhotos.delete(uri)
            runBlocking { presets.deleteGroup(groupId) }
        }
    }

    private fun open(uri: Uri, block: () -> Unit) {
        EditorMetrics.faces = null
        TestPhotos.launchView(uri, "image/jpeg").use {
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
            compose.waitForIdle()
            block()
        }
        Thread.sleep(WRITE_MS)
    }

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val WRITE_MS = 500L
        const val FACES = 3
    }
}
