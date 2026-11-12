package dev.hfps.reveal.ui.editor

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hfps.reveal.designsystem.components.slider.NumericInputTags
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.components.slider.RevealSliderTags
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.feature.editor.panels.toSliderSpec
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The value's tap target (numeric input) ends at the same place on every density: a tap on the track's far end does
 * not open the numeric input, a tap on the value does. It was 24 raw pixels, which on the Tab S9 Ultra (1.75x)
 * reached the track (`EditorInteractionTest` on the tablet opened the input instead of tapping the track).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w600dp-h900dp-mdpi")
class SliderTapTargetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theTrackEndIsNotTheValueOnATablet() = check(TABLET_DENSITY)

    @Test
    fun theTrackEndIsNotTheValueOnAPhone() = check(PHONE_DENSITY)

    private fun check(density: Float) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                EditorTheme {
                    Surface {
                        RevealSlider(
                            label = "Exposure",
                            value = 0f,
                            spec = RecipeRanges.exposure.toSliderSpec(),
                            format = RecipeRanges.exposure::format,
                            onValueChange = {},
                            onReset = {},
                            testTag = TAG,
                        )
                    }
                }
            }
        }
        // EditorInteractionTest's tap: the far right of the row, on the track's line.
        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(width * 0.95f, height * 0.7f)) }
        composeRule.mainClock.advanceTimeBy(DOUBLE_TAP_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NumericInputTags.FIELD).assertDoesNotExist()
        // The value itself still opens it.
        composeRule.onNodeWithTag(TAG + RevealSliderTags.VALUE_SUFFIX, useUnmergedTree = true)
            .performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(DOUBLE_TAP_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NumericInputTags.FIELD).assertExists()
    }

    private companion object {
        const val TAG = "exposure"
        const val TABLET_DENSITY = 1.75f
        const val PHONE_DENSITY = 2.8125f
        const val DOUBLE_TAP_MS = 500L
    }
}
