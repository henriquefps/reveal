package dev.hfps.reveal.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.CullingFilter
import dev.hfps.reveal.data.model.EditFilter
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceFilter
import dev.hfps.reveal.data.model.TypeFilter
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.library.LibraryFilterSteps.activeTokens
import dev.hfps.reveal.library.LibraryFilterSteps.openFolder
import dev.hfps.reveal.library.LibraryFilterSteps.showOnly
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library home with combinable filters (FIX/L) on a device: two folders, every photo in one grid; "Manage folders"
 * narrows to a folder; filters combine OR within a group and AND across; a chip's × removes one; the last
 * combination comes back after a restart. Synthetic photos only; nodes by test tag (any locale).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryFilterFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val run = (System.nanoTime() % 100_000).toInt()
    private val card = SyntheticFolder("filters-card-$run")
    private val phone = SyntheticFolder("filters-phone-$run")

    @After
    fun tearDown() {
        runBlocking { card.library.setLastFilters(PhotoFilters.NONE) }
        card.delete()
        phone.delete()
    }

    private fun cellShown(id: Long) =
        compose.onAllNodes(hasTestTag(LibraryTags.cell(id))).fetchSemanticsNodes().isNotEmpty()

    /** Waits until exactly [shown] of [all] are in the grid (the grid is small: every cell is composed). */
    private fun waitForCells(all: Map<String, Long>, vararg shown: String) {
        compose.waitUntil(TIMEOUT_MS) { all.all { (name, id) -> cellShown(id) == (name in shown) } }
    }

    @Test
    fun filtersCombineAndComeBackAfterARestart() {
        card.pair("DSC$run", run)
        card.jpeg("IMG${run}A.JPG", run + 1)
        phone.jpeg("PXL${run}B.JPG", run + 2)
        val (cardId, _) = card.addAndIndex()
        val (phoneId, _) = phone.addAndIndex(alongside = true)
        val photos = runBlocking {
            card.library.photoList(
                PhotoQuery(filters = PhotoFilters.folder(cardId).toggle(SourceFilter.Folder(phoneId))),
            )
        }.associate { it.name to it.id }
        val raw = photos.getValue("DSC$run.ARW")
        val jpeg = photos.getValue("IMG${run}A.JPG")
        val other = photos.getValue("PXL${run}B.JPG")
        runBlocking {
            card.library.setRating(listOf(raw, other), 3)
            card.library.setFlag(listOf(jpeg), PickFlag.PICK)
            card.library.setFlag(listOf(other), PickFlag.REJECT)
            card.library.setLastFilters(PhotoFilters.NONE)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            // The home: every photo of both folders in one grid.
            compose.showOnly(PhotoFilters.NONE)
            compose.showOnly(PhotoFilters.folder(cardId).toggle(SourceFilter.Folder(phoneId)))
            waitForCells(photos, *photos.keys.toTypedArray())

            // Manage folders → a folder: the home narrowed to it.
            compose.openFolder(phoneId, TIMEOUT_MS)
            waitForCells(photos, "PXL${run}B.JPG")

            // OR within a group, AND across groups.
            val both = PhotoFilters.folder(cardId).toggle(SourceFilter.Folder(phoneId))
            compose.showOnly(both.toggle(CullingFilter.STARRED))
            waitForCells(photos, "DSC$run.ARW", "PXL${run}B.JPG")
            compose.showOnly(both.toggle(CullingFilter.STARRED).toggle(CullingFilter.PICKED))
            waitForCells(photos, *photos.keys.toTypedArray())
            compose.showOnly(both.toggle(CullingFilter.STARRED).toggle(TypeFilter.JPEG))
            waitForCells(photos, "PXL${run}B.JPG")
            compose.showOnly(both.toggle(CullingFilter.REJECTED).toggle(TypeFilter.RAW))
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.EMPTY), TIMEOUT_MS)
            waitForCells(photos)

            // A chip's × removes that choice only.
            compose.onNodeWithTag(LibraryTags.activeFilter("t:RAW")).performScrollTo().performClick()
            waitForCells(photos, "PXL${run}B.JPG")
            compose.showOnly(both.toggle(EditFilter.NOT_EDITED).toggle(CullingFilter.PICKED))
            waitForCells(photos, "IMG${run}A.JPG")
        }

        // A restart shows the same combination.
        ActivityScenario.launch(MainActivity::class.java).use {
            val expected = LibraryFilterSteps.tokens(
                PhotoFilters.folder(cardId).toggle(SourceFilter.Folder(phoneId)).toggle(EditFilter.NOT_EDITED)
                    .toggle(CullingFilter.PICKED),
            )
            compose.waitUntil(TIMEOUT_MS) { compose.activeTokens().toSet() == expected.toSet() }
            waitForCells(photos, "IMG${run}A.JPG")
            // "Clear all" goes back to every photo.
            compose.onNodeWithTag(LibraryTags.FILTERS_CLEAR).performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.activeTokens().isEmpty() }
            compose.waitUntil(TIMEOUT_MS) { runBlocking { card.library.lastFilters() } == PhotoFilters.NONE }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
