package dev.hfps.reveal.editor

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.preset.GroupKind
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.designsystem.components.slider.NumericInputTags
import dev.hfps.reveal.designsystem.components.slider.RevealSliderTags
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.feature.library.presets.PresetBatchWorker
import dev.hfps.reveal.pipeline.preset.PackGroup
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetIds
import dev.hfps.reveal.pipeline.preset.PresetPack
import dev.hfps.reveal.pipeline.preset.PresetPacks
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Presets on the real editor (docs/adr/0019): applying is one step, the strength slider replaces that step (typed and
 * dragged), creating a preset from an edit, a pack opened from another app lands on trial and is kept from the panel,
 * and the library's batch apply on 50 photos. Locale-independent (tags only); every run uses new ids and names.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PresetsFlowTest {
    @get:org.junit.Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val presets = access.presets()

    private val history: EditHistory get() = checkNotNull(EditorMetrics.history).invoke()

    private val contrastPreset = PresetPatch.fromSections(
        Recipe(light = Light(contrast = 40f)),
        listOf(PresetSection.CONTRAST),
    )

    /** A user group of its own with one preset; returns (group uuid, preset uuid). */
    private fun userPreset(name: String): Pair<String, String> = runBlocking {
        val groupId = presets.createGroup("Test ${System.nanoTime()}")
        val uuid = presets.create(name, contrastPreset, groupId)
        val group = presets.library().first().groups.first { it.rowId == groupId }
        group.uuid to uuid
    }

    @Test
    fun applyingIsOneStepAndStrengthReplacesIt() {
        val (group, uuid) = userPreset("Crisp")
        val uri = TestPhotos.insert("reveal-preset-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            open(uri) {
                openPresets(group)
                click(EditorTags.preset(uuid))
                compose.waitUntil(TIMEOUT_MS) { history.entries.size == 1 }
                val step = history.entries.single()
                assertThat(step.label.action).isEqualTo(HistoryAction.PRESET)
                assertThat(step.label.name).isEqualTo("Crisp")
                assertThat(history.current.light.contrast).isEqualTo(40f)

                // Typed strength: the same step, at 50 %.
                compose.onNodeWithTag(
                    EditorTags.PRESET_STRENGTH + RevealSliderTags.VALUE_SUFFIX,
                    useUnmergedTree = true,
                )
                    .performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
                compose.onNodeWithTag(NumericInputTags.FIELD).performTextReplacement("50")
                compose.onNodeWithTag(NumericInputTags.DONE).performClick()
                compose.waitUntil(TIMEOUT_MS) { history.current.light.contrast == 20f }
                assertThat(history.entries).hasSize(1)
                assertThat(history.entries.single().label.name).isEqualTo("Crisp")

                // A real drag: still one step, at the new strength.
                compose.onNodeWithTag(EditorTags.PRESET_STRENGTH).performTouchInput { swipeRight() }
                compose.waitUntil(TIMEOUT_MS) { history.current.light.contrast > 20f }
                assertThat(history.entries).hasSize(1)
                compose.onNodeWithTag(EditorTags.PRESET_STRENGTH).performTouchInput { swipeLeft() }
                compose.waitForIdle()
                // Strength 0 removes the step (back to the photo as it was).
                compose.waitUntil(TIMEOUT_MS) { history.entries.isEmpty() }
                assertThat(history.current.light.contrast).isEqualTo(0f)
                compose.onNodeWithTag(EditorTags.PRESET_STRENGTH).performTouchInput { swipeRight() }
                compose.waitUntil(TIMEOUT_MS) { history.entries.size == 1 }
            }
            // Reopened from Room: the one named step is there.
            open(uri) {
                assertThat(history.entries).hasSize(1)
                assertThat(history.entries.single().label.action).isEqualTo(HistoryAction.PRESET)
                assertThat(history.entries.single().label.name).isEqualTo("Crisp")
            }
            // The preset is now recent.
            assertThat(runBlocking { presets.library().first() }.recents.map { it.uuid }).contains(uuid)
        } finally {
            TestPhotos.delete(uri)
            cleanUp(group)
        }
    }

    @Test
    fun aPresetIsCreatedFromTheEdit() {
        val uri = TestPhotos.insert("reveal-create-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        val name = "Made here ${System.nanoTime()}"
        val groupName = "Group ${System.nanoTime()}"
        try {
            open(uri) {
                compose.onNodeWithTag(
                    EditorTags.control(ControlId.CONTRAST) + RevealSliderTags.VALUE_SUFFIX,
                    useUnmergedTree = true,
                )
                    .performScrollTo().performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag(NumericInputTags.FIELD), TIMEOUT_MS)
                compose.onNodeWithTag(NumericInputTags.FIELD).performTextReplacement("35")
                compose.onNodeWithTag(NumericInputTags.DONE).performClick()
                compose.waitUntil(TIMEOUT_MS) { history.current.light.contrast == 35f }
                click(EditorTags.tool(ToolId.PRESETS))
                click(EditorTags.PRESET_CREATE)
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PRESET_NAME), TIMEOUT_MS)
                compose.onNodeWithTag(EditorTags.PRESET_NAME).performTextReplacement(name)
                click(EditorTags.PRESET_NEW_GROUP)
                compose.onNodeWithTag("${EditorTags.PRESET_NEW_GROUP}-name").performTextReplacement(groupName)
                click("${EditorTags.PRESET_SECTIONS}-confirm")
                compose.waitUntil(TIMEOUT_MS) {
                    runBlocking { presets.library().first() }.all.any { it.name == name }
                }
            }
            val library = runBlocking { presets.library().first() }
            val created = library.all.single { it.name == name }
            assertThat(library.groups.single { it.uuid == created.groupUuid }.name).isEqualTo(groupName)
            assertThat(created.preset.patch.paths).contains("light.contrast")
            // Contrast was ticked (the edit changed it), exposure was not touched.
            assertThat(created.preset.patch.applyFull(Recipe.DEFAULT).light.contrast).isEqualTo(35f)
            assertThat(created.preset.patch.paths).doesNotContain("light.exposure")
            cleanUp(created.groupUuid)
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun aPackOpenedFromAnotherAppIsTriedThenKept() {
        val pack = pack()
        val bytes = ByteArrayOutputStream().also { PresetPacks.write(pack, it) }.toByteArray()
        val file = TestPhotos.insert("reveal-${System.nanoTime()}.revealpack", PresetPacks.MIME, bytes, unique = false)
        val photo = TestPhotos.insert("reveal-pack-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(file, PresetPacks.MIME)
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(TIMEOUT_MS) {
                    runBlocking { presets.library().first() }.trialPacks.any { it.uuid == pack.id }
                }
            }
            val group = runBlocking { presets.library().first() }.groups.first { it.pack?.uuid == pack.id }
            assertThat(group.kind).isEqualTo(GroupKind.PACK)
            assertThat(group.presets.map { it.uuid }).containsExactlyElementsIn(pack.presets.map { it.id })
            open(photo) {
                openPresets(group.uuid)
                // Try one on the photo, then keep the pack from the panel's banner.
                click(EditorTags.preset(pack.presets.first().id))
                compose.waitUntil(TIMEOUT_MS) { history.entries.size == 1 }
                click(EditorTags.PRESET_KEEP_PACK)
                compose.waitUntil(TIMEOUT_MS) {
                    runBlocking { presets.library().first() }.trialPacks.none { it.uuid == pack.id }
                }
            }
        } finally {
            TestPhotos.delete(file)
            TestPhotos.delete(photo)
            runBlocking {
                presets.library().first().groups.firstOrNull { it.pack?.uuid == pack.id }?.pack?.let {
                    presets.removePack(it.rowId)
                }
            }
        }
    }

    @Test
    fun batchAppliesOneStepToEachOfFiftyPhotos() {
        val (group, uuid) = userPreset("Batch ${System.nanoTime()}")
        val db = access.database()
        val sourceId = runBlocking {
            db.sourceDao().insert(
                SourceEntity(
                    treeUri = "content://test/batch-${System.nanoTime()}",
                    rootDocumentId = "root",
                    displayName = "Batch",
                    volumeId = null,
                    volumeName = null,
                    state = SourceState.AVAILABLE,
                    writable = false,
                    addedAt = 0,
                ),
            )
        }
        try {
            val versions = runBlocking {
                (0 until PHOTOS).map { i ->
                    val photo = db.photoDao().insertPhoto(
                        PhotoEntity(
                            sourceId = sourceId, folder = "", folderDocumentId = "", stemKey = "b$i",
                            name = "B$i.JPG", format = PhotoFormat.JPEG, hasRaw = false, sortTime = 0, addedAt = 0,
                        ),
                    )
                    db.photoDao().insertVersion(VersionEntity(photoId = photo, createdAt = 0))
                }
            }
            val work = PresetBatchWorker.enqueue(context, uuid, 0.5f, versions)
            val manager = WorkManager.getInstance(context)
            compose.waitUntil(BATCH_TIMEOUT_MS) { manager.getWorkInfoById(work).get()?.state?.isFinished == true }
            assertThat(manager.getWorkInfoById(work).get()!!.state).isEqualTo(WorkInfo.State.SUCCEEDED)
            val edits = access.edits()
            runBlocking {
                for (version in versions) {
                    val loaded = edits.load(version)
                    assertThat(loaded.entries).hasSize(1)
                    assertThat(loaded.entries.single().label.action).isEqualTo(HistoryAction.PRESET)
                    assertThat(loaded.current.light.contrast).isEqualTo(20f)
                }
                // Undo on one photo leaves the others.
                edits.undo(versions.first())
                assertThat(edits.recipe(versions.first()).light.contrast).isEqualTo(0f)
                assertThat(edits.recipe(versions.last()).light.contrast).isEqualTo(20f)
            }
        } finally {
            runBlocking { db.sourceDao().delete(sourceId) }
            cleanUp(group)
        }
    }

    private fun pack(): PresetPack {
        val group = PackGroup(PresetIds.newId(), "Pack group")
        val presets = (1..3).map { i ->
            Preset(PresetIds.newId(), "Pack preset $i", contrastPreset, group = group.name)
        }
        return PresetPack(
            id = PresetIds.newId(),
            name = "Test pack",
            author = "Ana Example",
            version = 1,
            licenceName = "CC0",
            licenceText = "No rights reserved.",
            link = "https://example.com/pack",
            groups = listOf(group),
            presets = presets,
            groupOf = presets.associate { it.id to group.id },
            cover = null,
        )
    }

    private fun cleanUp(groupUuid: String) = runBlocking {
        presets.library().first().groups.firstOrNull { it.uuid == groupUuid }?.rowId?.let { presets.deleteGroup(it) }
    }

    private fun open(uri: Uri, block: () -> Unit) {
        TestPhotos.launchView(uri, "image/jpeg").use {
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
            compose.waitForIdle()
            block()
        }
        Thread.sleep(WRITE_MS)
    }

    private fun openPresets(group: String) {
        click(EditorTags.tool(ToolId.PRESETS))
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.presetChip(group)), TIMEOUT_MS)
        click(EditorTags.presetChip(group))
    }

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val BATCH_TIMEOUT_MS = 120_000L
        const val WRITE_MS = 500L
        const val PHOTOS = 50
    }
}
