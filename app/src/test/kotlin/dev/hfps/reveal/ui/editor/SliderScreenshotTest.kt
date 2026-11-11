package dev.hfps.reveal.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.components.slider.SliderGradients
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.panels.toSliderSpec
import dev.hfps.reveal.pipeline.recipe.ControlRange
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Goldens of [RevealSlider] states under `app/src/test/screenshots/editor/slider_*.png`. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class SliderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun states() = shoot("slider_states") { Sliders() }

    @Test fun statesFontScale200() = shoot("slider_states_font200") { Scaled(2f) { Sliders() } }

    @Test fun dragging() {
        composeRule.setContent { Sliders() }
        composeRule.onNodeWithTag("contrast").performTouchInput {
            down(Offset(width * 0.5f, height * 0.5f))
            moveBy(Offset(30f, 0f))
            moveBy(Offset(30f, 0f))
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/editor/slider_dragging.png")
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        composeRule.setContent(content)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/editor/$name.png")
    }

    @Composable
    private fun Sliders() {
        EditorTheme {
            Surface(
                color = RevealTheme.colors.card,
                modifier = Modifier
                    .fillMaxSize()
                    .background(RevealTheme.colors.background),
            ) {
                Column(Modifier.padding(vertical = 16.dp)) {
                    Row("Exposure", RecipeRanges.exposure, 0f)
                    Row("Exposure", RecipeRanges.exposure, -1.35f)
                    Row("Contrast", RecipeRanges.contrast, 42f, tag = "contrast")
                    Row("Amount", RecipeRanges.profileAmount, 150f)
                    Row("Temperature", RecipeRanges.temperature, 6450f, gradient = 1)
                    Row("Tint", RecipeRanges.tint, -23f, gradient = 2)
                    Row("Saturation", RecipeRanges.saturation, 100f)
                    Row("Whites", RecipeRanges.whites, -100f)
                    Row("Blacks (disabled)", RecipeRanges.blacks, 12f, enabled = false)
                }
            }
        }
    }

    @Composable
    private fun Row(
        label: String,
        range: ControlRange,
        value: Float,
        tag: String? = null,
        gradient: Int = 0,
        enabled: Boolean = true,
    ) {
        RevealSlider(
            label = label,
            value = value,
            spec = range.toSliderSpec(),
            format = range::format,
            onValueChange = {},
            onReset = {},
            enabled = enabled,
            trackBrush = when (gradient) {
                1 -> SliderGradients.Temperature
                2 -> SliderGradients.Tint
                else -> null
            },
            testTag = tag,
        )
    }

    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }
}
