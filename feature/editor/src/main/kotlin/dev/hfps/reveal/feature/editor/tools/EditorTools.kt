package dev.hfps.reveal.feature.editor.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.feature.editor.EditorActions
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.canvas.PhotoController
import dev.hfps.reveal.feature.editor.crop.CropControls
import dev.hfps.reveal.feature.editor.crop.CropOverlay
import dev.hfps.reveal.feature.editor.crop.CropSession
import dev.hfps.reveal.feature.editor.heal.HealControls
import dev.hfps.reveal.feature.editor.heal.HealPhotoLayer
import dev.hfps.reveal.feature.editor.masks.MaskPhotoLayer
import dev.hfps.reveal.feature.editor.masks.MasksPanel
import dev.hfps.reveal.feature.editor.panels.ColourPanel
import dev.hfps.reveal.feature.editor.panels.DetailPanel
import dev.hfps.reveal.feature.editor.panels.EffectsPanel
import dev.hfps.reveal.feature.editor.panels.HistoryPanel
import dev.hfps.reveal.feature.editor.panels.LightPanel
import dev.hfps.reveal.feature.editor.panels.OpticsPanel
import dev.hfps.reveal.feature.editor.panels.PanelHost
import dev.hfps.reveal.feature.editor.presets.PresetsPanel
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.recipe.RecipeGroup

/** The tools of the strip, in strip order (docs/plan/ux.md, "Phone, portrait"). */
enum class ToolId { PRESETS, AUTO, CROP, LIGHT, COLOUR, EFFECTS, DETAIL, OPTICS, MASKS, HEAL, HISTORY, MASK_LAB }

/**
 * What a mode (Crop, Masks, Heal) gets from the editor while it is active: the state and actions, whether its
 * controls are laid out [vertical]ly (the landscape/tablet column), the editor's photo [photo] controller (the view
 * the overlay draws in), the crop mode's UI [crop] session, and the Done / Cancel exits.
 */
@Stable
class ModeHost(
    val state: EditorUiState,
    val actions: EditorActions,
    val photo: PhotoController,
    val crop: CropSession,
    val vertical: Boolean,
    val onDone: () -> Unit,
    val onCancel: () -> Unit,
)

/** What a panel's photo layer gets: the state, the actions and the photo (its view, the pen). */
@Stable
class PhotoLayerHost(val state: EditorUiState, val actions: EditorActions, val photo: PhotoController)

/**
 * What a panel draws and handles on the photo while it is open (the Masks panel's handles, brush and eyedropper):
 * [gestures] wraps the photo's own gestures (it sees each pointer first and takes what it handles; the rest pans and
 * zooms), [overlay] draws over the photo in the slot's coordinates and takes no input.
 */
class PhotoLayer(
    val gestures: @Composable (PhotoLayerHost) -> Modifier,
    val overlay: @Composable BoxScope.(PhotoLayerHost) -> Unit,
)

/** What selecting a tool does. Adding a tool never touches the layout: give its [EditorTool] a behaviour. */
sealed interface ToolBehaviour {
    /**
     * Opens a panel in the panel card: the eyebrow [title], the panel-level reset of [group] (title ⟲ and long-press
     * on the title; hidden without a group), a panel-level Auto when [showAuto], and the [content] rows.
     */
    class Panel(
        @param:StringRes val title: Int,
        val group: RecipeGroup?,
        val showAuto: Boolean = false,
        /** The content scrolls by itself (a lazy grid): the card gives it its height instead of a scroll. */
        val ownsScroll: Boolean = false,
        /** What the panel draws and handles on the photo while it is the open tool, if anything. */
        val photo: PhotoLayer? = null,
        val content: @Composable (PanelHost) -> Unit,
    ) : ToolBehaviour

    /**
     * A mode (docs/plan/ux.md: Crop, Masks, Heal): entering it swaps the tool strip for [controls] plus Cancel and a
     * Done check under its [title], and draws [overlay] over the photo (in the photo frame's coordinates).
     * [onActive] is told when the mode opens and closes; [done] and [cancel] run on its exits (Cancel, back).
     */
    class Mode(
        @param:StringRes val title: Int,
        val controls: @Composable (ModeHost) -> Unit,
        val overlay: @Composable BoxScope.(ModeHost) -> Unit = {},
        val onActive: (EditorActions, Boolean) -> Unit = { _, _ -> },
        val done: (EditorActions) -> Unit = {},
        val cancel: (EditorActions) -> Unit = {},
        /** What the mode draws and handles on the photo (the Heal mode's spots and brush), if anything. */
        val photo: PhotoLayer? = null,
    ) : ToolBehaviour

    /** A one-shot action (Auto). */
    class Action(val run: (EditorActions) -> Unit) : ToolBehaviour

    /** Shown in the strip but not available yet (its workstream has not landed). */
    data object Unavailable : ToolBehaviour
}

/** One entry of the tool strip / rail: label, regular and filled (active) icon, behaviour. */
class EditorTool(
    val id: ToolId,
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val activeIcon: ImageVector,
    val behaviour: ToolBehaviour,
) {
    val available: Boolean get() = behaviour !is ToolBehaviour.Unavailable
}

/**
 * The registry of editor tools. M1 enables Crop (a mode), Light and Colour; Auto is wired by its own worker by
 * replacing [ToolBehaviour.Unavailable] with a [ToolBehaviour.Action] here; M2 adds Detail, Effects and Optics.
 */
object EditorTools {
    val all: List<EditorTool> = listOf(
        EditorTool(
            ToolId.PRESETS,
            R.string.editor_tool_presets,
            PhosphorRegular.SquaresFour,
            PhosphorFill.SquaresFour,
            ToolBehaviour.Panel(R.string.editor_tool_presets, group = null, ownsScroll = true) { PresetsPanel(it) },
        ),
        EditorTool(
            ToolId.AUTO,
            R.string.editor_tool_auto,
            PhosphorRegular.MagicWand,
            PhosphorFill.MagicWand,
            ToolBehaviour.Action { it.onAuto(AutoScope.ALL) },
        ),
        EditorTool(
            ToolId.CROP,
            R.string.editor_tool_crop,
            PhosphorRegular.Crop,
            PhosphorFill.Crop,
            ToolBehaviour.Mode(
                title = R.string.editor_tool_crop,
                controls = { CropControls(it) },
                overlay = { CropOverlay(it) },
                onActive = { actions, active -> actions.onCropMode(active) },
                done = { it.onCropDone() },
                cancel = { it.onCropCancel() },
            ),
        ),
        EditorTool(
            ToolId.LIGHT,
            R.string.editor_tool_light,
            PhosphorRegular.Sun,
            PhosphorFill.Sun,
            ToolBehaviour.Panel(R.string.editor_tool_light, RecipeGroup.LIGHT, showAuto = true) { LightPanel(it) },
        ),
        EditorTool(
            ToolId.COLOUR,
            R.string.editor_tool_colour,
            PhosphorRegular.Palette,
            PhosphorFill.Palette,
            ToolBehaviour.Panel(R.string.editor_tool_colour, RecipeGroup.COLOUR) { ColourPanel(it) },
        ),
        EditorTool(
            ToolId.EFFECTS,
            R.string.editor_tool_effects,
            PhosphorRegular.Sparkle,
            PhosphorFill.Sparkle,
            ToolBehaviour.Panel(R.string.editor_tool_effects, RecipeGroup.EFFECTS) { EffectsPanel(it) },
        ),
        EditorTool(
            ToolId.DETAIL,
            R.string.editor_tool_detail,
            PhosphorRegular.Triangle,
            PhosphorFill.Triangle,
            ToolBehaviour.Panel(R.string.editor_tool_detail, RecipeGroup.DETAIL) { DetailPanel(it) },
        ),
        EditorTool(
            ToolId.OPTICS,
            R.string.editor_tool_optics,
            PhosphorRegular.Aperture,
            PhosphorFill.Aperture,
            ToolBehaviour.Panel(R.string.editor_tool_optics, RecipeGroup.OPTICS) { OpticsPanel(it) },
        ),
        EditorTool(
            ToolId.MASKS,
            R.string.editor_tool_masks,
            PhosphorRegular.Selection,
            PhosphorFill.Selection,
            ToolBehaviour.Panel(
                R.string.editor_tool_masks,
                group = null,
                photo = MaskPhotoLayer.layer,
            ) { MasksPanel(it) },
        ),
        EditorTool(
            ToolId.HEAL,
            R.string.editor_tool_heal,
            PhosphorRegular.Bandaids,
            PhosphorFill.Bandaids,
            ToolBehaviour.Mode(
                title = R.string.editor_tool_heal,
                controls = { HealControls(it) },
                onActive = { actions, active -> actions.heal.setOpen(active) },
                done = { it.heal.done() },
                cancel = { it.heal.cancel() },
                photo = HealPhotoLayer.layer,
            ),
        ),
    )

    /** The history list: a panel opened from More ⋯, not a tool of the strip. */
    val history: EditorTool = EditorTool(
        ToolId.HISTORY,
        R.string.editor_history_title,
        PhosphorRegular.ClockCounterClockwise,
        PhosphorFill.ClockCounterClockwise,
        ToolBehaviour.Panel(R.string.editor_history_title, group = null) { HistoryPanel(it) },
    )

    /**
     * The mask lab (debug builds only, docs/adr/0021): runs the on-device mask models on the open photo and tints the
     * mask. Opened from More ⋯; null in release builds (`src/release` has no lab, no LiteRT and no models).
     */
    val maskLab: EditorTool? get() = DebugTools.maskLab

    /** The tool with [id]. */
    operator fun get(id: ToolId): EditorTool = when (id) {
        ToolId.HISTORY -> history
        ToolId.MASK_LAB -> checkNotNull(maskLab) { "no mask lab in this build" }
        else -> all.first { it.id == id }
    }
}
