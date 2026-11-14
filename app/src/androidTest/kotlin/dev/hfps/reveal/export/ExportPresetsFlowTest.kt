package dev.hfps.reveal.export

import android.Manifest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.designsystem.components.RevealTextDialogTags
import dev.hfps.reveal.feature.export.model.BuiltInExportPreset
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.ui.ExportTags
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.library.LibraryFilterSteps.openFolder
import dev.hfps.reveal.library.SyntheticFolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export presets in the sheet (docs/adr/0025), opened from the library's selection (a batch): a built-in sets the
 * options in one tap, the current options are saved as a user preset, it is renamed, updated and deleted. The sheet's
 * options are read back from what it remembers (the last options), so nothing depends on the language.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ExportPresetsFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val folder = SyntheticFolder("export-presets-${System.nanoTime()}")
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)

    @Before
    fun grantNotifications() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        folder.delete()
        runBlocking {
            access.exportPresets().all().filter {
                it.name.startsWith(PREFIX)
            }.forEach { access.exportPresets().delete(it.uuid) }
        }
    }

    private fun lastOptions(): ExportOptions = ExportOptions.decode(
        context.getSharedPreferences("export", android.content.Context.MODE_PRIVATE).getString("lastOptions", null),
    )

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
    }

    @Test
    fun pickSaveRenameUpdateAndDeleteAPreset() {
        folder.jpeg("IMG_0001.JPG", 1)
        folder.jpeg("IMG_0002.JPG", 2)
        val (sourceId, _) = folder.addAndIndex()
        val photos = runBlocking { folder.library.photoList(dev.hfps.reveal.data.model.PhotoQuery.ofSource(sourceId)) }
        val name = "$PREFIX${System.nanoTime() % 10_000}"
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openFolder(sourceId, TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photos[0].id)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.cell(photos[0].id)).performSemanticsAction(SemanticsActions.OnLongClick)
            compose.onNodeWithTag(LibraryTags.cell(photos[1].id)).performClick()
            // The export action is a button on wide windows, in the selection menu on phones.
            if (compose.onAllNodes(hasTestTag(LibraryTags.EXPORT)).fetchSemanticsNodes().isEmpty()) {
                click(LibraryTags.SELECTION_MENU)
            }
            click(LibraryTags.EXPORT)
            compose.waitUntilAtLeastOneExists(hasTestTag(ExportTags.PRESETS), TIMEOUT_MS)

            // A built-in sets every option in one tap.
            click(ExportTags.preset("builtin:web"))
            compose.waitUntil(TIMEOUT_MS) { lastOptions() == BuiltInExportPreset.WEB.options }

            // Change one option, save as a preset.
            click(ExportTags.DEST_SHARE)
            compose.waitUntil(TIMEOUT_MS) { lastOptions().destination != BuiltInExportPreset.WEB.options.destination }
            click(ExportTags.PRESET_SAVE)
            compose.waitUntilAtLeastOneExists(hasTestTag(RevealTextDialogTags.FIELD), TIMEOUT_MS)
            compose.onNodeWithTag(RevealTextDialogTags.FIELD).performTextReplacement(name)
            compose.onNodeWithTag(RevealTextDialogTags.CONFIRM).performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { access.exportPresets().all() }.any { it.name == name } }
            val saved = runBlocking { access.exportPresets().all() }.single { it.name == name }
            assertThat(ExportOptions.decode(saved.optionsJson)).isEqualTo(lastOptions())
            compose.waitUntilAtLeastOneExists(hasText(name), TIMEOUT_MS)

            // Rename it.
            click(ExportTags.PRESET_RENAME)
            compose.waitUntilAtLeastOneExists(hasTestTag(RevealTextDialogTags.FIELD), TIMEOUT_MS)
            compose.onNodeWithTag(RevealTextDialogTags.FIELD).performTextReplacement("$name b")
            compose.onNodeWithTag(RevealTextDialogTags.CONFIRM).performClick()
            compose.waitUntil(TIMEOUT_MS) {
                runBlocking { access.exportPresets().all() }.any { it.uuid == saved.uuid && it.name == "$name b" }
            }

            // Another built-in, then back to the user preset: its options come back.
            click(ExportTags.preset("builtin:full"))
            compose.waitUntil(TIMEOUT_MS) { lastOptions() == BuiltInExportPreset.FULL_QUALITY.options }
            click(ExportTags.preset(saved.uuid))
            compose.waitUntil(TIMEOUT_MS) { lastOptions() == ExportOptions.decode(saved.optionsJson) }

            // Adjust it and update the preset.
            click(ExportTags.DEST_PICTURES)
            click(ExportTags.PRESET_UPDATE)
            compose.waitUntil(TIMEOUT_MS) {
                runBlocking { access.exportPresets().all() }.single { it.uuid == saved.uuid }
                    .let { ExportOptions.decode(it.optionsJson) } == lastOptions()
            }

            // Delete it.
            click(ExportTags.PRESET_DELETE)
            compose.waitUntil(TIMEOUT_MS) {
                runBlocking { access.exportPresets().all() }.none { it.uuid == saved.uuid }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val PREFIX = "Test preset "
    }
}
