package dev.hfps.reveal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hfps.reveal.ui.start.StartTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app launches, Hilt builds its graph, the start screen offers "Open photo" and its menu reaches the gallery. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startScreenOffersOpenPhoto() {
        composeRule.onNodeWithTag(StartTags.OPEN).assertIsDisplayed()
    }

    @Test
    fun menuReachesTheGalleryAndAbout() {
        composeRule.onNodeWithTag(StartTags.MENU).performClick()
        composeRule.onNodeWithText("Design system").performClick()
        composeRule.onNodeWithText("Foundations").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(StartTags.MENU).performClick()
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("LibRaw 0.22.3").assertIsDisplayed()
    }
}
