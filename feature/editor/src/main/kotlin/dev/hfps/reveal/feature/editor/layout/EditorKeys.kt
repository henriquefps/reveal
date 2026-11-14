package dev.hfps.reveal.feature.editor.layout

import androidx.compose.ui.input.key.Key

/** What a keyboard shortcut asks the editor to do. */
enum class EditorKeyCommand {
    UNDO,
    REDO,

    /** Before/after: the ◐ button's tap (the last compare view on or off). */
    COMPARE,

    /** The next photo of the filmstrip. */
    NEXT,

    /** The previous photo of the filmstrip. */
    PREVIOUS,

    /** Fit <-> 100 % (the double tap). */
    ZOOM_100,

    /** Opens the Masks tool. */
    MASKS,

    /** Masks: the brush one step smaller (`[`). */
    BRUSH_SMALLER,

    /** Masks: the brush one step larger (`]`). */
    BRUSH_LARGER,

    /** Masks: the selected mask's overlay on or off. */
    MASK_OVERLAY,

    /** Masks: the brush's erase mode on or off. */
    BRUSH_ERASE,

    /** Opens the Heal mode (docs/adr/0023). */
    HEAL,

    /** Heal: Visualise spots on or off. */
    VISUALISE_SPOTS,
}

/**
 * The editor's keyboard shortcuts on tablets with a keyboard (docs/plan/ux.md): Ctrl+Z undo, Ctrl+Shift+Z (or Ctrl+Y)
 * redo, backslash before/after, left/right arrows (and the D-pad) previous/next photo, Z zoom to 100 % and back; M
 * opens the Masks tool, and in it `[` / `]` resize the brush, O shows the overlay, E toggles erasing (docs/adr/0022
 * "M4/B"); H opens the Heal mode, and in it `[` / `]` resize the spot and V toggles Visualise spots (docs/adr/0023).
 */
object EditorKeys {
    fun command(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): EditorKeyCommand? = when {
        alt -> null
        ctrl -> withCtrl(key, shift)
        shift -> null
        else -> plain[key]
    }

    private fun withCtrl(key: Key, shift: Boolean): EditorKeyCommand? = when (key) {
        Key.Z -> if (shift) EditorKeyCommand.REDO else EditorKeyCommand.UNDO
        Key.Y -> if (shift) null else EditorKeyCommand.REDO
        else -> null
    }

    private val plain: Map<Key, EditorKeyCommand> = mapOf(
        Key.Backslash to EditorKeyCommand.COMPARE,
        Key.DirectionRight to EditorKeyCommand.NEXT,
        Key.DirectionLeft to EditorKeyCommand.PREVIOUS,
        Key.Z to EditorKeyCommand.ZOOM_100,
        Key.M to EditorKeyCommand.MASKS,
        Key.LeftBracket to EditorKeyCommand.BRUSH_SMALLER,
        Key.RightBracket to EditorKeyCommand.BRUSH_LARGER,
        Key.O to EditorKeyCommand.MASK_OVERLAY,
        Key.E to EditorKeyCommand.BRUSH_ERASE,
        Key.H to EditorKeyCommand.HEAL,
        Key.V to EditorKeyCommand.VISUALISE_SPOTS,
    )
}
