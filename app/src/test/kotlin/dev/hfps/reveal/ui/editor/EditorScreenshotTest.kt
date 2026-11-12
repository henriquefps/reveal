package dev.hfps.reveal.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.editor.EditorLayout
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
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
import dev.hfps.reveal.feature.editor.layout.FilmstripThumbs
import dev.hfps.reveal.feature.editor.layout.FilmstripUi
import dev.hfps.reveal.feature.editor.layout.PanelSnap
import dev.hfps.reveal.feature.editor.panels.PasteChooserContent
import dev.hfps.reveal.feature.editor.panels.toSliderSpec
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.feature.export.encode.EncoderSupport
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.ui.ExportRequest
import dev.hfps.reveal.feature.export.ui.ExportSheetActions
import dev.hfps.reveal.feature.export.ui.ExportSheetContent
import dev.hfps.reveal.feature.export.ui.ExportUiState
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.ProfileLooks
import dev.hfps.reveal.pipeline.export.PixelSize
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.Histogram
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.optics.SonyLensProfile
import dev.hfps.reveal.pipeline.recipe.Aspect
import dev.hfps.reveal.pipeline.recipe.AspectPreset
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GradeRegion
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.UprightMode
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

    /** Effects (docs/adr/0014): every amount at 0, so the shaping sliders are disabled. */
    @Test fun effects() = shoot("effects") { Editor(tool = ToolId.EFFECTS) }

    /** Effects with presence, a vignette and grain on, the whole panel. */
    @Test fun effectsEdited() = shoot("effects_edited") {
        Editor(tool = ToolId.EFFECTS, snap = PanelSnap.EXPANDED, effects = Photos.effects)
    }

    @Test fun effectsFontScale200() = shoot("effects_font200") {
        Scaled(2f) { Editor(tool = ToolId.EFFECTS, effects = Photos.effects) }
    }

    /** Optics (docs/adr/0015): a photo with the camera's lens profile, corrections on (the new-photo default). */
    @Test fun optics() = shoot("optics") {
        Editor(tool = ToolId.OPTICS, optics = Photos.lensDefault, lens = Photos.lens)
    }

    /** Optics edited: amounts, CA removal and purple defringe, the whole panel. */
    @Test fun opticsEdited() = shoot("optics_edited") {
        Editor(tool = ToolId.OPTICS, snap = PanelSnap.EXPANDED, optics = Photos.optics, lens = Photos.lens)
    }

    /** A photo without a profile: the status says so and Lens corrections is disabled. */
    @Test fun opticsNoProfile() = shoot("optics_no_profile") { Editor(tool = ToolId.OPTICS) }

    @Test fun opticsFontScale200() = shoot("optics_font200") {
        Scaled(2f) { Editor(tool = ToolId.OPTICS, optics = Photos.optics, lens = Photos.lens) }
    }

    /** The Crop mode's Transform tab after Upright Vertical. */
    @Test fun transform() = transformTab("transform")

    @Test fun transformFontScale200() = transformTab("transform_font200", fontScale = 2f)

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

    @Test fun effects() = shoot("effects") { Editor(tool = ToolId.EFFECTS, effects = Photos.effects) }

    @Test fun optics() = shoot("optics") {
        Editor(tool = ToolId.OPTICS, optics = Photos.optics, lens = Photos.lens)
    }

    @Test fun transform() = transformTab("transform")
}

/**
 * The tablet layout (M2/E) on a Galaxy Tab S9 in landscape (2560x1600 at 274 dpi = 1495x934 dp; rendered at mdpi to
 * keep the goldens small): full top bar, photo with the filmstrip, resizable panel, tool rail.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1495dp-h934dp-mdpi")
class EditorTabletScreenshotTest : EditorScreenshotBase("tablet") {
    override val strip: FilmstripUi? get() = Strip.ui

    @Test fun light() = shoot("light") { Editor() }

    @Test fun split() = shoot("split") {
        Editor(view = ViewOptions(compare = CompareMode.Split(leftRight = true, position = 0.4f)))
    }

    @Test fun lightFontScale200() = shoot("light_font200") { Scaled(2f) { Editor() } }

    @Test fun colourMixer() = subPanel("colour_mixer", ToolId.COLOUR, EditorTags.MIXER_ROW)

    @Test fun toneCurve() = subPanel("tone_curve", ToolId.LIGHT, EditorTags.TONE_CURVE_ROW)

    @Test fun crop() = shoot("crop") { CropEditor() }

    @Test fun transform() = transformTab("transform")

    @Test fun transformFontScale200() = transformTab("transform_font200", fontScale = 2f)

    @Test fun optics() = shoot("optics") {
        Editor(tool = ToolId.OPTICS, optics = Photos.optics, lens = Photos.lens)
    }

    @Test fun effects() = shoot("effects") { Editor(tool = ToolId.EFFECTS, effects = Photos.effects) }

    @Test fun detail() = shoot("detail") { Editor(tool = ToolId.DETAIL, detail = Photos.detail) }

    @Test fun history() = shoot("history") { Editor(tool = ToolId.HISTORY, history = Photos.history) }

    /** The panel dragged to its widest and the filmstrip hidden (both remembered). */
    @Test fun widePanelNoFilmstrip() = shoot("wide_panel_no_filmstrip") {
        Editor(layout = EditorLayout(panelWidthDp = 560f, filmstripVisible = false))
    }

    /** The export sheet opens as a centred dialog on a tablet. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun exportSheet() {
        composeRule.setContent {
            Editor()
            EditorTheme {
                RevealBottomSheet(onDismissRequest = {}) { ExportSheetContent(Strip.export, ExportSheetActions()) }
            }
        }
        composeRule.waitForIdle()
        captureScreen("export_sheet")
    }

    /** A pen (here a mouse, the same hover path) over a curve point: ring and value tooltip. */
    @Test fun toneCurveHover() {
        composeRule.setContent { ToolsEditor(ToolId.LIGHT) }
        openRow(EditorTags.TONE_CURVE_ROW)
        composeRule.onNodeWithTag(EditorTags.CURVE_EDITOR).performMouseInput {
            moveTo(Offset(width * 0.5f, height * 0.5f))
        }
        composeRule.waitForIdle()
        capture("tone_curve_hover")
    }
}

/**
 * Tablet in portrait (Tab S9, 934x1495 dp): the docked layout (docs/adr/0016 "Docked panel"), the photo across the
 * whole width, the panel docked below and the tool strip as the bottom row.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w934dp-h1495dp-mdpi")
class EditorTabletPortraitScreenshotTest : EditorScreenshotBase("tablet_portrait") {
    override val strip: FilmstripUi? get() = Strip.ui

    @Test fun light() = shoot("light") { Editor() }

    @Test fun lightFontScale200() = shoot("light_font200") { Scaled(2f) { Editor() } }

    @Test fun colourMixer() = subPanel("colour_mixer", ToolId.COLOUR, EditorTags.MIXER_ROW)

    @Test fun toneCurve() = subPanel("tone_curve", ToolId.LIGHT, EditorTags.TONE_CURVE_ROW)

    @Test fun transform() = transformTab("transform")

    @Test fun crop() = shoot("crop") { CropEditor() }

    /** The docked panel dragged to its highest and the filmstrip hidden (both remembered). */
    @Test fun tallPanelNoFilmstrip() = shoot("tall_panel_no_filmstrip") {
        Editor(layout = EditorLayout(panelHeightDp = 760f, filmstripVisible = false))
    }
}

/** A foldable's inner screen turned (883x852 dp): still docked, a 3:2 photo is larger there than beside a column. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w883dp-h852dp-mdpi")
class EditorFoldableLandscapeScreenshotTest : EditorScreenshotBase("foldable_landscape") {
    override val strip: FilmstripUi? get() = Strip.ui

    @Test fun light() = shoot("light") { Editor() }
}

/** A foldable's inner screen (Pixel 9 Pro Fold class, 852x883 dp): the docked tablet layout. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w852dp-h883dp-mdpi")
class EditorFoldableScreenshotTest : EditorScreenshotBase("foldable") {
    override val strip: FilmstripUi? get() = Strip.ui

    @Test fun light() = shoot("light") { Editor() }

    @Test fun colourMixer() = subPanel("colour_mixer", ToolId.COLOUR, EditorTags.MIXER_ROW)

    @Test fun transform() = transformTab("transform")

    @Test fun colourFontScale200() = shoot("colour_font200") { Scaled(2f) { Editor(tool = ToolId.COLOUR) } }
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

    /** The filmstrip the layout shows (tablet classes); none on phones. */
    protected open val strip: FilmstripUi? get() = null

    /** Captures every window (the editor and a dialog over it). */
    @OptIn(ExperimentalRoborazziApi::class)
    protected fun captureScreen(name: String) {
        captureScreenRoboImage(
            "src/test/screenshots/editor/${size}_$name.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = ANTIALIAS_TOLERANCE),
            ),
        )
    }

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
        effects: Effects = Effects(),
        optics: Optics = Optics(),
        lens: LensProfile? = null,
        layout: EditorLayout = EditorLayout(),
    ) {
        val recipe = base.copy(geometry = geometry, detail = detail, effects = effects, optics = optics)
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
                        lens = lens,
                    ),
                    view = view,
                    histogram = Photos.histogram,
                ),
                actions = EditorActions(onBack = {}),
                initialTool = tool,
                initialSnap = snap,
                initialMode = mode,
                filmstrip = strip,
                layout = layout,
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

    /** The Crop mode with its Transform tab open, on an Upright Vertical result. */
    protected fun transformTab(name: String, fontScale: Float = 1f) {
        composeRule.setContent {
            Scaled(fontScale) { CropEditor(geometry = Photos.transformed) }
        }
        composeRule.onNodeWithTag(EditorTags.CROP_TAB_TRANSFORM).performClick()
        composeRule.waitForIdle()
        capture(name)
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

    /** A typical Effects edit: some presence, a darkening vignette and fine grain. */
    val effects = Effects(texture = 20f, clarity = 25f, dehaze = 10f, vignetteAmount = -30f, grainAmount = 20f)

    /** The public ZV-E10 sample's embedded lens data (E PZ 16-50mm at 29 mm). */
    val lens: LensProfile = checkNotNull(
        SonyLensProfile.of(
            "E PZ 16-50mm F3.5-5.6 OSS",
            listOf(11, 21, 2, -22, -49, -84, -124, -168, -208, -247, -279, -305),
            listOf(11, 0, 0, 128, 352, 736, 1184, 1920, 3200, 4768, 6528, 8384),
            listOf(
                22, -512, -512, -384, -384, -256, -256, -256, -128, 0, 128, 256,
                256, 768, 1024, 1280, 1408, 1408, 1408, 1280, 1024, 768, 512,
            ),
        ),
    )

    val lensDefault = Optics.forNewPhoto(hasProfile = true, profileHasCa = true, isRaw = true)

    val optics = lensDefault.copy(distortionPercent = 80f, vignettingPercent = 120f, purpleAmount = 6f)

    /** An Upright Vertical result with a refitted crop (the transform's sliders in the Transform tab). */
    val transformed = Geometry(
        crop = CropRect(0.08f, 0.1f, 0.92f, 0.9f),
        transform = Transform(vertical = 24f, rotate = 1.2f, upright = UprightMode.VERTICAL),
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

/** A folder of 40 photos for the filmstrip (the current one is the fifth): stars, flags and edits mixed. */
private object Strip {
    private val images = listOf(Photos.edited, Photos.original, Photos.toolsEdited, Photos.bwEdited)

    private val rows = List(40) { i ->
        PhotoRow(
            id = i + 1L,
            sourceId = 1,
            name = "DSC%05d.ARW".format(i + 1),
            format = PhotoFormat.RAW_PAIR,
            sortTime = i.toLong(),
            captureTime = i.toLong(),
            rating = listOf(0, 3, 5, 0, 4, 0, 1, 0)[i % 8],
            label = null,
            flag = listOf(PickFlag.NONE, PickFlag.PICK, PickFlag.NONE, PickFlag.REJECT)[i % 4],
            hasEdit = i % 3 != 1,
            versionId = 100L + i,
            editTime = null,
        )
    }

    val ui = FilmstripUi(
        photos = rows,
        currentVersionId = 104L,
        thumbs = object : FilmstripThumbs {
            override fun cached(row: PhotoRow): ImageBitmap = images[(row.id % images.size).toInt()]

            override suspend fun load(row: PhotoRow): ImageBitmap = cached(row)
        },
    )

    val export = ExportUiState(
        request = ExportRequest.Current(104L, null, "DSC00005.ARW", Recipe()),
        options = ExportOptions(colour = ExportColour.DISPLAY_P3),
        support = EncoderSupport(heif = true, heicCodec = false, hevcMain10 = false),
        fullSize = PixelSize(6024, 4024),
        outputSize = PixelSize(6024, 4024),
        estimateBytes = 9_100_000,
    )
}
