package dev.hfps.reveal.feature.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.hfps.reveal.feature.editor.edit.AsShot
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.Histogram
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.WbMode

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
 * The edit of the open photo: the working [recipe] (what is on screen, updated every drag frame) and the
 * [committed] one (moved forward on release: the history and Room seam, docs/adr/0003 "Committed steps").
 *
 * @property isRaw RAW input shows absolute white balance (kelvin/tint); display-referred input the relative pair.
 * @property asShot the camera's white balance, shown while the recipe holds `null`.
 * @property autoWb the grey-world estimate behind the "Auto" white-balance chip, `null` when unavailable.
 */
@Immutable
data class EditState(
    val recipe: Recipe = Recipe.DEFAULT,
    val committed: Recipe = Recipe.DEFAULT,
    val isRaw: Boolean = true,
    val asShot: AsShot = AsShot(),
    val autoWb: AsShot? = null,
)

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
)

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
    /** Eyedropper result: the neutral point in proxy image pixels. */
    val onPickNeutral: (imageX: Float, imageY: Float) -> Unit = { _, _ -> },
    // TODO(history worker): undo/redo are placeholders until the history lands.
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    // TODO(export worker): opens the export sheet.
    val onExport: () -> Unit = {},
    // TODO(auto worker): the panel-level Auto of Light.
    val onAuto: () -> Unit = {},
)
