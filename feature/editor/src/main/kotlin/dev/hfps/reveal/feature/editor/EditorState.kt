package dev.hfps.reveal.feature.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.heal.HealActions
import dev.hfps.reveal.feature.editor.heal.HealUiState
import dev.hfps.reveal.feature.editor.masks.MaskActions
import dev.hfps.reveal.feature.editor.masks.MasksUiState
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.feature.editor.presets.PresetThumbnailSource
import dev.hfps.reveal.feature.editor.presets.PresetsState
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.Histogram
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.preset.SmartRules
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.CurveChannel
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GuideLine
import dev.hfps.reveal.pipeline.recipe.MixerProperty
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.UprightMode
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance

/** Where the photo is in its life on screen. */
sealed interface EditorPhase {
    /** Decoding: [progress] 0..1 over the proxy steps. */
    data class Loading(val progress: Float) : EditorPhase

    /**
     * The proxy is on the GPU and [renderer] draws it. [hundredPercentScale] = full-size pixels per proxy pixel.
     * A `null` [renderer] shows [EditorUiState.preview] as a still photo instead (screenshot tests, previews).
     */
    data class Ready(
        val renderer: GpuDevelopRenderer?,
        val width: Int,
        val height: Int,
        val hundredPercentScale: Float,
    ) : EditorPhase

    /** [handoff]: the photo is a hand-off Reveal can no longer read, with nothing in its place (ADR 0011). */
    data class Failed(val error: OpenError, val handoff: UnreadableHandoff? = null) : EditorPhase
}

/**
 * A hand-off that cannot be opened (ADR 0011 "Fallbacks"): [app] is the sending app when it is known (its provider has
 * a launcher entry; the editor can open it), [gallery] that gallery access would make it readable (a MediaStore URI
 * and no full access yet).
 */
data class UnreadableHandoff(val app: String? = null, val gallery: Boolean = false)

/** The editor shows a smart preview instead of the original (ADR 0011 "Fallbacks", ADR 0018 "Smart previews"). */
enum class PreviewOnly {
    /** A hand-off Reveal can no longer read: share it again for the full photo. */
    HANDOFF,

    /** The original is out of reach (a Server photo whose original is on another device, an unplugged folder). */
    ELSEWHERE,
}

/**
 * The edit of the open photo: the working [recipe] (what is on screen, updated every drag frame) and its [history]
 * of committed steps, whose current step is the [committed] recipe (moved forward on release, docs/adr/0003
 * "Committed steps"; persisted per version, ADR 0006 "History").
 *
 * @property isRaw RAW input shows absolute white balance (kelvin/tint); display-referred input the relative pair.
 * @property asShot the camera's white balance, shown while the recipe holds `null`.
 * @property autoWb the Auto white balance (ADR 0008) behind the "Auto" chip as recipe values, `null` when unavailable.
 * @property autoReady the Auto analysis sample is in memory: the Auto tool and the Light panel's Auto can run.
 */
@Immutable
data class EditState(
    val recipe: Recipe = Recipe.DEFAULT,
    val history: EditHistory = EditHistory(),
    val isRaw: Boolean = true,
    val asShot: AsShot = AsShot(),
    val autoWb: WhiteBalance? = null,
    val autoReady: Boolean = false,
    /** The copied edit (from here or the library), null when nothing was copied: Paste is available. */
    val clipboard: Recipe? = null,
    /** Another photo has an edit: "Previous" is available. */
    val hasPrevious: Boolean = false,
    /** The paste chooser is open with these groups ticked; null = closed. */
    val pasteChooser: Set<RecipeGroup>? = null,
    /** The band the targeted adjustment leads with while a drag on the photo runs (the panel shows it). */
    val targetedBand: ColourBand? = null,
    /** The band under a hovering pen while the targeted adjustment is on (its tooltip), `null` otherwise. */
    val hoverBand: ColourBand? = null,
    /** The photo's lens profile (docs/adr/0015), `null` when none is known. */
    val lens: LensProfile? = null,
    /** The last Upright mode that found too few lines (the Transform tab says so), `null` otherwise. */
    val uprightFailed: UprightMode? = null,
) {
    /** The recipe of the current history step. */
    val committed: Recipe get() = history.current

    val canPaste: Boolean get() = clipboard != null
}

/** How the photo is shown: histogram, clipping overlays, before/after, eyedropper. */
@Immutable
data class ViewOptions(
    val histogram: Boolean = true,
    val clipping: ClippingWarnings = ClippingWarnings.NONE,
    val compare: CompareMode = CompareMode.Off,
    /** The split the ◐ button returns to on a tap. */
    val lastCompare: CompareMode = CompareMode.Split(),
    /** Hold ◐ or the photo: the original (default recipe) is shown. */
    val showOriginal: Boolean = false,
    /** "Tap a neutral area" mode of the white-balance eyedropper. */
    val eyedropper: Boolean = false,
    /**
     * The Crop mode is open: the photo shows the whole image straightened and oriented (the editing plane of
     * `GeometryMapping.editing`) under the crop frame; elsewhere the editor shows the cropped result.
     */
    val cropping: Boolean = false,
    /**
     * The targeted adjustment of the colour mixer or grey mixer (docs/adr/0013) is on: a vertical drag on the photo
     * changes [TargetedTarget] of the bands under the finger. `null` = off.
     */
    val targeted: TargetedTarget? = null,
)

/** What the targeted adjustment changes: one property of the colour mixer, or the B&W grey mixer. */
enum class TargetedTarget {
    HUE,
    SATURATION,
    LUMINANCE,
    GREY,
    ;

    /** The mixer property, `null` for [GREY]. */
    val property: MixerProperty?
        get() = when (this) {
            HUE -> MixerProperty.HUE
            SATURATION -> MixerProperty.SATURATION
            LUMINANCE -> MixerProperty.LUMINANCE
            GREY -> null
        }
}

@Immutable
data class EditorUiState(
    val fileName: String = "",
    /**
     * The instant preview shown until the first developed frame, then dropped. Without a renderer (screenshot
     * tests, previews) it is the still photo of the edit.
     */
    val preview: ImageBitmap? = null,
    /** Without a renderer only: the still original, for the before side of a split. */
    val stillOriginal: ImageBitmap? = null,
    val phase: EditorPhase = EditorPhase.Loading(0f),
    /** True once a developed frame is on screen (the preview fades out). */
    val developed: Boolean = false,
    val edit: EditState = EditState(),
    val view: ViewOptions = ViewOptions(),
    /** Latest histogram of the displayed photo (null until the first one). */
    val histogram: Histogram? = null,
    /** The preset library and the applied preset (docs/adr/0019). */
    val presets: PresetsState = PresetsState(),
    /** Non-null when the photo opened from its smart preview: the editor says so (exports are at preview size). */
    val previewOnly: PreviewOnly? = null,
    /** The Masks tool (docs/adr/0022 "M4/B (editor)"): selection, brush, overlay, AI states. */
    val masks: MasksUiState = MasksUiState(),
    /** The Heal mode (docs/adr/0023 "Editor"): selection, settings, Visualise spots, Remove's progress. */
    val heal: HealUiState = HealUiState(),
) {
    val ready: Boolean get() = phase is EditorPhase.Ready
}

/** Everything the editor content can ask for; defaults do nothing (screenshot tests, previews). */
@Suppress("LongParameterList")
class EditorActions(
    val onBack: () -> Unit,
    /** Opens the app that shared an unreadable hand-off, to share it again (ADR 0011 "Fallbacks"). */
    val onOpenSender: () -> Unit = {},
    /** Asks for gallery access, then opens the photo again (ADR 0011 "Gallery access"). */
    val onGalleryAccess: () -> Unit = {},
    val onDeveloped: () -> Unit = {},
    val onPreviewHidden: () -> Unit = {},
    /** Per drag frame: the working recipe changes, nothing is committed. */
    val onControl: (ControlId, Float) -> Unit = { _, _ -> },
    /** Release / numeric input / accessibility action: the working recipe is committed. */
    val onCommit: () -> Unit = {},
    val onResetControl: (ControlId) -> Unit = {},
    val onResetGroup: (RecipeGroup) -> Unit = {},
    val onLook: (BaseLook) -> Unit = {},
    val onWbMode: (WbMode) -> Unit = {},
    val onHistogram: (Boolean) -> Unit = {},
    val onClipping: (ClippingWarnings) -> Unit = {},
    val onCompare: (CompareMode) -> Unit = {},
    val onShowOriginal: (Boolean) -> Unit = {},
    val onEyedropper: (Boolean) -> Unit = {},
    /** Crop mode: the working geometry changes (per gesture frame, not committed). */
    val onGeometry: (Geometry) -> Unit = {},
    /** Crop mode opened (`true`) or closed (`false`): the renderer shows the editing plane while open. */
    val onCropMode: (Boolean) -> Unit = {},
    /** Crop mode's Done: the geometry edit becomes one committed step. */
    val onCropDone: () -> Unit = {},
    /** Crop mode's Cancel (and back): the geometry returns to what it was when the mode opened. */
    val onCropCancel: () -> Unit = {},
    /** Eyedropper result: the neutral point in proxy image pixels. */
    val onPickNeutral: (imageX: Float, imageY: Float) -> Unit = { _, _ -> },
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    /** History list: jump to the step at a position (-1 = the original). */
    val onGoTo: (Int) -> Unit = {},
    /** More ⋯: copy the whole edit; the paste chooser picks the groups. */
    val onCopy: () -> Unit = {},
    /** Opens the paste chooser. */
    val onPaste: () -> Unit = {},
    val onPasteGroups: (Set<RecipeGroup>) -> Unit = {},
    val onPasteConfirm: () -> Unit = {},
    val onPasteDismiss: () -> Unit = {},
    /** Applies the last edited photo's recipe. */
    val onPrevious: () -> Unit = {},
    val onResetAll: () -> Unit = {},
    /** Back to the recipe the photo opens with (default + the user's defaults). */
    val onResetAsOpened: () -> Unit = {},
    /** Opens the export sheet for the photo with its current recipe. */
    val onExport: () -> Unit = {},
    /** One-tap Auto (docs/adr/0010): the strip's Auto sets [AutoScope.ALL], the Light panel's [AutoScope.LIGHT]. */
    val onAuto: (AutoScope) -> Unit = {},
    /** Detail panel: shows (`true`) or hides the sharpening mask on the photo (while Masking is dragged). */
    val onDetailMask: (Boolean) -> Unit = {},
    /** Per drag frame, several controls at once (a grading wheel's hue and saturation); not committed. */
    val onControls: (Map<ControlId, Float>) -> Unit = {},
    /** Per drag frame: the points of one point curve change (empty = straight line); not committed. */
    val onCurve: (CurveChannel, List<CurvePoint>) -> Unit = { _, _ -> },
    /** A point-curve preset applied to the RGB curve, one committed step. */
    val onCurvePreset: (List<CurvePoint>) -> Unit = {},
    /** Colour or black & white (the profile's treatment), one committed step. */
    val onTreatment: (Treatment) -> Unit = {},
    /** Black & white Auto mix: the grey mixer computed from the photo, one committed step. */
    val onAutoMix: () -> Unit = {},
    /** Turns the targeted adjustment on for a target, or off (`null`). */
    val onTargeted: (TargetedTarget?) -> Unit = {},
    /** A targeted drag starts on the proxy image point ([imageX], [imageY]). */
    val onTargetedStart: (imageX: Float, imageY: Float) -> Unit = { _, _ -> },
    /** The targeted drag has moved [dpUp] dp upwards from where it started (negative = down); not committed. */
    val onTargetedDrag: (dpUp: Float) -> Unit = {},
    /** The targeted drag ended: one committed step. */
    val onTargetedEnd: () -> Unit = {},
    /** A one-step edit (a sub-panel's own reset): [change] applied to the working recipe and committed. */
    val onEditAndCommit: ((Recipe) -> Recipe) -> Unit = {},
    /** Optics: "Remove chromatic aberration" on or off (one step; the automatic estimate when needed). */
    val onRemoveCa: (Boolean) -> Unit = {},
    /** Crop mode, Transform tab: Upright with a mode (working geometry; Done commits). */
    val onUpright: (UprightMode) -> Unit = {},
    /** Crop mode: auto-straighten (working geometry; Done commits). */
    val onAutoStraighten: () -> Unit = {},
    /** Crop mode, Guided Upright: a guide was drawn (normalised lens-corrected image coordinates). */
    val onGuide: (GuideLine) -> Unit = {},
    val onClearGuides: () -> Unit = {},
    /** The pen hovers the photo with the targeted adjustment on: the proxy image point, `null` when it leaves. */
    val onTargetedHover: (Offset?) -> Unit = {},
    /** Tablet filmstrip (and arrows, swipes): the editor moves to another photo's version, keeping the open tool. */
    val onOpenPhoto: (versionId: Long, tool: ToolId) -> Unit = { _, _ -> },
    /** Tablet: the filmstrip is shown (`true`) or hidden; remembered. */
    val onFilmstrip: (Boolean) -> Unit = {},
    /** Tablet: the side panel was resized to a width in dp; remembered. */
    val onPanelWidth: (Float) -> Unit = {},
    /** Tablet portrait: the docked panel was resized to a height in dp; remembered. */
    val onPanelHeight: (Float) -> Unit = {},
    // ------------------------------------------------------------------ presets (docs/adr/0019)
    /** Applies a preset by id: one history step (replacing the last applied one while it is current). */
    val onApplyPreset: (String) -> Unit = {},
    /** Per drag frame: the applied preset's strength 0..2 (not committed). */
    val onPresetStrength: (Float) -> Unit = {},
    /** Strength released: the applied preset's step is replaced. */
    val onPresetStrengthDone: () -> Unit = {},
    val onCreatePreset: () -> Unit = {},
    val onCreatePresetDismiss: () -> Unit = {},
    /** Saves the edit's sections as a preset: name, user group id or a new group's name. */
    val onSavePreset: (name: String, groupId: Long?, newGroup: String?, sections: Set<PresetSection>) -> Unit =
        { _, _, _, _ -> },
    val onPresetFavourite: (String, Boolean) -> Unit = { _, _ -> },
    val onRenamePreset: (String, String) -> Unit = { _, _ -> },
    val onSetPresetSmart: (String, Set<SmartRules.Kind>) -> Unit = { _, _ -> },
    val onDuplicatePreset: (String, String) -> Unit = { _, _ -> },
    val onDeletePreset: (String) -> Unit = {},
    val onMovePreset: (String, Long) -> Unit = { _, _ -> },
    /** Moves a preset one place earlier (-1) or later (+1) in its group. */
    val onShiftPreset: (String, Int) -> Unit = { _, _ -> },
    val onRenamePresetGroup: (Long, String) -> Unit = { _, _ -> },
    val onDeletePresetGroup: (Long) -> Unit = {},
    val onShiftPresetGroup: (Long, Int) -> Unit = { _, _ -> },
    val onKeepPack: (Long) -> Unit = {},
    val onRemovePack: (Long) -> Unit = {},
    /** Imports a `.revealpreset` / `.revealpack` picked with the system file picker. */
    val onImportPresets: (android.net.Uri) -> Unit = {},
    /** Opens "Import from a link" (docs/adr/0019): the link field, then a confirmation before any download. */
    val onImportPresetLink: () -> Unit = {},
    val onExportPreset: (String, android.net.Uri) -> Unit = { _, _ -> },
    /** Exports a user group as a pack: the document, the author and the licence name shown to importers. */
    val onExportPresetGroup: (Long, android.net.Uri, String, String) -> Unit = { _, _, _, _ -> },
    /** The live previews (null: placeholders, e.g. screenshot tests without a GPU). */
    val presetThumbnails: PresetThumbnailSource? = null,
    /** The recipe the panel previews presets on (the applied preset's base while it is current). */
    val presetBase: () -> Recipe? = { null },
    /** A preset at full strength on a base recipe, as previews show it. */
    val presetPreview: (Preset, Recipe) -> Recipe = { preset, base -> preset.apply(base) },
    // ------------------------------------------------------------------ masks (docs/adr/0022 "M4/B (editor)")
    val masks: MaskActions = MaskActions.NONE,
    /** The Masks panel is on screen (`true`) or not: the overlay and the photo tools work only while it is. */
    val onMasksPanel: (Boolean) -> Unit = {},
    // ------------------------------------------------------------------ healing (docs/adr/0023)
    val heal: HealActions = HealActions.NONE,
)
