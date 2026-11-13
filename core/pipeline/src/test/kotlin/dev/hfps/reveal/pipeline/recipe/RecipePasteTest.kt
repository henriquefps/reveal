package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Paste semantics per group (docs/plan/product.md "copy/paste edits with a chooser"). */
class RecipePasteTest {
    private val source = Recipe(
        profile = Profile(BaseLook.VIVID, 150f),
        light = Light(exposureEv = 1f, contrast = 10f),
        colour = Colour(WhiteBalance(5000f, 3f, WbMode.CUSTOM, 5f, 6f), vibrance = 7f),
        geometry = Geometry(CropRect(0.1f, 0.1f, 0.9f, 0.9f), angle = 2f),
        masks = listOf(Mask("m")),
    )

    private val target = Recipe(
        light = Light(exposureEv = -0.5f, shadows = 40f),
        colour = Colour(saturation = -20f),
        geometry = Geometry(rotate = 1, flipH = true),
    )

    @Test
    fun `the default choice is everything but geometry and masks`() {
        assertThat(RecipePaste.DEFAULT_GROUPS).doesNotContain(RecipeGroup.GEOMETRY)
        assertThat(RecipePaste.DEFAULT_GROUPS).doesNotContain(RecipeGroup.MASKS)
        assertThat(RecipePaste.DEFAULT_GROUPS + RecipeGroup.GEOMETRY + RecipeGroup.MASKS)
            .containsExactlyElementsIn(RecipeGroup.entries)
        val pasted = RecipePaste.paste(source, target, RecipePaste.DEFAULT_GROUPS)
        assertThat(pasted.geometry).isEqualTo(target.geometry)
        assertThat(pasted.masks).isEqualTo(target.masks)
        assertThat(pasted.copy(geometry = source.geometry, masks = source.masks)).isEqualTo(source)
    }

    @Test
    fun `all groups make the target the source`() {
        assertThat(RecipePaste.paste(source, target, RecipeGroup.entries.toSet())).isEqualTo(source)
    }

    @Test
    fun `no groups change nothing`() {
        assertThat(RecipePaste.paste(source, target, emptySet())).isEqualTo(target)
    }

    @Test
    fun `a group replaces the whole group, including fields the source left at default`() {
        val pasted = RecipePaste.paste(source, target, setOf(RecipeGroup.LIGHT))
        // Shadows was edited on the target only: pasting Light carries the source's 0.
        assertThat(pasted.light).isEqualTo(source.light)
        assertThat(pasted.light.shadows).isEqualTo(0f)
        assertThat(pasted.copy(light = target.light)).isEqualTo(target)
    }

    @Test
    fun `each group touches only its own history fields`() {
        for (group in RecipeGroup.entries) {
            val pasted = RecipePaste.paste(source, target, setOf(group))
            val touched = RecipeDiff.between(target, pasted).map { it.field.group }.toSet()
            assertThat(touched - group).isEmpty()
        }
    }

    @Test
    fun `colour carries both white-balance pairs, so RAW and JPEG targets each read theirs`() {
        val pasted = RecipePaste.paste(source, Recipe(), setOf(RecipeGroup.COLOUR))
        val wb = pasted.colour.whiteBalance
        assertThat(wb.temperatureK).isEqualTo(5000f)
        assertThat(wb.relTemperature).isEqualTo(5f)
    }

    @Test
    fun `masks and geometry travel only when chosen`() {
        assertThat(RecipePaste.paste(source, target, setOf(RecipeGroup.LIGHT)).masks).isEmpty()
        assertThat(RecipePaste.paste(source, target, setOf(RecipeGroup.MASKS)).masks).isEqualTo(source.masks)
        assertThat(RecipePaste.paste(source, target, setOf(RecipeGroup.GEOMETRY)).geometry).isEqualTo(source.geometry)
    }
}
