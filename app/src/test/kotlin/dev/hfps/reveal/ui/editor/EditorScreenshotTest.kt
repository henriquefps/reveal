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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
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
import dev.hfps.reveal.feature.editor.TargetedTarget
import dev.hfps.reveal.feature.editor.ViewOptions
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.layout.PanelSnap
import dev.hfps.reveal.feature.editor.panels.PasteChooserContent
import dev.hfps.reveal.feature.editor.panels.toSliderSpec
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.ProfileLooks
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.Histogram
import dev.hfps.reveal.pipeline.recipe.Aspect
import dev.hfps.reveal.pipeline.recipe.AspectPreset
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GradeRegion
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.Treatment
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

    @Test fun cropMode() = shoot("crop") { CropEditor() }

    @Test fun cropModeStraightenedTurned() = shoot("crop_turned") {
        CropEditor(Photos.straightened.copy(rotate = 1, aspect = Aspect(AspectPreset.R5_4, locked = true)))
    }

    @Test fun croppedResult() = shoot("cropped") { Editor(geometry = Photos.straightened) }

    @Test fun colourFontScale200() = shoot("colour_font200") { Scaled(2f) { Editor(tool = ToolId.COLOUR) } }

    /** Detail (docs/adr/0012): every amount at 0, so the shaping sliders are disabled. */
    @Test fun detail() = shoot("detail") { Editor(tool = ToolId.DETAIL) }

    /** Detail with sharpening and both noise reductions on, the whole panel. */
    @Test fun detailEdited() = shoot("detail_edited") {
        Editor(tool = ToolId.DETAIL, snap = PanelSnap.EXPANDED, detail = Photos.detail)
    }

    @Test fun detailFontScale200() = shoot("detail_font200") {
        Scaled(2f) { Editor(tool = ToolId.DETAIL, detail = Photos.detail) }
    }

    @Test fun toneCurve() = subPanel("tone_curve", ToolId.LIGHT, EditorTags.TONE_CURVE_ROW)

    @Test fun toneCurveParametric() = subPanel("tone_curve_parametric", ToolId.LIGHT, EditorTags.TONE_CURVE_ROW) {
        composeRule.onNodeWithTag(EditorTags.CURVE_MODE_PARAMETRIC).performClick()
        composeRule.waitForIdle()
    }

    @Test fun colourMixer() = subPanel("colour_mixer", ToolId.COLOUR, EditorTags.MIXER_ROW)

    @Test fun colourMixerTargeted() {
        composeRule.setContent { ToolsEditor(ToolId.COLOUR, view = ViewOptions(targeted = TargetedTarget.SATURATION)) }
        openRow(EditorTags.MIXER_ROW)
        capture("colour_mixer_targeted")
    }

    @Test fun grading() = subPanel("grading", ToolId.COLOUR, EditorTags.GRADING_ROW)

    @Test fun gradingSingle() = subPanel("grading_shadows", ToolId.COLOUR, EditorTags.GRADING_ROW) {
        composeRule.onNodeWithTag(EditorTags.gradeRegion(GradeRegion.SHADOWS)).performClick()
        composeRule.waitForIdle()
    }

    @Test fun blackWhite() = shoot("bw") { ToolsEditor(ToolId.COLOUR, bw = true) }

    @Test fun blackWhiteMix() = subPanel("bw_mix", ToolId.COLOUR, EditorTags.GREY_MIXER_ROW, bw = true)

    @Test fun toneCurveFontScale200() {
        composeRule.setContent { Scaled(2f) { ToolsEditor(ToolId.LIGHT) } }
        openRow(EditorTags.TONE_CURVE_ROW)
        capture("tone_curve_font200")
    }

    @Test fun colourMixerFontScale200() {
        composeRule.setContent { Scaled(2f) { ToolsEditor(ToolId.COLOUR) } }
        openRow(EditorTags.MIXER_ROW)
        capture("colour_mixer_font200")
    }

    @Test fun gradingFontScale200() {
        composeRule.setContent { Scaled(2f) { ToolsEditor(ToolId.COLOUR) } }
        openRow(EditorTags.GRADING_ROW)
        capture("grading_font200")
    }

    @Test fun historyPanel() = shoot("history") {
        Editor(tool = ToolId.HISTORY, snap = PanelSnap.EXPANDED, history = Photos.history)
    }

    @Test fun pasteChooser() = shoot("paste_chooser") {
        EditorTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(RevealTheme.colors.background),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(color = RevealTheme.colors.card, shape = RevealTheme.shapes.sheet) {
                    PasteChooserContent(
                        copied = Photos.recipe,
                        groups = RecipePaste.DEFAULT_GROUPS - RecipeGroup.PROFILE,
                        onGroupsChange = {},
                        onPaste = {},
                        onCancel = {},
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }

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

    @Test fun cropMode() = shoot("crop") { CropEditor() }

    @Test fun detail() = shoot("detail") { Editor(tool = ToolId.DETAIL, detail = Photos.detail) }
    @Test fun toneCurve() = subPanel("tone_curve", ToolId.LIGHT, EditorTags.TONE_CURVE_ROW)

    @Test fun colourMixer() = subPanel("colour_mixer", ToolId.COLOUR, EditorTags.MIXER_ROW)

    @Test fun grading() = subPanel("grading", ToolId.COLOUR, EditorTags.GRADING_ROW)
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
    private companion object {
        /**
         * Share of pixels allowed to differ. The histogram is drawn on an offscreen layer with blended paths, and
         * Skia's software rasterizer anti-aliases those edges differently on Linux (CI) and macOS; nothing else in
         * the editor differs between the two. 2 % of the frame is far above the histogram's edge pixels and far
         * below any layout, colour or text regression.
         */
        const val ANTIALIAS_TOLERANCE = 0.02f
    }

    @get:Rule
    val composeRule = createComposeRule()

    protected fun shoot(name: String, content: @Composable () -> Unit) {
        composeRule.setContent(content)
        capture(name)
    }

    protected fun capture(name: String) {
        composeRule.onRoot().captureRoboImage(
            "src/test/screenshots/editor/${size}_$name.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ANTIALIAS_TOLERANCE),
            ),
        )
    }

    @Composable
    protected fun Editor(
        tool: ToolId = ToolId.LIGHT,
        snap: PanelSnap = PanelSnap.HALF,
        view: ViewOptions = ViewOptions(),
        history: EditHistory = EditHistory(),
        geometry: Geometry = Geometry(),
        mode: ToolId? = null,
        detail: Detail = Detail(),
        base: Recipe = Photos.recipe,
        preview: ImageBitmap = Photos.edited,
    ) {
        val recipe = base.copy(geometry = geometry, detail = detail)
        EditorTheme {
            EditorContent(
                state = EditorUiState(
                    fileName = "DSC01234.ARW",
                    preview = preview,
                    stillOriginal = Photos.original,
                    phase = EditorPhase.Ready(null, Photos.WIDTH, Photos.HEIGHT, 1f),
                    developed = true,
                    edit = EditState(
                        recipe = recipe,
                        history = history,
                        isRaw = true,
                        asShot = AsShot(5200f, 4f),
                        autoWb = WhiteBalance(5450f, 2f, WbMode.AUTO),
                        autoReady = true,
                    ),
                    view = view,
                    histogram = Photos.histogram,
                ),
                actions = EditorActions(onBack = {}),
                initialTool = tool,
                initialSnap = snap,
                initialMode = mode,
            )
        }
    }

    /** The editor on the colour-tools recipe, with the sub-panel behind [row] opened. */
    protected fun subPanel(name: String, tool: ToolId, row: String, bw: Boolean = false, then: () -> Unit = {}) {
        composeRule.setContent { ToolsEditor(tool, bw) }
        openRow(row)
        then()
        capture(name)
    }

    @Composable
    protected fun ToolsEditor(tool: ToolId, bw: Boolean = false, view: ViewOptions = ViewOptions()) = Editor(
        tool = tool,
        view = view,
        base = if (bw) Photos.bw else Photos.tools,
        preview = if (bw) Photos.bwEdited else Photos.toolsEdited,
    )

    protected fun openRow(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    /** The Crop mode open on a straightened 4:5 crop. */
    @Composable
    protected fun CropEditor(geometry: Geometry = Photos.straightened) =
        Editor(view = ViewOptions(cropping = true), geometry = geometry, mode = ToolId.CROP)

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

    /** A typical high-ISO Detail edit. */
    val detail = Detail(sharpenAmount = 45f, sharpenMasking = 30f, nrLuminance = 35f, nrColour = 40f)

    // The synthetic scene's values are display-like (grey near 0.18); a camera exposes a RAW about 1.3 EV lower
    // (grey near 0.06), which is what Standard's baseline gain expects (docs/adr/0008). Exposed like a camera so the
    // screenshots show a normal photo instead of a blown-out one.

    /** A history of five steps, the last one undone (the current step is a paste). */
    val history: EditHistory = run {
        val exposure = Recipe(light = Light(exposureEv = 0.7f))
        val light = exposure.copy(light = recipe.light)
        var h = EditHistory()
        h = h.commit(exposure)!!
        h = h.commit(light)!!
        h = h.commit(Recipe.DEFAULT, HistoryAction.RESET_GROUP, listOf(RecipeGroup.LIGHT))!!
        h = h.commit(recipe, HistoryAction.PASTE, listOf(RecipeGroup.LIGHT, RecipeGroup.COLOUR))!!
        h = h.commit(recipe.copy(colour = recipe.colour.copy(vibrance = 45f)))!!
        h.undo()!!
    }

    /** A straightened, constrained 4:5 crop of the scene (the crop mode's frame and the cropped result). */
    val straightened: Geometry = CropEditing.straighten(
        CropEditing.withAspect(Geometry(), Aspect(AspectPreset.R4_5, locked = true), WIDTH, HEIGHT),
        -6.5f,
        WIDTH,
        HEIGHT,
    )

    private val input = SyntheticImages.scene(WIDTH, HEIGHT).let { scene ->
        val k = (1.0 / ProfileLooks.STANDARD_FILMIC.gain).toFloat()
        PipelineInput(
            scene.kind,
            scene.width,
            scene.height,
            FloatArray(scene.linearRgb.size) { scene.linearRgb[it] * k },
            scene.cameraToXyz,
            scene.asShotMultipliers,
            scene.saturation,
        )
    }
    private val editedRgba = CpuDevelop.render(input, recipe)

    /** The colour tools in use: a tone curve, the mixer, grading (docs/adr/0013). */
    val tools = recipe.copy(
        light = recipe.light.copy(
            curve = Curves(
                rgb = Curves.MEDIUM_CONTRAST,
                red = listOf(CurvePoint(0f, 0f), CurvePoint(0.55f, 0.6f), CurvePoint(1f, 1f)),
                parametric = ParametricCurve(highlights = -20f, shadows = 15f),
            ),
        ),
        colour = recipe.colour.copy(
            mixer = ColourMixer(
                hue = BandValues(red = 20f),
                saturation = BandValues(red = 25f, blue = 30f, green = -20f),
                luminance = BandValues(blue = -35f),
            ),
            grading = ColourGrading(
                shadows = GradeWheel(225f, 30f, -10f),
                midtones = GradeWheel(40f, 8f),
                highlights = GradeWheel(55f, 22f, 5f),
            ),
        ),
    )
    val toolsEdited: ImageBitmap = bitmap(CpuDevelop.render(input, tools))

    /** Black & white with a grey mix and a split tone. */
    val bw = tools.copy(
        profile = Profile(treatment = Treatment.BLACK_WHITE),
        colour = tools.colour.copy(greyMixer = BandValues(red = 25f, orange = 15f, blue = -40f, aqua = -20f)),
    )
    val bwEdited: ImageBitmap = bitmap(CpuDevelop.render(input, bw))

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
