package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecipeRangesTest {
    @Test
    fun `every control has exactly one descriptor`() {
        assertThat(RecipeRanges.all.map { it.id }).containsExactlyElementsIn(ControlId.entries)
        assertThat(RecipeRanges.all.map { it.id }.toSet()).hasSize(RecipeRanges.all.size)
        for (id in ControlId.entries) assertThat(RecipeRanges[id].id).isEqualTo(id)
    }

    @Test
    fun `ranges are sane`() {
        for (r in RecipeRanges.all) {
            assertThat(r.default).isAtLeast(r.min)
            assertThat(r.default).isAtMost(r.max)
            assertThat(r.fineStep).isAtMost(r.step)
            if (r.bipolar) {
                assertThat(r.neutral).isEqualTo(r.default)
                assertThat(r.min).isEqualTo(-r.max)
            }
        }
    }

    @Test
    fun `specified ranges and steps`() {
        with(RecipeRanges.exposure) {
            assertThat(min).isEqualTo(-5f)
            assertThat(max).isEqualTo(5f)
            assertThat(step).isEqualTo(0.01f)
        }
        for (r in listOf(
            RecipeRanges.contrast, RecipeRanges.highlights, RecipeRanges.shadows, RecipeRanges.whites,
            RecipeRanges.blacks, RecipeRanges.vibrance, RecipeRanges.saturation, RecipeRanges.relTemperature,
            RecipeRanges.relTint,
        )) {
            assertThat(r.min).isEqualTo(-100f)
            assertThat(r.max).isEqualTo(100f)
            assertThat(r.step).isEqualTo(1f)
        }
        assertThat(RecipeRanges.profileAmount.min).isEqualTo(0f)
        assertThat(RecipeRanges.profileAmount.max).isEqualTo(200f)
        assertThat(RecipeRanges.profileAmount.default).isEqualTo(100f)
        assertThat(RecipeRanges.angle.max).isEqualTo(45f)
        assertThat(RecipeRanges.angle.step).isEqualTo(0.1f)
        assertThat(RecipeRanges.temperature.min).isEqualTo(2000f)
        assertThat(RecipeRanges.temperature.max).isEqualTo(15000f)
    }

    @Test
    fun `recipe defaults equal the neutral values`() {
        val r = Recipe()
        assertThat(r.light.exposureEv).isEqualTo(RecipeRanges.exposure.neutral)
        assertThat(r.light.contrast).isEqualTo(RecipeRanges.contrast.neutral)
        assertThat(r.light.blacks).isEqualTo(RecipeRanges.blacks.neutral)
        assertThat(r.colour.vibrance).isEqualTo(RecipeRanges.vibrance.neutral)
        assertThat(r.colour.whiteBalance.relTint).isEqualTo(RecipeRanges.relTint.neutral)
        assertThat(r.profile.amountPercent).isEqualTo(RecipeRanges.profileAmount.default)
        assertThat(r.geometry.angle).isEqualTo(RecipeRanges.angle.neutral)
    }

    @Test
    fun `clamp and snap`() {
        assertThat(RecipeRanges.contrast.clamp(250f)).isEqualTo(100f)
        assertThat(RecipeRanges.contrast.clamp(-250f)).isEqualTo(-100f)
        assertThat(RecipeRanges.contrast.snap(12.4f)).isEqualTo(12f)
        assertThat(RecipeRanges.temperature.snap(5612f)).isEqualTo(5600f)
        assertThat(RecipeRanges.temperature.snap(99999f)).isEqualTo(15000f)
    }

    @Test
    fun `values are formatted the way the plan says`() {
        assertThat(RecipeRanges.exposure.format(0.7f)).isEqualTo("+0.70")
        assertThat(RecipeRanges.exposure.format(-1.25f)).isEqualTo("−1.25")
        assertThat(RecipeRanges.exposure.format(0f)).isEqualTo("0.00")
        assertThat(RecipeRanges.contrast.format(-12f)).isEqualTo("−12")
        assertThat(RecipeRanges.contrast.format(12f)).isEqualTo("+12")
        assertThat(RecipeRanges.contrast.format(0f)).isEqualTo("0")
        assertThat(RecipeRanges.temperature.format(5600f)).isEqualTo("5600 K")
        assertThat(RecipeRanges.profileAmount.format(100f)).isEqualTo("100 %")
        assertThat(RecipeRanges.angle.format(1.5f)).isEqualTo("1.5°")
        assertThat(RecipeRanges.angle.format(-3f)).isEqualTo("−3.0°")
        assertThat(RecipeRanges.angle.format(0f)).isEqualTo("0.0°")
    }

    @Test
    fun `formatting ignores the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pt-BR"))
            assertThat(RecipeRanges.exposure.format(0.7f)).isEqualTo("+0.70")
            assertThat(RecipeRanges.angle.format(1.5f)).isEqualTo("1.5°")
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
