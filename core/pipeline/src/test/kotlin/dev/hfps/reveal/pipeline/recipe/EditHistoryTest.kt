package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditHistoryTest {
    private fun exposure(ev: Float) = Recipe(light = Light(exposureEv = ev))

    private fun EditHistory.commitOrFail(recipe: Recipe, action: HistoryAction? = null) =
        checkNotNull(commit(recipe, action)) { "expected a step" }

    @Test
    fun `an empty history is the base with nothing to undo or redo`() {
        val history = EditHistory()
        assertThat(history.current).isEqualTo(Recipe.DEFAULT)
        assertThat(history.cursor).isEqualTo(-1)
        assertThat(history.canUndo).isFalse()
        assertThat(history.canRedo).isFalse()
        assertThat(history.undo()).isNull()
        assertThat(history.redo()).isNull()
    }

    @Test
    fun `commits append labelled steps and move the cursor`() {
        val history = EditHistory().commitOrFail(exposure(0.7f)).commitOrFail(exposure(1.2f))
        assertThat(history.entries.map { it.label.changes }).containsExactly(
            listOf(HistoryStep(HistoryField.EXPOSURE, "+0.70")),
            listOf(HistoryStep(HistoryField.EXPOSURE, "+1.20")),
        ).inOrder()
        assertThat(history.cursor).isEqualTo(1)
        assertThat(history.current).isEqualTo(exposure(1.2f))
        assertThat(history.canUndo).isTrue()
        assertThat(history.canRedo).isFalse()
    }

    @Test
    fun `undo and redo walk the steps down to the base and back`() {
        val two = EditHistory().commitOrFail(exposure(0.7f)).commitOrFail(exposure(1.2f))
        val one = checkNotNull(two.undo())
        assertThat(one.current).isEqualTo(exposure(0.7f))
        val base = checkNotNull(one.undo())
        assertThat(base.current).isEqualTo(Recipe.DEFAULT)
        assertThat(base.undo()).isNull()
        assertThat(base.canRedo).isTrue()
        assertThat(checkNotNull(base.redo()).current).isEqualTo(exposure(0.7f))
        assertThat(checkNotNull(checkNotNull(base.redo()).redo()).current).isEqualTo(exposure(1.2f))
        // Undo/redo never change the steps, only the cursor.
        assertThat(base.entries).isEqualTo(two.entries)
    }

    @Test
    fun `jumping to a step keeps the later steps for redo`() {
        val three = EditHistory()
            .commitOrFail(exposure(0.5f))
            .commitOrFail(exposure(1f))
            .commitOrFail(exposure(1.5f))
        val jumped = checkNotNull(three.goTo(0))
        assertThat(jumped.current).isEqualTo(exposure(0.5f))
        assertThat(jumped.entries).hasSize(3)
        assertThat(jumped.canRedo).isTrue()
        assertThat(checkNotNull(jumped.goTo(-1)).current).isEqualTo(Recipe.DEFAULT)
        assertThat(checkNotNull(jumped.goTo(2)).current).isEqualTo(exposure(1.5f))
        assertThat(jumped.goTo(0)).isNull()
        assertThat(jumped.goTo(3)).isNull()
        assertThat(jumped.goTo(-2)).isNull()
    }

    @Test
    fun `a commit after an undo starts a new branch and drops the redo steps`() {
        val three = EditHistory()
            .commitOrFail(exposure(0.5f))
            .commitOrFail(exposure(1f))
            .commitOrFail(exposure(1.5f))
        val branch = checkNotNull(three.goTo(0)).commitOrFail(exposure(-1f))
        assertThat(branch.entries.map { it.recipe }).containsExactly(exposure(0.5f), exposure(-1f)).inOrder()
        assertThat(branch.cursor).isEqualTo(1)
        assertThat(branch.canRedo).isFalse()
        // The new step is labelled against the step it branched from.
        assertThat(branch.entries.last().label.changes).containsExactly(HistoryStep(HistoryField.EXPOSURE, "−1.00"))
        // Branching from the base keeps nothing.
        val fromBase = checkNotNull(three.goTo(-1)).commitOrFail(exposure(2f))
        assertThat(fromBase.entries).hasSize(1)
    }

    @Test
    fun `an unchanged recipe is not a step, even for a named action`() {
        val history = EditHistory().commitOrFail(exposure(0.7f))
        assertThat(history.commit(exposure(0.7f))).isNull()
        assertThat(history.commit(exposure(0.70001f))).isNull()
        assertThat(EditHistory().commit(Recipe.DEFAULT, HistoryAction.RESET_ALL)).isNull()
    }

    @Test
    fun `named steps keep their action, groups and changes`() {
        val edited = Recipe(light = Light(exposureEv = 1f, contrast = 20f))
        val reset = EditHistory().commitOrFail(edited).commit(Recipe.DEFAULT, HistoryAction.RESET_GROUP, listOf(RecipeGroup.LIGHT))
        val label = checkNotNull(reset).entries.last().label
        assertThat(label.action).isEqualTo(HistoryAction.RESET_GROUP)
        assertThat(label.groups).containsExactly(RecipeGroup.LIGHT)
        assertThat(label.changes.map { it.field }).containsExactly(HistoryField.EXPOSURE, HistoryField.CONTRAST).inOrder()
    }

    @Test
    fun `history is unlimited`() {
        var history = EditHistory()
        repeat(500) { i -> history = history.commitOrFail(exposure((i + 1) / 100f)) }
        assertThat(history.entries).hasSize(500)
        assertThat(checkNotNull(history.goTo(-1)).current).isEqualTo(Recipe.DEFAULT)
    }
}
