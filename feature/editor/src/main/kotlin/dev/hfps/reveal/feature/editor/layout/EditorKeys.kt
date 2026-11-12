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
}

/**
 * The editor's keyboard shortcuts on tablets with a keyboard (docs/plan/ux.md): Ctrl+Z undo, Ctrl+Shift+Z (or Ctrl+Y)
 * redo, backslash before/after, left/right arrows (and the D-pad) previous/next photo, Z zoom to 100 % and back.
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
    )
}
