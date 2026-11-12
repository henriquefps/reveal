package dev.hfps.reveal.library

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.feature.library.LibraryTags

/**
 * Drives the library home's filters (FIX/L) the way the owner does: the chips' ×, the Filters button, the sheet's
 * choices, Done. Choices go by their `PhotoListCodec` token (test tags), so the steps work in any language.
 */
@OptIn(ExperimentalTestApi::class)
object LibraryFilterSteps {
    /** Removes every active filter (their chips), then sets exactly [filters]' choices in the sheet. */
    fun ComposeTestRule.showOnly(filters: PhotoFilters, timeoutMs: Long = 20_000L) {
        waitUntilAtLeastOneExists(hasTestTag(LibraryTags.FILTERS), timeoutMs)
        clearFilters(timeoutMs)
        if (filters.isEmpty) return
        onNodeWithTag(LibraryTags.FILTERS).performClick()
        waitUntilAtLeastOneExists(hasTestTag(LibraryTags.FILTER_SHEET), timeoutMs)
        tokens(filters).forEach { token ->
            waitUntilAtLeastOneExists(hasTestTag(LibraryTags.filterChoice(token)), timeoutMs)
            onNodeWithTag(LibraryTags.filterChoice(token)).performScrollTo().performClick()
        }
        onNodeWithTag(LibraryTags.FILTER_SHEET_DONE).performClick()
        waitUntil(timeoutMs) { onAllNodes(hasTestTag(LibraryTags.FILTER_SHEET)).fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMs) { activeTokens().toSet() == tokens(filters).toSet() }
    }

    /** Manage folders → the folder: the home narrowed to [sourceId] alone. */
    fun ComposeTestRule.openFolder(sourceId: Long, timeoutMs: Long = 20_000L) {
        waitUntilAtLeastOneExists(hasTestTag(LibraryTags.FOLDERS), timeoutMs)
        onNodeWithTag(LibraryTags.FOLDERS).performClick()
        waitUntilAtLeastOneExists(hasTestTag(LibraryTags.source(sourceId)), timeoutMs)
        onNodeWithTag(LibraryTags.source(sourceId)).performClick()
        waitUntil(timeoutMs) { onAllNodes(hasTestTag(LibraryTags.FOLDERS_SHEET)).fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMs) { activeTokens() == listOf("f:$sourceId") }
    }

    /** Taps the × of every active chip. */
    fun ComposeTestRule.clearFilters(timeoutMs: Long = 20_000L) {
        while (true) {
            val token = activeTokens().firstOrNull() ?: return
            onNodeWithTag(LibraryTags.activeFilter(token)).performScrollTo().performClick()
            waitUntil(timeoutMs) { token !in activeTokens() }
        }
    }

    /** The tokens of every choice in [filters], one per chip. */
    fun tokens(filters: PhotoFilters): List<String> =
        PhotoListCodec.encodeFilters(filters).split(',').filter { it.isNotEmpty() }

    /** The active chips' tokens above the grid. */
    fun ComposeTestRule.activeTokens(): List<String> {
        val prefix = LibraryTags.activeFilter("")
        val matcher = SemanticsMatcher("test tag starts with $prefix") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }
        return onAllNodes(matcher).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix(prefix)
        }
    }
}
