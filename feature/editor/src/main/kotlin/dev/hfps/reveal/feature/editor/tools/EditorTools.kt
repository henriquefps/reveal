package dev.hfps.reveal.feature.editor.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.feature.editor.EditorActions
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.panels.ColourPanel
import dev.hfps.reveal.feature.editor.panels.HistoryPanel
import dev.hfps.reveal.feature.editor.panels.LightPanel
import dev.hfps.reveal.feature.editor.panels.PanelHost
import dev.hfps.reveal.pipeline.recipe.RecipeGroup

/** The tools of the strip, in strip order (docs/plan/ux.md, "Phone, portrait"). */
enum class ToolId { PRESETS, AUTO, CROP, LIGHT, COLOUR, EFFECTS, DETAIL, OPTICS, MASKS, HEAL, HISTORY }

/** What a mode (Crop, Masks, Heal) gets from the editor while it is active. */
@Stable
class ModeHost(val state: EditorUiState, val actions: EditorActions, val onDone: () -> Unit)

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
        val content: @Composable (PanelHost) -> Unit,
    ) : ToolBehaviour

    /**
     * A mode (docs/plan/ux.md: Crop, Masks, Heal): entering it swaps the tool strip for [controls] plus a Done check,
     * and draws [overlay] over the photo (in the photo frame's coordinates).
     */
    class Mode(
        val controls: @Composable (ModeHost) -> Unit,
        val overlay: @Composable BoxScope.(ModeHost) -> Unit = {},
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
 * The registry of editor tools. M1 enables Light and Colour; Crop and Auto are wired by their own workers by
 * replacing [ToolBehaviour.Unavailable] with a [ToolBehaviour.Mode] / [ToolBehaviour.Action] here.
 */
object EditorTools {
    val all: List<EditorTool> = listOf(
        EditorTool(
            ToolId.PRESETS,
            R.string.editor_tool_presets,
            PhosphorRegular.SquaresFour,
            PhosphorFill.SquaresFour,
            ToolBehaviour.Unavailable,
        ),
        // TODO(auto worker): ToolBehaviour.Action { it.onAuto() }.
        EditorTool(
            ToolId.AUTO,
            R.string.editor_tool_auto,
            PhosphorRegular.MagicWand,
            PhosphorFill.MagicWand,
            ToolBehaviour.Unavailable,
        ),
        // TODO(crop worker): ToolBehaviour.Mode(controls = aspect chips + straighten dial, overlay = crop frame).
        EditorTool(
            ToolId.CROP,
            R.string.editor_tool_crop,
            PhosphorRegular.Crop,
            PhosphorFill.Crop,
            ToolBehaviour.Unavailable,
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
            ToolBehaviour.Unavailable,
        ),
        EditorTool(
            ToolId.DETAIL,
            R.string.editor_tool_detail,
            PhosphorRegular.Triangle,
            PhosphorFill.Triangle,
            ToolBehaviour.Unavailable,
        ),
        EditorTool(
            ToolId.OPTICS,
            R.string.editor_tool_optics,
            PhosphorRegular.Aperture,
            PhosphorFill.Aperture,
            ToolBehaviour.Unavailable,
        ),
        EditorTool(
            ToolId.MASKS,
            R.string.editor_tool_masks,
            PhosphorRegular.Selection,
            PhosphorFill.Selection,
            ToolBehaviour.Unavailable,
        ),
        EditorTool(
            ToolId.HEAL,
            R.string.editor_tool_heal,
            PhosphorRegular.Bandaids,
            PhosphorFill.Bandaids,
            ToolBehaviour.Unavailable,
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

    /** The tool with [id]. */
    operator fun get(id: ToolId): EditorTool = if (id == ToolId.HISTORY) history else all.first { it.id == id }
}
