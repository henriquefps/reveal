package dev.hfps.reveal.pipeline.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import org.junit.Test

/** History steps and copy/paste of the spots (docs/adr/0023 "History, copy and paste"). */
class HealDiffTest {
    private val a = Heal("a", Heal.KIND_HEAL, 0.2f, 0.3f, 0.01f, dx = 0.05f)
    private val b = Heal("b", Heal.KIND_REMOVE, 0.6f, 0.5f, 0.03f)

    @Test
    fun `adding and deleting spots name their kind`() {
        assertThat(RecipeDiff.between(Recipe(), Recipe(heals = listOf(a, b)))).containsExactly(
            HistoryStep(HistoryField.HEAL_ADD, Heal.KIND_HEAL),
            HistoryStep(HistoryField.HEAL_ADD, Heal.KIND_REMOVE),
        )
        assertThat(RecipeDiff.between(Recipe(heals = listOf(a, b)), Recipe(heals = listOf(b)))).containsExactly(
            HistoryStep(HistoryField.HEAL_DELETE, Heal.KIND_HEAL),
        )
    }

    @Test
    fun `a moved source and other changes are separate steps`() {
        val before = Recipe(heals = listOf(a))
        assertThat(RecipeDiff.between(before, Recipe(heals = listOf(a.copy(dx = 0.1f, autoSource = false)))))
            .containsExactly(HistoryStep(HistoryField.HEAL_SOURCE, Heal.KIND_HEAL))
        assertThat(RecipeDiff.between(before, Recipe(heals = listOf(a.copy(radius = 0.02f)))))
            .containsExactly(HistoryStep(HistoryField.HEAL_EDIT, Heal.KIND_HEAL))
        assertThat(RecipeDiff.between(before, Recipe(heals = listOf(a.copy(kind = Heal.KIND_CLONE)))))
            .containsExactly(HistoryStep(HistoryField.HEAL_EDIT, Heal.KIND_CLONE))
        assertThat(RecipeDiff.between(before, before)).isEmpty()
    }

    @Test
    fun `the healing group pastes the spots and is not in the default choice`() {
        val source = Recipe(heals = listOf(a, b))
        assertThat(RecipeGroup.HEALS.copy(source, Recipe()).heals).containsExactly(a, b).inOrder()
        assertThat(RecipePaste.paste(source, Recipe(), RecipePaste.DEFAULT_GROUPS).heals).isEmpty()
        assertThat(HistoryField.HEAL_ADD.group).isEqualTo(RecipeGroup.HEALS)
    }
}
