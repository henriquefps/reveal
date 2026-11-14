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
import dev.hfps.reveal.data.edit.SnapshotInfo
import dev.hfps.reveal.data.editor.EditorLayout
import dev.hfps.reveal.data.mask.AiMaskState
import dev.hfps.reveal.data.mask.AiMasks
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.preset.GroupKind
import dev.hfps.reveal.data.preset.LibraryGroup
import dev.hfps.reveal.data.preset.LibraryPreset
import dev.hfps.reveal.data.preset.PackInfo
import dev.hfps.reveal.data.preset.PresetLibrary
import dev.hfps.reveal.data.preset.PresetOrigin
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
import dev.hfps.reveal.feature.editor.PreviewOnly
import dev.hfps.reveal.feature.editor.TargetedTarget
import dev.hfps.reveal.feature.editor.UnreadableHandoff
import dev.hfps.reveal.feature.editor.VersionsState
import dev.hfps.reveal.feature.editor.ViewOptions
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.heal.HealEdits
import dev.hfps.reveal.feature.editor.heal.HealSetting
import dev.hfps.reveal.feature.editor.heal.HealSettings
import dev.hfps.reveal.feature.editor.heal.HealUiState
import dev.hfps.reveal.feature.editor.layout.FilmstripThumbs
import dev.hfps.reveal.feature.editor.layout.FilmstripUi
import dev.hfps.reveal.feature.editor.layout.PanelSnap
import dev.hfps.reveal.feature.editor.masks.CreateTarget
import dev.hfps.reveal.feature.editor.masks.MaskOverlayOptions
import dev.hfps.reveal.feature.editor.masks.MasksUiState
import dev.hfps.reveal.feature.editor.masks.PhotoTool
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.feature.editor.panels.PasteChooserContent
import dev.hfps.reveal.feature.editor.panels.toSliderSpec
import dev.hfps.reveal.feature.editor.presets.CreatePresetDraft
import dev.hfps.reveal.feature.editor.presets.PresetSession
import dev.hfps.reveal.feature.editor.presets.PresetThumbnailSource
import dev.hfps.reveal.feature.editor.presets.PresetsState
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
import dev.hfps.reveal.pipeline.mask.MaskPlan
import dev.hfps.reveal.pipeline.mask.MaskRaster
import dev.hfps.reveal.pipeline.mask.StrokeCodec
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.optics.SonyLensProfile
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.Aspect
import dev.hfps.reveal.pipeline.recipe.AspectPreset
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.BrushStroke
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
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.StrokePoint
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

    /** A hand-off opened from its smart preview (ADR 0011 "Fallbacks"). */
    @Test fun previewOnly() = shoot("preview_only") { Editor(previewOnly = PreviewOnly.HANDOFF) }

    /** A hand-off with nothing in its place: the sending app is named and can be opened. */
    @Test fun unreadableHandoff() = shoot("error_handoff") {
        Editor(phase = EditorPhase.Failed(OpenError.PERMISSION_LOST, UnreadableHandoff(app = "Hoard")))
    }

    /** A gallery hand-off with nothing in its place: gallery access is offered. */
    @Test fun unreadableGalleryHandoff() = shoot("error_handoff_gallery") {
        Editor(phase = EditorPhase.Failed(OpenError.PERMISSION_LOST, UnreadableHandoff(gallery = true)))
    }

    @Test fun cropModeStraightenedTurned() = shoot("crop_turned") {
        CropEditor(Photos.straightened.copy(rotate = 1, aspect = Aspect(AspectPreset.R5_4, locked = true)))
    }

    @Test fun croppedResult() = shoot("cropped") { Editor(geometry = Photos.straightened) }

    @Test fun colourFontScale200() = shoot("colour_font200") { Scaled(2f) { Editor(tool = ToolId.COLOUR) } }

    /** Detail (docs/adr/0012): every amount at 0, so the shaping sliders are disabled. */
    @Test fun detail() = shoot("detail") { Editor(tool = ToolId.DETAIL) }

    @Test fun presets() = shoot("presets") { PresetsEditor() }

    @Test fun masks() = shoot("masks") { MasksEditor() }

    /** The Heal mode (docs/adr/0023): spots of every kind, a clone selected with its source. */
    @Test fun heal() = shoot("heal") { HealEditor() }

    /** A Remove spot selected while its fill is computed: the progress on the spot and in the controls. */
    @Test fun healRemove() = shoot("heal_remove") {
        HealEditor(
            selected = "remove",
            heal = HealUiState(settings = HealSettings(kind = HealKind.REMOVE), pending = setOf("remove")),
        )
    }

    /** Red eye selected, its Darken slider shown. */
    @Test fun healRedEye() = shoot("heal_red_eye") {
        HealEditor(
            selected = "eye",
            heal = HealUiState(settings = HealSettings(kind = HealKind.RED_EYE), setting = HealSetting.DARKEN),
        )
    }

    /** Visualise spots on: its contrast slider (the high-pass view itself is the renderer's). */
    @Test fun healVisualise() = shoot("heal_visualise") { HealEditor(heal = HealUiState(visualise = true)) }

    @Test fun masksExpanded() = shoot("masks_expanded") { MasksEditor(snap = PanelSnap.EXPANDED) }

    @Test fun masksEmpty() = shoot("masks_empty") { MasksEditor(none = true) }

    @Test fun masksRadial() = shoot("masks_radial") {
        MasksEditor(
            selected = "spot",
            component = 0,
            snap = PanelSnap.EXPANDED,
            masks = MasksUiState(overlay = MaskOverlayOptions(shown = true)),
        )
    }

    @Test fun masksBrush() = shoot("masks_brush") {
        val brush = MasksUiState(tool = PhotoTool.BRUSH)
        MasksEditor(selected = "brush", component = 0, snap = PanelSnap.EXPANDED, masks = brush)
    }

    @Test fun masksAiComputing() = shoot("masks_ai") {
        val ai = Photos.masks[1].components.single() as MaskComponent.Ai
        MasksEditor(selected = "subject", masks = MasksUiState(ai = mapOf(AiMasks.key(ai) to AiMaskState.COMPUTING)))
    }

    @Test fun masksAiFailed() = shoot("masks_ai_failed") {
        val ai = Photos.masks[1].components.single() as MaskComponent.Ai
        MasksEditor(selected = "subject", masks = MasksUiState(ai = mapOf(AiMasks.key(ai) to AiMaskState.FAILED)))
    }

    @Test
    fun masksChooser() {
        composeRule.setContent { MasksEditor(masks = MasksUiState(creating = CreateTarget.NewMask)) }
        composeRule.waitForIdle()
        captureScreen("masks_chooser")
    }

    @Test fun presetsApplied() = shoot("presets_applied") { PresetsEditor(applied = true) }

    @Test fun presetsExpanded() = shoot("presets_expanded") { PresetsEditor(snap = PanelSnap.EXPANDED) }

    @Test fun presetsFontScale200() = shoot("presets_font200") { Scaled(2f) { PresetsEditor(applied = true) } }

    @Test fun presetsEmpty() = shoot("presets_empty") { PresetsEditor(library = PresetLibrary()) }

    @Test fun presetsTrialPack() {
        composeRule.setContent { PresetsEditor() }
        composeRule.onNodeWithTag(EditorTags.presetChip(TestPresets.PACK_GROUP)).performScrollTo().performClick()
        composeRule.waitForIdle()
        capture("presets_trial_pack")
    }

    @Test fun presetsCreate() {
        composeRule.setContent { PresetsEditor(creating = true) }
        composeRule.waitForIdle()
        captureScreen("presets_create")
    }

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

    /** A virtual copy's History panel with its snapshots (docs/adr/0025). */
    @Test fun historySnapshots() = shoot("history_snapshots") {
        Editor(tool = ToolId.HISTORY, snap = PanelSnap.EXPANDED, history = Photos.history, versions = Photos.versions)
    }

    @Test fun historyNoSnapshots() = shoot("history_no_snapshots") {
        Editor(tool = ToolId.HISTORY, snap = PanelSnap.EXPANDED, versions = VersionsState(library = true))
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

    @Test fun heal() = shoot("heal") { HealEditor() }

    @Test fun masks() = shoot("masks") { MasksEditor() }

    @Test fun presets() = shoot("presets") { PresetsEditor(applied = true) }

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

    @Test fun historySnapshots() = shoot("history_snapshots") {
        Editor(tool = ToolId.HISTORY, history = Photos.history, versions = Photos.versions)
    }
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

    @Test fun presets() = shoot("presets") { PresetsEditor(applied = true) }

    @Test fun masks() = shoot("masks") { MasksEditor() }

    @Test fun masksRadial() = shoot("masks_radial") { MasksEditor(selected = "spot", component = 0) }

    @Test fun heal() = shoot("heal") { HealEditor() }

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

    @Test fun historySnapshots() = shoot("history_snapshots") {
        Editor(tool = ToolId.HISTORY, history = Photos.history, versions = Photos.versions)
    }

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
    @Test fun presets() = shoot("presets") { PresetsEditor(applied = true) }

    @Test fun heal() = shoot("heal") { HealEditor() }

    @Test fun masks() = shoot("masks") { MasksEditor() }

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

    @Test fun heal() = shoot("heal") { HealEditor() }

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

    init {
        // Snapshot times are formatted in the default zone: the same on every machine (CI runs in UTC).
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }

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
        presets: PresetsState = PresetsState(),
        previewOnly: PreviewOnly? = null,
        phase: EditorPhase = EditorPhase.Ready(null, Photos.WIDTH, Photos.HEIGHT, 1f),
        masks: MasksUiState = MasksUiState(),
        versions: VersionsState = VersionsState(),
        heal: HealUiState = HealUiState(),
    ) {
        val recipe = base.copy(geometry = geometry, detail = detail, effects = effects, optics = optics)
        EditorTheme {
            EditorContent(
                state = EditorUiState(
                    fileName = "DSC01234.ARW",
                    preview = preview,
                    stillOriginal = Photos.original,
                    phase = phase,
                    developed = true,
                    previewOnly = previewOnly,
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
                    presets = presets,
                    masks = masks,
                    versions = versions,
                    heal = heal,
                ),
                actions = EditorActions(onBack = {}, presetThumbnails = TestPresets.thumbnails),
                initialTool = tool,
                initialSnap = snap,
                initialMode = mode,
                filmstrip = strip,
                layout = layout,
            )
        }
    }

    /** The Presets panel on the placeholder library; [applied] shows the strength of an applied preset. */
    @Composable
    protected fun PresetsEditor(
        applied: Boolean = false,
        library: PresetLibrary = TestPresets.library,
        snap: PanelSnap = PanelSnap.HALF,
        creating: Boolean = false,
    ) {
        val session = if (applied) TestPresets.session else null
        Editor(
            tool = ToolId.PRESETS,
            snap = snap,
            base = session?.applied ?: Photos.recipe,
            history = if (applied) TestPresets.history else EditHistory(),
            preview = if (applied) TestPresets.appliedImage else Photos.edited,
            presets = PresetsState(
                library = library,
                session = session,
                creating = if (creating) CreatePresetDraft(PresetSection.suggestedFor(Photos.recipe)) else null,
            ),
        )
    }

    /**
     * The Masks panel (docs/adr/0022 "M4/B (editor)") on four masks developed by the CPU reference: [selected] mask and
     * [component]; [masks] adds UI state (AI states, the chooser, the overlay); [none] shows the empty panel.
     */
    @Composable
    protected fun MasksEditor(
        selected: String? = "sky",
        component: Int? = 0,
        snap: PanelSnap = PanelSnap.HALF,
        masks: MasksUiState = MasksUiState(),
        none: Boolean = false,
    ) = Editor(
        tool = ToolId.MASKS,
        snap = snap,
        base = if (none) Photos.recipe else Photos.masked,
        preview = if (none) Photos.edited else Photos.maskedEdited,
        masks = masks.copy(
            selected = if (none) null else selected,
            component = if (none) null else component,
            thumbnails = if (none) emptyMap() else Photos.maskThumbnails,
        ),
    )

    /** The Heal mode (docs/adr/0023) open on spots of every kind developed by the CPU reference; [selected] spot. */
    @Composable
    protected fun HealEditor(selected: String? = "clone", heal: HealUiState = HealUiState()) = Editor(
        mode = ToolId.HEAL,
        base = Photos.healed,
        preview = Photos.healedEdited,
        // The controls show the selected spot's settings (the view model copies them on selection).
        heal = heal.copy(
            open = true,
            selected = selected,
            settings = Photos.heals.firstOrNull { it.id == selected }
                ?.let { HealEdits.settingsOf(it, heal.settings) } ?: heal.settings,
        ),
    )

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

    /** A virtual copy with two snapshots (docs/adr/0025); fixed times, formatted in the test's locale. */
    val versions = VersionsState(
        library = true,
        copyName = "Copy 1",
        snapshots = listOf(
            SnapshotInfo(2, "s2", 7, "Warmer, before the crop", 1_786_000_000_000),
            SnapshotInfo(1, "s1", 7, "Snapshot 1", 1_785_900_000_000),
        ),
    )

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

    /** Masks of every hand-made kind (an AI mask renders nothing without a source, as in a screenshot). */
    val masks: List<Mask> = listOf(
        Mask(
            "sky",
            "Sky",
            adjustments = LocalAdjustments(exposure = -0.8f, dehaze = 25f, colourHue = 220f, colourSaturation = 20f),
            components = listOf(MaskComponent.Linear(x0 = 0.5f, y0 = 0.05f, x1 = 0.5f, y1 = 0.55f, feather = 100f)),
        ),
        Mask(
            "subject",
            "Subject",
            adjustments = LocalAdjustments(exposure = 0.4f, clarity = 15f),
            components = listOf(MaskComponent.Ai(model = "magic-touch@1", kind = "SUBJECT")),
        ),
        Mask(
            "spot",
            "Mask 3",
            adjustments = LocalAdjustments(shadows = 40f, saturation = 20f),
            components = listOf(
                MaskComponent.Radial(cx = 0.6f, cy = 0.62f, rx = 0.22f, ry = 0.14f, rotation = 15f, feather = 60f),
                MaskComponent.LuminanceRange(op = MaskOp.INTERSECT, low = 20f, high = 80f),
            ),
        ),
        Mask(
            "brush",
            "Mask 4",
            enabled = false,
            adjustments = LocalAdjustments(saturation = -60f),
            components = listOf(
                MaskComponent.Brush(
                    strokes = listOf(
                        BrushStroke.of(
                            List(24) { i -> StrokePoint(0.15f + i * 0.02f, 0.8f - 0.1f * kotlin.math.sin(i * 0.3f)) },
                            size = 0.06f,
                        ),
                    ),
                ),
            ),
        ),
    )

    val masked: Recipe = recipe.copy(masks = masks)
    val maskedEdited: ImageBitmap = bitmap(CpuDevelop.render(input, masked))

    /** The masks' coverage pictures, as the editor makes them (the AI one empty: no source here). */
    val maskThumbnails: Map<String, ImageBitmap> = masks.associate { mask ->
        val w = 96
        val h = 64
        val plan = MaskPlan.of(listOf(mask.copy(adjustments = LocalAdjustments(exposure = 1f))), w, h)
        // A lightness ramp stands in for the pre-local image of the luminance range.
        val pre = FloatArray(w * h * 4) { if (it % 4 == 0) (it / 4 % w) / w.toFloat() else 0f }
        val cov = MaskRaster.coverage(plan, MaskRaster.Region(0, 0, w, h), pre).single()
        val px = IntArray(w * h) { i ->
            val v = (cov[i].coerceIn(0f, 1f) * 255).toInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        mask.id to Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Healing spots of every kind (docs/adr/0023); Remove renders nothing without a fill, as in a screenshot. */
    val heals: List<Heal> = listOf(
        Heal("heal", Heal.KIND_HEAL, 0.32f, 0.36f, 0.018f, dx = 0.06f, dy = 0.02f),
        Heal("clone", Heal.KIND_CLONE, 0.7f, 0.3f, 0.025f, dx = -0.08f, dy = 0.07f),
        Heal(
            "stroke",
            Heal.KIND_HEAL,
            radius = 0.012f,
            stroke = StrokeCodec.encode(
                List(12) { i -> StrokePoint(0.2f + i * 0.025f, 0.78f - 0.04f * kotlin.math.sin(i * 0.5f)) },
            ),
            dy = -0.1f,
        ),
        Heal("remove", Heal.KIND_REMOVE, 0.55f, 0.62f, 0.04f, feather = 30f),
        Heal("eye", Heal.KIND_RED_EYE, 0.85f, 0.55f, 0.012f),
    )
    val healed: Recipe = recipe.copy(heals = heals)
    val healedEdited: ImageBitmap = bitmap(CpuDevelop.render(input, healed))

    /** A recipe that "Evening walk" was made from. */
    val recipeForPresets = recipe.copy(light = recipe.light.copy(contrast = 30f, shadows = 50f))

    private val smallInput = PipelineInput(
        input.kind,
        input.width / 4,
        input.height / 4,
        FloatArray(input.width / 4 * (input.height / 4) * 3) { i ->
            val p = i / 3
            val x = p % (input.width / 4) * 4
            val y = p / (input.width / 4) * 4
            input.linearRgb[(y * input.width + x) * 3 + i % 3]
        },
        input.cameraToXyz,
        input.asShotMultipliers,
        input.saturation,
    )

    /** [r] developed on the CPU (at a quarter size for the preset thumbnails). */
    fun render(r: Recipe, small: Boolean = false): ImageBitmap {
        if (!small) return bitmap(CpuDevelop.render(input, r))
        val w = smallInput.width
        val h = smallInput.height
        val rgba = CpuDevelop.render(smallInput, r)
        val pixels = IntArray(w * h) { i ->
            val red = rgba[i * 4].toInt() and 0xFF
            val g = rgba[i * 4 + 1].toInt() and 0xFF
            val b = rgba[i * 4 + 2].toInt() and 0xFF
            (0xFF shl 24) or (red shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
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

/**
 * Placeholder presets for the goldens only (the real built-in looks come from M3/B): a looks group, the user's
 * default group and a pack on trial, with thumbnails developed on the CPU from the synthetic scene.
 */
private object TestPresets {
    const val PACK_GROUP = "5c3e1b2a-0d3f-4a7b-9c8d-2e1f0a9b8c70"

    private fun id(i: Int) = "0f8fad5b-d9cb-469f-a165-7086772895%02d".format(i)

    private fun preset(i: Int, name: String, recipe: Recipe, vararg sections: PresetSection) =
        Preset(id(i), name, PresetPatch.fromSections(recipe, sections.toList()))

    private val looks = listOf(
        preset(
            1,
            "Warm",
            Recipe(colour = Colour(WhiteBalance(6500f, 10f, WbMode.CUSTOM), vibrance = 15f)),
            PresetSection.WHITE_BALANCE,
            PresetSection.VIBRANCE,
        ),
        preset(
            2,
            "Cool",
            Recipe(colour = Colour(WhiteBalance(4300f, -5f, WbMode.CUSTOM))),
            PresetSection.WHITE_BALANCE,
        ),
        preset(
            3,
            "Matte",
            Recipe(
                light = Light(
                    contrast = -25f,
                    curve = Curves(rgb = listOf(CurvePoint(0f, 0.08f), CurvePoint(1f, 0.95f))),
                ),
            ),
            PresetSection.CONTRAST,
            PresetSection.TONE_CURVE,
        ),
        preset(
            4,
            "Punchy",
            Recipe(light = Light(contrast = 35f), colour = Colour(vibrance = 30f)),
            PresetSection.CONTRAST,
            PresetSection.VIBRANCE,
        ),
        preset(
            5,
            "Black & white with a longer name",
            Recipe(profile = Profile(treatment = Treatment.BLACK_WHITE)),
            PresetSection.TREATMENT,
        ),
        preset(
            6,
            "Faded",
            Recipe(colour = Colour(saturation = -40f), light = Light(blacks = 30f)),
            PresetSection.SATURATION,
            PresetSection.BLACKS,
        ),
        preset(7, "Vivid", Recipe(profile = Profile(BaseLook.VIVID)), PresetSection.PROFILE),
        preset(
            8,
            "Teal & orange",
            Recipe(
                colour = Colour(
                    grading = ColourGrading(shadows = GradeWheel(200f, 35f), highlights = GradeWheel(40f, 30f)),
                ),
            ),
            PresetSection.COLOUR_GRADING,
        ),
    )
    private val mine = listOf(
        preset(11, "Evening walk", Photos.recipeForPresets, PresetSection.CONTRAST, PresetSection.SHADOWS),
        preset(
            12,
            "Soft portrait",
            Recipe(profile = Profile(BaseLook.PORTRAIT), effects = Effects(clarity = -15f)),
            PresetSection.PROFILE,
            PresetSection.CLARITY,
        ),
    )
    private val pack = listOf(
        preset(
            21,
            "Golden",
            Recipe(colour = Colour(WhiteBalance(7000f, 12f, WbMode.CUSTOM), saturation = 10f)),
            PresetSection.WHITE_BALANCE,
            PresetSection.SATURATION,
        ),
        preset(
            22,
            "Dusk",
            Recipe(light = Light(exposureEv = -0.4f, highlights = -40f)),
            PresetSection.EXPOSURE,
            PresetSection.HIGHLIGHTS,
        ),
    )

    val library = PresetLibrary(
        listOf(
            LibraryGroup(
                "aa0e8400-e29b-41d4-a716-446655440000",
                null,
                "Looks",
                GroupKind.BUILT_IN,
                looks.mapIndexed { i, p ->
                    LibraryPreset(
                        p,
                        null,
                        "aa0e8400-e29b-41d4-a716-446655440000",
                        PresetOrigin.BUILT_IN,
                        favourite =
                            i == 2,
                    )
                },
            ),
            LibraryGroup(
                "bb0e8400-e29b-41d4-a716-446655440000",
                1L,
                "",
                GroupKind.USER,
                mine.mapIndexed { i, p ->
                    LibraryPreset(p, 10L + i, "bb0e8400-e29b-41d4-a716-446655440000", PresetOrigin.USER)
                },
            ),
            LibraryGroup(
                PACK_GROUP,
                2L,
                "Golden hour",
                GroupKind.PACK,
                pack.mapIndexed { i, p -> LibraryPreset(p, 20L + i, PACK_GROUP, PresetOrigin.PACK) },
                PackInfo(
                    1L,
                    "6a5b4c3d-2e1f-4a0b-8c9d-0e1f2a3b4c5d",
                    "Golden hour",
                    "Ana Example",
                    1,
                    "Personal use",
                    "",
                    null,
                    null,
                    trial = true,
                ),
            ),
        ),
    )

    /** "Matte" applied at 75 %. */
    private val applied = looks[2]
    val history: EditHistory = EditHistory().commit(Photos.recipe)!!.let { h ->
        h.commit(applied.apply(Photos.recipe, 0.75f), HistoryAction.PRESET, listOf(RecipeGroup.LIGHT), applied.name)!!
    }
    val session = PresetSession(applied, Photos.recipe, 0, 0.75f, history.current)
    val appliedImage: ImageBitmap = Photos.render(history.current)

    private val images = HashMap<String, ImageBitmap>()

    /** CPU-developed thumbnails, synchronous (the goldens never wait for a GPU). */
    val thumbnails = object : PresetThumbnailSource {
        override fun cached(key: String): ImageBitmap? {
            val id = key.split('|')[2]
            val preset = library.all.first { it.uuid == id }.preset
            return images.getOrPut(id) { Photos.render(preset.apply(Photos.recipe), small = true) }
        }

        override suspend fun render(key: String, recipe: Recipe): ImageBitmap? = cached(key)
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
            editTime = if (i % 3 != 1) 1L else null,
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
