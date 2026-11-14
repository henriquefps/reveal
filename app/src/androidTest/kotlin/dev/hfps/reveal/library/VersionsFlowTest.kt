package dev.hfps.reveal.library

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.designsystem.components.RevealTextDialogTags
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.R
import dev.hfps.reveal.feature.library.viewer.VersionsTags
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import dev.hfps.reveal.library.LibraryFilterSteps.openFolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Virtual copies end to end (docs/adr/0025), without the editor's GL (any emulator): a copy made from the library's
 * selection appears as its own named cell; rating the copy rates its photo; the viewer opens on the copy, its Versions
 * sheet renames it, makes a copy of the original and deletes a copy. Texts from resources, nodes by test tag.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VersionsFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = SyntheticFolder("versions-${System.nanoTime()}")
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)

    @After
    fun tearDown() = folder.delete()

    private fun rows(sourceId: Long): List<PhotoRow> =
        runBlocking { folder.library.photoList(PhotoQuery.ofSource(sourceId)) }

    @Suppress("LongMethod")
    @Test
    fun copiesAreCellsOfTheirOwnAndTheViewerManagesThem() {
        folder.jpeg("IMG_0001.JPG", 1)
        folder.jpeg("IMG_0002.JPG", 2)
        val (sourceId, _) = folder.addAndIndex()
        val photo = rows(sourceId).first { it.name == "IMG_0001.JPG" }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openFolder(sourceId, TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photo.id)), TIMEOUT_MS)

            // Select the photo, then "Create virtual copy" from the selection menu.
            compose.onNodeWithTag(LibraryTags.cell(photo.id)).performSemanticsAction(SemanticsActions.OnLongClick)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.SELECTION_MENU), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.SELECTION_MENU).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.CREATE_COPY), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.CREATE_COPY).performClick()
            compose.waitUntil(TIMEOUT_MS) { rows(sourceId).count { it.id == photo.id } == 2 }
            val copy = rows(sourceId).single { it.id == photo.id && it.versionPosition > 0 }
            assertThat(copy.conflict).isFalse()
            assertThat(copy.versionName).isEqualTo(context.getString(R.string.library_copy_name, 1))
            // The badge sits inside the cell's merged semantics.
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodes(hasTestTag(LibraryTags.copyBadge(copy.versionId)), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(LibraryTags.copyBadge(copy.versionId), useUnmergedTree = true)
                .assertTextEquals(copy.versionName!!)

            // Rating the copy alone rates its photo (culling is per photo).
            compose.onNodeWithTag(LibraryTags.copy(copy.versionId)).performSemanticsAction(SemanticsActions.OnLongClick)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.SELECTION_MENU), TIMEOUT_MS)
            runBlocking { folder.library.setRating(listOf(photo.id), 3) }
            compose.waitUntil(TIMEOUT_MS) { rows(sourceId).filter { it.id == photo.id }.all { it.rating == 3 } }

            // The viewer opens on the copy.
            compose.onNodeWithTag(LibraryTags.copy(copy.versionId)).performClick() // deselect
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodes(hasTestTag(LibraryTags.SELECTION_BAR)).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag(LibraryTags.copy(copy.versionId)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.VERSIONS), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasText(copy.versionName!!, substring = true), TIMEOUT_MS)

            // Versions sheet: rename the copy.
            compose.onNodeWithTag(ViewerTags.VERSIONS).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(VersionsTags.rename(copy.versionId)), TIMEOUT_MS)
            compose.onNodeWithTag(VersionsTags.rename(copy.versionId)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(RevealTextDialogTags.FIELD), TIMEOUT_MS)
            compose.onNodeWithTag(RevealTextDialogTags.FIELD).performTextReplacement("Black and white")
            compose.onNodeWithTag(RevealTextDialogTags.CONFIRM).performClick()
            compose.waitUntil(TIMEOUT_MS) {
                runBlocking { access.versions().version(copy.versionId)?.name } == "Black and white"
            }

            // A copy of the original: a third version, shown at once.
            compose.waitUntilAtLeastOneExists(hasTestTag(VersionsTags.COPY_ORIGINAL), TIMEOUT_MS)
            compose.onNodeWithTag(VersionsTags.COPY_ORIGINAL).performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { access.versions().versions(photo.id) }.size == 3 }
            val third = runBlocking { access.versions().versions(photo.id) }.last()
            assertThat(runBlocking { access.edits().recipe(third.id) }.isDefault()).isTrue()

            // Delete the renamed copy from the sheet.
            compose.onNodeWithTag(ViewerTags.VERSIONS).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(VersionsTags.delete(copy.versionId)), TIMEOUT_MS)
            compose.onNodeWithTag(VersionsTags.delete(copy.versionId)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(VersionsTags.DELETE_CONFIRM), TIMEOUT_MS)
            compose.onNodeWithTag(VersionsTags.DELETE_CONFIRM).performClick()
            compose.waitUntil(TIMEOUT_MS) {
                runBlocking { access.versions().versions(photo.id) }.map { it.id } == listOf(photo.versionId, third.id)
            }
            // The main version has no delete button (a photo always has one).
            assertThat(compose.onAllNodes(hasTestTag(VersionsTags.delete(photo.versionId))).fetchSemanticsNodes())
                .isEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
