package dev.hfps.reveal.editor

import android.net.Uri
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
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.designsystem.components.RevealTextDialogTags
import dev.hfps.reveal.designsystem.components.slider.NumericInputTags
import dev.hfps.reveal.designsystem.components.slider.RevealSliderTags
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Snapshots and virtual copies in the editor (docs/adr/0025) on the real open flow (a synthetic JPEG through
 * `ACTION_VIEW`, filed in the library): save a snapshot from More ⋯, change the edit, apply the snapshot from History
 * panel (one undoable step), rename and delete it; "Create virtual copy" moves the editor to the copy, which has the
 * edit and its own history. Needs the editor's GL (the phone or a GPU emulator). Locale-independent.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SnapshotsFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)

    private val exposureValue: String
        get() = compose.onNodeWithTag(EditorTags.EXPOSURE).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)
            .orEmpty()
            .substringBefore(", ")

    @Test
    fun saveApplyRenameDeleteASnapshotAndCreateAVirtualCopy() {
        val uri = TestPhotos.insert(
            "reveal-snapshots-${System.nanoTime()}.jpg",
            "image/jpeg",
            TestPhotos.syntheticJpeg(),
        )
        try {
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                typeExposure("+1")
                // More ⋯ → Save snapshot…
                click(EditorTags.MORE)
                click(EditorTags.MENU_SAVE_SNAPSHOT)
                compose.waitUntilAtLeastOneExists(hasTestTag(RevealTextDialogTags.FIELD), TIMEOUT_MS)
                compose.onNodeWithTag(RevealTextDialogTags.FIELD).performTextReplacement("Bright")
                click(RevealTextDialogTags.CONFIRM)
                val versionId = waitForVersion()
                compose.waitUntil(TIMEOUT_MS) { snapshots(versionId).map { it.name } == listOf("Bright") }
                val snapshot = snapshots(versionId).single()

                typeExposure("-2")
                assertThat(exposureValue).isEqualTo("−2.00")

                // History panel: apply the snapshot (one step), undo goes back.
                click(EditorTags.MORE)
                click(EditorTags.MENU_HISTORY)
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.snapshot(snapshot.id)), TIMEOUT_MS)
                click(EditorTags.snapshot(snapshot.id))
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.historyStep(2)), TIMEOUT_MS)
                showLight()
                assertThat(exposureValue).isEqualTo("+1.00")
                click(EditorTags.UNDO)
                assertThat(exposureValue).isEqualTo("−2.00")

                // Rename, then delete from the snapshot's menu.
                click(EditorTags.MORE)
                click(EditorTags.MENU_HISTORY)
                click(EditorTags.snapshotMenu(snapshot.id))
                click(EditorTags.SNAPSHOT_RENAME)
                compose.waitUntilAtLeastOneExists(hasTestTag(RevealTextDialogTags.FIELD), TIMEOUT_MS)
                compose.onNodeWithTag(RevealTextDialogTags.FIELD).performTextReplacement("Bright sky")
                click(RevealTextDialogTags.CONFIRM)
                compose.waitUntil(TIMEOUT_MS) { snapshots(versionId).map { it.name } == listOf("Bright sky") }
                click(EditorTags.snapshotMenu(snapshot.id))
                click(EditorTags.SNAPSHOT_DELETE)
                compose.waitUntil(TIMEOUT_MS) { snapshots(versionId).isEmpty() }

                // More ⋯ → Create virtual copy: the editor moves to the copy, which has the edit.
                click(EditorTags.MORE)
                click(EditorTags.MENU_CREATE_COPY)
                val photoId = checkNotNull(runBlocking { access.versions().version(versionId) }).photoId
                compose.waitUntil(TIMEOUT_MS) { runBlocking { access.versions().versions(photoId) }.size == 2 }
                val copy = runBlocking { access.versions().versions(photoId) }.last()
                assertThat(copy.isCopy).isTrue()
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                showLight()
                compose.waitUntil(TIMEOUT_MS) { runCatching { exposureValue == "−2.00" }.getOrDefault(false) }
                assertThat(runBlocking { access.edits().load(copy.id) }.entries).hasSize(1)
                typeExposure("+0,5")
                Thread.sleep(WRITE_MS)
                // Each version keeps its own edit.
                assertThat(runBlocking { access.edits().recipe(copy.id) }.light.exposureEv).isEqualTo(0.5f)
                assertThat(runBlocking { access.edits().recipe(versionId) }.light.exposureEv).isEqualTo(-2f)
            }
        } finally {
            TestPhotos.delete(uri)
        }
    }

    /** The library version the editor opened (the photo filed on `ACTION_VIEW`). */
    private fun waitForVersion(): Long {
        var id: Long? = null
        compose.waitUntil(TIMEOUT_MS) {
            id = runBlocking {
                access.library().photoList(PhotoQuery(scope = LibraryScope.Recent)).firstOrNull()?.versionId
            }
            id != null
        }
        return checkNotNull(id)
    }

    private fun snapshots(versionId: Long) = runBlocking { access.versions().snapshots(versionId) }

    /** Opens Light unless its panel is showing (tapping the open tool closes its panel). */
    private fun showLight() {
        if (compose.onAllNodes(hasTestTag(EditorTags.EXPOSURE)).fetchSemanticsNodes().isNotEmpty()) return
        click(EditorTags.tool(ToolId.LIGHT))
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.EXPOSURE), TIMEOUT_MS)
    }

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private fun typeExposure(text: String) {
        showLight()
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
