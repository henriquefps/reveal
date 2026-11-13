package dev.hfps.reveal.feature.editor.masks

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.hfps.reveal.data.mask.AiMaskState
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.pipeline.gl.MaskOverlay
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import dev.hfps.reveal.pipeline.recipe.StrokePressure

/**
 * The brush's settings (docs/adr/0022 "M4/B (editor)"): [size] the dab diameter as a fraction of the image's long edge,
 * [feather], [flow] and [density] in percent, [erase] mode, [autoMask], and what pen [pressure] drives.
 */
@Immutable
data class BrushSettings(
    val size: Float = DEFAULT_SIZE,
    val feather: Float = DEFAULT_FEATHER,
    val flow: Float = FULL,
    val density: Float = FULL,
    val erase: Boolean = false,
    val autoMask: Boolean = false,
    val pressure: StrokePressure = StrokePressure.FLOW,
) {
    /** One step of the `[` / `]` keys: about 15 % bigger or smaller, within [MIN_SIZE]..[MAX_SIZE]. */
    fun resized(steps: Int): BrushSettings {
        var s = size
        repeat(kotlin.math.abs(steps)) { s = if (steps > 0) s * SIZE_STEP else s / SIZE_STEP }
        return copy(size = s.coerceIn(MIN_SIZE, MAX_SIZE))
    }

    companion object {
        const val DEFAULT_SIZE: Float = 0.05f
        const val MIN_SIZE: Float = 0.002f
        const val MAX_SIZE: Float = 0.5f
        const val DEFAULT_FEATHER: Float = 50f
        const val FULL: Float = 100f
        private const val SIZE_STEP = 1.15f
    }
}

/** How the selected mask is shown over the photo (`GpuDevelopRenderer.setMaskOverlay`). */
@Immutable
data class MaskOverlayOptions(
    /** Shown all the time (the `O` key, the panel's toggle); otherwise only while drawing or dragging a handle. */
    val shown: Boolean = false,
    val mode: MaskOverlay.Mode = MaskOverlay.Mode.COLOUR,
    val colour: Int = MaskOverlay.DEFAULT_COLOUR,
    val opacity: Float = MaskOverlay.DEFAULT_OPACITY,
) {
    companion object {
        /** The overlay colours offered (sRGB): red, green, blue, yellow, magenta, white. */
        val COLOURS: List<Int> = listOf(0xFF2A2A, 0x2AD94A, 0x2A7BFF, 0xFFD52A, 0xE02AFF, 0xFFFFFF)
    }
}

/** What a tap on the photo does while the Masks panel is open, besides the handles. */
enum class PhotoTool {
    /** Nothing special: handles drag, the rest pans and zooms. */
    NONE,

    /** Paint with the brush (the selected component is a brush). */
    BRUSH,

    /** The colour range's eyedropper: a tap adds a sample. */
    PICK_COLOUR,

    /** Subject: a tap adds a point to the subject. */
    SUBJECT_ADD,

    /** Subject: a tap removes the object under it (a subtracting subject component seeded by the tap). */
    SUBJECT_SUBTRACT,
}

/** Where the "+" chooser puts what is picked: a new mask, or a component of the selected one with [op]. */
sealed interface CreateTarget {
    data object NewMask : CreateTarget

    data class AddTo(val op: MaskOp) : CreateTarget
}

/** The Masks panel's state (everything that is not in the recipe). */
@Immutable
data class MasksUiState(
    /** The selected mask's id. */
    val selected: String? = null,
    /** The selected component (index in the selected mask's components). */
    val component: Int? = null,
    val brush: BrushSettings = BrushSettings(),
    val overlay: MaskOverlayOptions = MaskOverlayOptions(),
    /** A stroke, a handle drag or a range slider is in progress: the overlay shows automatically. */
    val interacting: Boolean = false,
    val tool: PhotoTool = PhotoTool.NONE,
    /** The luminance range's map: the photo's lightness in grey with the selected band tinted. */
    val luminanceMap: Boolean = false,
    /** The chooser is open, and where it adds. */
    val creating: CreateTarget? = null,
    /** AI masks: their state by `AiMasks.key` (computing, ready, failed, unavailable). */
    val ai: Map<String, AiMaskState> = emptyMap(),
    /** Small coverage pictures of the masks by id (none without a renderer). */
    val thumbnails: Map<String, ImageBitmap> = emptyMap(),
    /** The AI kinds this build can make (empty: no models, the AI choices are hidden). */
    val aiKinds: List<MaskKind> = MaskKind.entries,
    /** The last mask refused because the recipe has [MaskEdits.MAX_MASKS] masks. */
    val full: Boolean = false,
    /** The photo before local adjustments (the luminance map, the eyedropper), once rendered. */
    val preLocal: PreLocalImage? = null,
)

/** What the Masks panel and the photo overlay can ask for; defaults do nothing (screenshot tests, previews). */
@Suppress("TooManyFunctions")
interface MaskActions {
    fun select(id: String?) {}

    fun selectComponent(index: Int?) {}

    /** Opens the chooser for [target]; `null` closes it. */
    fun openCreate(target: CreateTarget?) {}

    /** The chooser's pick: a new mask, or a component of the selected one (as the chooser was opened). */
    fun create(kind: NewComponent) {}

    fun rename(id: String, name: String) {}

    fun duplicate(id: String) {}

    fun invert(id: String) {}

    fun delete(id: String) {}

    /** Moves a mask to position [to] (one step). */
    fun move(id: String, to: Int) {}

    fun setEnabled(id: String, enabled: Boolean) {}

    /** Per drag frame (not committed). */
    fun setAmount(id: String, amount: Float) {}

    /** Per drag frame (not committed). */
    fun setAdjustment(id: String, adjustment: LocalAdjustment, value: Float) {}

    fun resetAdjustment(id: String, adjustment: LocalAdjustment) {}

    /** Every local adjustment of the mask back to 0 (one step). */
    fun resetAdjustments(id: String) {}

    fun setComponentOp(index: Int, op: MaskOp) {}

    fun toggleComponentInvert(index: Int) {}

    fun removeComponent(index: Int) {}

    /** Per frame: the selected mask's component [index] changes (a handle, a range slider); not committed. */
    fun editComponent(index: Int, change: (MaskComponent) -> MaskComponent) {}

    /** A drag, slider or handle was released: the working recipe becomes one step. */
    fun commit() {}

    fun setBrush(settings: BrushSettings) {}

    /** A stroke starts at [point] (image fractions); [erase] for the pen's eraser end or its held button. */
    fun strokeStart(point: StrokePoint, erase: Boolean) {}

    /** The stroke in progress gets [points] (appended, raw). */
    fun strokeMove(points: List<StrokePoint>) {}

    /** The stroke ends: simplified, one history step. */
    fun strokeEnd() {}

    /** The stroke is dropped (a second finger arrived). */
    fun strokeCancel() {}

    fun setTool(tool: PhotoTool) {}

    /** The colour range's eyedropper picked image fraction ([x], [y]): one more sample (at most five). */
    fun pickColour(x: Float, y: Float) {}

    /** A subject tap at image fraction ([x], [y]) as the tool says (add or subtract). */
    fun subjectTap(x: Float, y: Float) {}

    fun setOverlay(options: MaskOverlayOptions) {}

    /** The `O` key: the overlay on or off. */
    fun toggleOverlay() {}

    /** A stroke or a handle drag starts (`true`) or ends: the overlay shows meanwhile. */
    fun setInteracting(on: Boolean) {}

    fun setLuminanceMap(on: Boolean) {}

    /** Computes a failed AI component again. */
    fun retryAi() {}

    companion object {
        val NONE: MaskActions = object : MaskActions {}
    }
}
