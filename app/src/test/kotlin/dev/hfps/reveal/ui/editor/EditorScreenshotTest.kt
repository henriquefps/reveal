package dev.hfps.reveal.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.reveal.designsystem.components.slider.NumericInputContent
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditState
import dev.hfps.reveal.feature.editor.EditorActions
import dev.hfps.reveal.feature.editor.EditorContent
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.ViewOptions
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.layout.PanelSnap
import dev.hfps.reveal.feature.editor.panels.toSliderSpec
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.Histogram
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the M1 editor under `app/src/test/screenshots/editor/`, on a synthetic photo (the pipeline's demo
 * scene developed by the CPU reference; the GPU canvas itself is covered by the instrumented tests).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class EditorPhoneScreenshotTest : EditorScreenshotBase("phone") {
    @Test fun light() = shoot("light") { Editor() }

    @Test fun colour() = shoot("colour") { Editor(tool = ToolId.COLOUR) }

    @Test fun expanded() = shoot("expanded") { Editor(snap = PanelSnap.EXPANDED) }

    @Test fun collapsed() = shoot("collapsed") { Editor(snap = PanelSnap.COLLAPSED) }

    @Test fun profileSubPanel() {
        composeRule.setContent { Editor() }
        composeRule.onNodeWithTag(EditorTags.PROFILE_ROW).performClick()
        capture("profile")
    }

    @Test fun draggingASlider() {
        composeRule.setContent { Editor() }
        // A finger down on the exposure row, moved right and held: the rest of the panel fades out.
        composeRule.onNodeWithTag(EditorTags.EXPOSURE).performTouchInput {
            down(Offset(width * 0.3f, height * 0.5f))
            moveBy(Offset(40f, 0f))
            moveBy(Offset(60f, 0f))
        }
        composeRule.waitForIdle()
        capture("dragging")
    }

    @Test fun splitBeforeAfter() = shoot("split") {
        Editor(view = ViewOptions(compare = CompareMode.Split(leftRight = true, position = 0.5f)))
    }

    @Test fun sideBySide() = shoot("side_by_side") { Editor(view = ViewOptions(compare = CompareMode.SideBySide)) }

    @Test fun clippingOn() = shoot("clipping") {
        Editor(view = ViewOptions(clipping = ClippingWarnings(shadows = true, highlights = true)))
    }

    @Test fun lightFontScale200() = shoot("light_font200") { Scaled(2f) { Editor() } }

    @Test fun colourFontScale200() = shoot("colour_font200") { Scaled(2f) { Editor(tool = ToolId.COLOUR) } }

    @Test fun numericInput() = shoot("numeric_input") {
        EditorTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(RevealTheme.colors.background),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(color = RevealTheme.colors.card, shape = RevealTheme.shapes.sheet) {
                    Column(Modifier.padding(top = 24.dp)) {
                        val range = RecipeRanges.exposure
                        NumericInputContent(
                            label = "Exposure",
                            value = 0.7f,
                            spec = range.toSliderSpec(),
                            format = range::format,
                            onDismiss = {},
                            onConfirm = {},
                            requestFocus = false,
                        )
                    }
                }
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w844dp-h390dp-xhdpi")
class EditorLandscapeScreenshotTest : EditorScreenshotBase("landscape") {
    @Test fun light() = shoot("light") { Editor() }

    @Test fun colour() = shoot("colour") { Editor(tool = ToolId.COLOUR) }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-hdpi")
class EditorTabletScreenshotTest : EditorScreenshotBase("tablet") {
    @Test fun light() = shoot("light") { Editor() }

    @Test fun split() = shoot("split") {
        Editor(view = ViewOptions(compare = CompareMode.Split(leftRight = true, position = 0.4f)))
    }
}

abstract class EditorScreenshotBase(private val size: String) {
    @get:Rule
    val composeRule = createComposeRule()

    protected fun shoot(name: String, content: @Composable () -> Unit) {
        composeRule.setContent(content)
        capture(name)
    }

    protected fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/screenshots/editor/${size}_$name.png")
    }

    @Composable
    protected fun Editor(
        tool: ToolId = ToolId.LIGHT,
        snap: PanelSnap = PanelSnap.HALF,
        view: ViewOptions = ViewOptions(),
    ) {
        EditorTheme {
            EditorContent(
                state = EditorUiState(
                    fileName = "DSC01234.ARW",
                    preview = Photos.edited,
                    stillOriginal = Photos.original,
                    phase = EditorPhase.Ready(null, Photos.WIDTH, Photos.HEIGHT, 1f),
                    developed = true,
                    edit = EditState(
                        recipe = Photos.recipe,
                        committed = Photos.recipe,
                        isRaw = true,
                        asShot = AsShot(5200f, 4f),
                        autoWb = AsShot(5450f, 2f),
                    ),
                    view = view,
                    histogram = Photos.histogram,
                ),
                actions = EditorActions(onBack = {}),
                initialTool = tool,
                initialSnap = snap,
            )
        }
    }

    @Composable
    protected fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }
}

/** The synthetic photo: the demo scene, developed by the CPU reference with and without the edit. */
private object Photos {
    const val WIDTH = 480
    const val HEIGHT = 320

    val recipe = Recipe(
        light = Light(exposureEv = 0.7f, contrast = 18f, highlights = -64f, shadows = 35f),
        colour = Colour(WhiteBalance(temperatureK = 5600f, tint = 8f, mode = WbMode.CUSTOM), vibrance = 20f),
    )

    private val input = SyntheticImages.scene(WIDTH, HEIGHT)
    private val editedRgba = CpuDevelop.render(input, recipe)

    val edited: ImageBitmap = bitmap(editedRgba)
    val original: ImageBitmap = bitmap(CpuDevelop.render(input, Recipe.DEFAULT))
    val histogram: Histogram = Histogram.fromRgba8(editedRgba)

    private fun bitmap(rgba: ByteArray): ImageBitmap {
        val pixels = IntArray(WIDTH * HEIGHT) { i ->
            val r = rgba[i * 4].toInt() and 0xFF
            val g = rgba[i * 4 + 1].toInt() and 0xFF
            val b = rgba[i * 4 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
