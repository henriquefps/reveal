package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecipeGroupsTest {
    private val edited = Recipe(
        profile = Profile(BaseLook.VIVID, 150f),
        light = Light(1f, 10f, 20f, 30f, 40f, 50f),
        colour = Colour(WhiteBalance(5000f, 3f, WbMode.CUSTOM, 5f, 6f), 7f, 8f),
        geometry = Geometry(
            CropRect(0f, 0f, 0.5f, 0.5f),
            2f,
            1,
            flipH = true,
            flipV = true,
            aspect = Aspect(locked = true),
        ),
        masks = listOf(Mask("m")),
        heals = listOf(Heal("h")),
    )

    @Test
    fun `every history field belongs to exactly one group and every group has fields or is reserved`() {
        // The group is a constructor property, so "exactly one" holds by construction; check coverage.
        val used = HistoryField.entries.map { it.group }.toSet()
        val reserved =
            setOf(RecipeGroup.EFFECTS, RecipeGroup.DETAIL, RecipeGroup.OPTICS, RecipeGroup.MASKS, RecipeGroup.HEALS)
        assertThat(used + reserved).containsExactlyElementsIn(RecipeGroup.entries)
    }

    @Test
    fun `pasting a group changes only that group's history fields`() {
        for (group in RecipeGroup.entries) {
            val pasted = group.copy(edited, Recipe())
            val fields = RecipeDiff.between(Recipe(), pasted).map { it.field }
            assertThat(fields.map { it.group }.toSet() - group).isEmpty()
        }
    }

    @Test
    fun `the groups together cover every field`() {
        var all = Recipe()
        for (group in RecipeGroup.entries) all = group.copy(edited, all)
        assertThat(all).isEqualTo(edited)
        for (group in RecipeGroup.entries) all = group.reset(all)
        assertThat(all.isDefault()).isTrue()
    }

    @Test
    fun `every history field is produced by a diff of the fully edited recipe`() {
        val fields = RecipeDiff.between(Recipe(), edited).map { it.field }.toSet()
        assertThat(fields).containsExactlyElementsIn(HistoryField.entries)
    }
}
