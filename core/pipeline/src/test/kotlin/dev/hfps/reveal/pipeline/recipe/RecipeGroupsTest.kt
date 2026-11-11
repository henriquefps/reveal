package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecipeGroupsTest {
    private val edited = Recipe(
        profile = Profile(BaseLook.VIVID, 150f, Treatment.BLACK_WHITE),
        light = Light(
            1f,
            10f,
            20f,
            30f,
            40f,
            50f,
            Curves(
                ParametricCurve(1f, 2f, 3f, 4f, 20f, 45f, 70f),
                Curves.MEDIUM_CONTRAST,
                Curves.STRONG_CONTRAST,
                Curves.MEDIUM_CONTRAST,
                Curves.STRONG_CONTRAST,
            ),
        ),
        colour = Colour(
            WhiteBalance(5000f, 3f, WbMode.CUSTOM, 5f, 6f),
            7f,
            8f,
            ColourMixer(
                BandValues.of(List(8) { it + 1f }),
                BandValues.of(List(8) { it + 2f }),
                BandValues.of(
                    List(8) {
                        it +
                            3f
                    },
                ),
            ),
            ColourGrading(
                GradeWheel(1f, 2f, 3f),
                GradeWheel(4f, 5f, 6f),
                GradeWheel(7f, 8f, 9f),
                GradeWheel(10f, 11f, 12f),
                13f,
                14f,
            ),
            BandValues.of(List(8) { it + 4f }),
        ),
        geometry = Geometry(
            CropRect(0f, 0f, 0.5f, 0.5f),
            2f,
            1,
            flipH = true,
            flipV = true,
            aspect = Aspect(locked = true),
            transform = Transform(
                vertical = 10f,
                horizontal = -5f,
                rotate = 1.5f,
                aspect = 4f,
                scale = 90f,
                offsetX = 3f,
                offsetY = -2f,
                upright = UprightMode.GUIDED,
                guides = listOf(GuideLine(0.1f, 0.1f, 0.1f, 0.9f)),
            ),
        ),
        optics = Optics(
            lensCorrections = true,
            distortionPercent = 80f,
            vignettingPercent = 120f,
            removeCa = true,
            caRed = 3f,
            caBlue = -2f,
            purpleAmount = 5f,
            purpleHueLow = 20f,
            purpleHueHigh = 80f,
            greenAmount = 4f,
            greenHueLow = 30f,
            greenHueHigh = 70f,
        ),
        detail = Detail(10f, 1.5f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 95f),
        effects = Effects(11f, 12f, 13f, 14f, 15f, 16f, 17f, 18f, 19f, 20f, 21f),
        masks = listOf(Mask("m")),
        heals = listOf(Heal("h")),
    )

    @Test
    fun `every history field belongs to exactly one group and every group has fields or is reserved`() {
        // The group is a constructor property, so "exactly one" holds by construction; check coverage.
        val used = HistoryField.entries.map { it.group }.toSet()
        val reserved =
            setOf(RecipeGroup.MASKS, RecipeGroup.HEALS)
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
