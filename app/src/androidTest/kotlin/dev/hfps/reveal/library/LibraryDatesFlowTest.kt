package dev.hfps.reveal.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.CameraFilter
import dev.hfps.reveal.data.model.DateFilter
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.feature.library.DateIndex
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.library.LibraryFilterSteps.activeTokens
import dev.hfps.reveal.library.LibraryFilterSteps.showOnly
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Navigating a library by date (W3/D) on a device: month headers and the sticky month, "Go to date" (counts, empty
 * months greyed), the fast scroller landing on the month its bubble named, the Camera filter, a date range filter.
 * Synthetic photos only (twelve months, two cameras); every node by test tag or semantics (any locale).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryDatesFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val run = (System.nanoTime() % 100_000).toInt()
    private val folder = SyntheticFolder("dates-$run")
    private val nikon = "NIKON CORPORATION" to "NIKON D5600"
    private val sony = "SONY" to "ZV-E10"

    /** September 2025 to August 2026 without December 2025; Nikon in the autumn, Sony the rest. */
    private val months = (0 until 12).map { LocalDate.of(2025, 9, 1).plusMonths(it.toLong()) }
        .filter { it.monthValue != 12 }

    private fun key(date: LocalDate) = DateIndex.of(date.year, date.monthValue)

    private fun nikonMonth(date: LocalDate) = date.year == 2025

    @Before
    fun setUp() {
        var seed = run
        for (month in months) {
            for (i in 0 until PER_MONTH) {
                val day = 1 + i * 3
                val stamp = "%04d:%02d:%02d 12:%02d:%02d".format(month.year, month.monthValue, day, i, run % 60)
                folder.jpegAt(
                    name = "D${run}_${month.year}${month.monthValue}_$i.JPG",
                    seed = seed++,
                    dateTime = stamp,
                    camera = if (nikonMonth(month)) nikon else sony,
                    lens = if (nikonMonth(month)) "18.0-55.0 mm f/3.5-5.6" else "E PZ 16-50mm F3.5-5.6 OSS",
                )
            }
        }
        folder.addAndIndex()
        runBlocking { folder.library.setLastFilters(PhotoFilters.NONE) }
    }

    @After
    fun tearDown() {
        runBlocking { folder.library.setLastFilters(PhotoFilters.NONE) }
        folder.delete()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)

    private fun stateOf(tag: String): String? = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun textOf(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().let { node ->
            node.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text }
        }

    @Test
    fun headersGoToDateAndTheScrollerLandOnTheirMonth() {
        val newest = months.last()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.showOnly(PhotoFilters.NONE)
            // Month headers and the sticky month.
            waitFor(LibraryTags.monthHeader(key(newest)))
            waitFor(LibraryTags.STICKY_MONTH)

            // "Go to date": an empty month is greyed (not tappable); a month jumps the grid to its header.
            compose.onNodeWithTag(LibraryTags.MENU).performClick()
            waitFor(LibraryTags.GO_TO_DATE)
            compose.onNodeWithTag(LibraryTags.GO_TO_DATE).performClick()
            waitFor(LibraryTags.GO_TO_DATE_SHEET)
            val december = DateIndex.of(2025, 12)
            compose.onNodeWithTag(LibraryTags.month(december)).performScrollTo().assertIsNotEnabled()
            val target = months[2]
            compose.onNodeWithTag(LibraryTags.month(key(target))).performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) { !exists(LibraryTags.GO_TO_DATE_SHEET) }
            waitFor(LibraryTags.monthHeader(key(target)))
            compose.waitUntil(TIMEOUT_MS) { !exists(LibraryTags.monthHeader(key(newest))) }

            // The fast scroller: shown by a scroll; dragged to the middle (where a jump puts its item at the top),
            // the grid lands on the month its bubble named.
            val gridHeight = compose.onNodeWithTag(LibraryTags.GRID).fetchSemanticsNode().size.height
            compose.onNodeWithTag(LibraryTags.GRID).performScrollToIndex(0)
            waitFor(LibraryTags.SCROLLER)
            compose.onNodeWithTag(LibraryTags.SCROLLER).performTouchInput {
                down(center)
                moveBy(Offset(0f, gridHeight * DRAG_FRACTION))
            }
            waitFor(LibraryTags.SCROLLER_BUBBLE)
            val bubble = textOf(LibraryTags.SCROLLER_BUBBLE)
            compose.onNodeWithTag(LibraryTags.SCROLLER).performTouchInput { up() }
            compose.waitUntil(TIMEOUT_MS) { !exists(LibraryTags.SCROLLER_BUBBLE) }
            check(bubble.isNotBlank()) { "the bubble named no month" }
            compose.waitUntil(TIMEOUT_MS) { stateOf(LibraryTags.SCROLLER) == bubble }
            check(!exists(LibraryTags.monthHeader(key(newest)))) { "the drag did not move the grid" }
        }
    }

    @Test
    fun cameraAndDateFiltersNarrowTheMonths() {
        // Newest first: the newest Nikon month heads the filtered grid.
        val autumn = months.last { nikonMonth(it) }
        val summer = months.last()
        ActivityScenario.launch(MainActivity::class.java).use {
            // Camera: only the Nikon months stay.
            compose.showOnly(PhotoFilters(cameras = setOf(CameraFilter(nikon.first, nikon.second))))
            waitFor(LibraryTags.monthHeader(key(autumn)))
            compose.waitUntil(TIMEOUT_MS) { !exists(LibraryTags.monthHeader(key(summer))) }
            val nikonPhotos = runBlocking {
                folder.library.photoList(
                    PhotoQuery(filters = PhotoFilters(cameras = setOf(CameraFilter(nikon.first, nikon.second)))),
                )
            }
            assertEquals(months.count { nikonMonth(it) } * PER_MONTH, nikonPhotos.count { it.name.startsWith("D$run") })
        }
        // A range set before a start comes back as its chip and narrows the grid to its month.
        val range = DateFilter.Range(summer.withDayOfMonth(1), summer.withDayOfMonth(summer.lengthOfMonth()))
        runBlocking { folder.library.setLastFilters(PhotoFilters(dates = setOf(range))) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(TIMEOUT_MS) {
                compose.activeTokens() ==
                    LibraryFilterSteps.tokens(PhotoFilters(dates = setOf(range)))
            }
            waitFor(LibraryTags.monthHeader(key(summer)))
            compose.waitUntil(TIMEOUT_MS) { !exists(LibraryTags.monthHeader(key(months[months.size - 2]))) }
            val sorted = runBlocking {
                folder.library.photoList(
                    PhotoQuery(filters = PhotoFilters(dates = setOf(range)), sort = PhotoSort.CAPTURE_NEWEST),
                )
            }
            assertEquals(PER_MONTH, sorted.count { it.name.startsWith("D$run") })
        }
    }

    private fun assertEquals(expected: Int, actual: Int) = org.junit.Assert.assertEquals(expected, actual)

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val PER_MONTH = 9
        const val DRAG_FRACTION = 0.4f
    }
}
