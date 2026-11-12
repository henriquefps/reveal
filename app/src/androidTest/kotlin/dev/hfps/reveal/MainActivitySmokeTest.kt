package dev.hfps.reveal

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.R as LibraryR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app launches, Hilt builds its graph, the library is the start screen; its menu reaches the gallery and About. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** Texts come from the app's resources so the test passes in any device locale. */
    private fun text(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun theLibraryIsTheStartScreen() {
        // An empty library offers "Add folder"; one with folders lists them (and still offers it); one with only
        // photos other apps handed over opens on them (ADR 0011).
        composeRule.waitUntilAtLeastOneExists(
            hasTestTag(LibraryTags.ADD_FOLDER) or hasTestTag(LibraryTags.SOURCES) or hasTestTag(LibraryTags.GRID),
            10_000,
        )
        composeRule.onNodeWithTag(LibraryTags.MENU).assertIsDisplayed()
    }

    @Test
    fun menuReachesTheGalleryAndAbout() {
        composeRule.onNodeWithTag(LibraryTags.MENU).performClick()
        composeRule.onNodeWithText(text(LibraryR.string.library_menu_gallery)).performClick()
        composeRule.onNodeWithText(text(R.string.gallery_page_foundations)).assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(LibraryTags.MENU).performClick()
        composeRule.onNodeWithText(text(LibraryR.string.library_menu_about)).performClick()
        composeRule.onNodeWithText("LibRaw 0.22.3").assertIsDisplayed()
    }
}
