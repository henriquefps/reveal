package dev.hfps.reveal.feature.editor.layout

import androidx.compose.ui.input.key.Key
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorKeysTest {
    private fun cmd(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        EditorKeys.command(key, ctrl, shift, alt)

    @Test
    fun undoAndRedo() {
        assertThat(cmd(Key.Z, ctrl = true)).isEqualTo(EditorKeyCommand.UNDO)
        assertThat(cmd(Key.Z, ctrl = true, shift = true)).isEqualTo(EditorKeyCommand.REDO)
        assertThat(cmd(Key.Y, ctrl = true)).isEqualTo(EditorKeyCommand.REDO)
    }

    @Test
    fun plainKeys() {
        assertThat(cmd(Key.Backslash)).isEqualTo(EditorKeyCommand.COMPARE)
        assertThat(cmd(Key.DirectionRight)).isEqualTo(EditorKeyCommand.NEXT)
        assertThat(cmd(Key.DirectionLeft)).isEqualTo(EditorKeyCommand.PREVIOUS)
        assertThat(cmd(Key.Z)).isEqualTo(EditorKeyCommand.ZOOM_100)
    }

    @Test
    fun masksKeys() {
        assertThat(cmd(Key.M)).isEqualTo(EditorKeyCommand.MASKS)
        assertThat(cmd(Key.LeftBracket)).isEqualTo(EditorKeyCommand.BRUSH_SMALLER)
        assertThat(cmd(Key.RightBracket)).isEqualTo(EditorKeyCommand.BRUSH_LARGER)
        assertThat(cmd(Key.O)).isEqualTo(EditorKeyCommand.MASK_OVERLAY)
        assertThat(cmd(Key.E)).isEqualTo(EditorKeyCommand.BRUSH_ERASE)
        assertThat(cmd(Key.E, ctrl = true)).isNull()
    }

    @Test
    fun otherCombinationsDoNothing() {
        assertThat(cmd(Key.Z, alt = true)).isNull()
        assertThat(cmd(Key.Z, shift = true)).isNull()
        assertThat(cmd(Key.DirectionRight, ctrl = true)).isNull()
        assertThat(cmd(Key.A)).isNull()
        assertThat(cmd(Key.Y, ctrl = true, shift = true)).isNull()
    }
}
