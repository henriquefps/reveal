package dev.hfps.reveal.feature.editor.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/**
 * docs/adr/0023 "FIX/H": while a spot is dragged (its target, its source or its size) only the overlay moves; the
 * recipe, and so the healing, changes once on release (one step); a cancelled drag leaves the spot as it was.
 */
class HealDragTest {
    private class Host : HealEditor.Host {
        override var ui = EditorUiState()
        var edits = 0
        var commits = 0

        override fun updateHeal(change: (HealUiState) -> HealUiState) {
            ui = ui.copy(heal = change(ui.heal))
        }

        override fun edit(change: (Recipe) -> Recipe) {
            edits++
            ui = ui.copy(edit = ui.edit.copy(recipe = change(ui.edit.recipe)))
        }

        override fun commit() {
            commits++
        }

        override fun renderer(): GpuDevelopRenderer? = null
    }

    private val spot = HealEdits.circle(0.3f, 0.4f, HealSettings(kind = HealKind.REMOVE), id = "s")

    private fun editor(): Pair<HealEditor, Host> {
        val host = Host()
        host.ui = host.ui.copy(edit = host.ui.edit.copy(recipe = Recipe(heals = listOf(spot))))
        val editor = HealEditor(CoroutineScope(Dispatchers.Unconfined), host)
        editor.setOpen(true)
        editor.select("s")
        return editor to host
    }

    @Test
    fun `dragging a spot moves only its outline until the release`() {
        val (editor, host) = editor()
        repeat(30) { editor.moveTarget("s", 0.3f + it * 0.01f, 0.4f) }
        assertThat(host.edits).isEqualTo(0)
        assertThat(host.ui.edit.recipe.heals.single()).isEqualTo(spot)
        assertThat(host.ui.heal.shown(host.ui.edit.recipe.heals).single().x).isWithin(1e-6f).of(0.59f)
        editor.commit()
        assertThat(host.edits).isEqualTo(1)
        assertThat(host.commits).isEqualTo(1)
        assertThat(host.ui.edit.recipe.heals.single().x).isWithin(1e-6f).of(0.59f)
        assertThat(host.ui.heal.dragging).isNull()
    }

    @Test
    fun `a source drag is applied on release, and a cancelled drag changes nothing`() {
        val (editor, host) = editor()
        editor.moveSource("s", 0.1f, 0.05f)
        assertThat(host.ui.edit.recipe.heals.single().dx).isEqualTo(spot.dx)
        editor.dragCancel()
        assertThat(host.ui.heal.dragging).isNull()
        assertThat(host.edits).isEqualTo(0)
        editor.moveSource("s", 0.1f, 0.05f)
        editor.commit()
        assertThat(host.ui.edit.recipe.heals.single().dx).isEqualTo(0.1f)
        assertThat(host.ui.edit.recipe.heals.single().autoSource).isFalse()
    }

    @Test
    fun `the size slider shows the outline, feather and opacity apply live`() {
        val (editor, host) = editor()
        val settings = host.ui.heal.settings
        editor.setSettings(settings.copy(size = 0.05f))
        editor.setSettings(settings.copy(size = 0.06f))
        assertThat(host.edits).isEqualTo(0)
        assertThat(host.ui.heal.dragging?.radius).isEqualTo(0.06f)
        editor.commit()
        assertThat(host.ui.edit.recipe.heals.single().radius).isEqualTo(0.06f)
        editor.setSettings(host.ui.heal.settings.copy(opacity = 50f))
        assertThat(host.edits).isEqualTo(2)
        assertThat(host.ui.edit.recipe.heals.single().opacity).isEqualTo(50f)
    }
}
