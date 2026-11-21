package dev.hfps.reveal.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.settings.SettingsEntry
import dev.hfps.reveal.feature.settings.SettingsTags

/**
 * Moves through Settings by test tags (works in any language and however deep the screen is): opens the hub from
 * the library, an entry from the hub, and goes back until a screen shows instead of pressing Back a fixed number of
 * times.
 */
@OptIn(ExperimentalTestApi::class)
object SettingsSteps {
    private const val MAX_BACKS = 6

    /** From the library: Settings, then [entry]'s screen. */
    fun ComposeTestRule.openSettings(entry: SettingsEntry? = null, timeoutMs: Long = 20_000L) {
        waitUntilAtLeastOneExists(hasTestTag(LibraryTags.SETTINGS), timeoutMs)
        onNodeWithTag(LibraryTags.SETTINGS).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.SCREEN), timeoutMs)
        if (entry != null) openEntry(entry, timeoutMs)
    }

    /** From the hub: [entry]'s row. */
    fun ComposeTestRule.openEntry(entry: SettingsEntry, timeoutMs: Long = 20_000L) {
        waitUntilAtLeastOneExists(hasTestTag(entry.tag), timeoutMs)
        onNodeWithTag(entry.tag).performScrollTo().performClick()
        waitUntilDoesNotExist(hasTestTag(SettingsTags.SCREEN), timeoutMs)
    }

    /** Presses Back until the node tagged [tag] shows (the library's settings button by default). */
    fun ComposeTestRule.backTo(tag: String = LibraryTags.SETTINGS, timeoutMs: Long = 20_000L) {
        repeat(MAX_BACKS) {
            waitForIdle()
            if (onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()) return
            Espresso.pressBack()
        }
        waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMs)
    }
}
