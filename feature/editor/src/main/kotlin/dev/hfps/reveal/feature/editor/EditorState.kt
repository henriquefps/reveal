package dev.hfps.reveal.feature.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.Histogram
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.CurveChannel
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.MixerProperty
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.Treatment
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

    data class Failed(val error: OpenError) : EditorPhase
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
) {
    val ready: Boolean get() = phase is EditorPhase.Ready
}

/** Everything the editor content can ask for; defaults do nothing (screenshot tests, previews). */
@Suppress("LongParameterList")
class EditorActions(
    val onBack: () -> Unit,
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
)
