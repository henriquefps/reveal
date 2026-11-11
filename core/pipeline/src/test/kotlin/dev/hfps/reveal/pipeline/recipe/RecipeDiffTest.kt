package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecipeDiffTest {
    private val base = Recipe()

    @Test
    fun `identical recipes have no steps`() {
        assertThat(RecipeDiff.between(base, base.copy())).isEmpty()
    }

    @Test
    fun `exposure is signed with two decimals`() {
        assertThat(RecipeDiff.between(base, base.withExposure(0.7f)))
            .containsExactly(HistoryStep(HistoryField.EXPOSURE, "+0.70"))
        assertThat(RecipeDiff.between(base, base.withExposure(-1.25f)))
            .containsExactly(HistoryStep(HistoryField.EXPOSURE, "−1.25"))
    }

    @Test
    fun `sub-epsilon changes are not steps and a returning zero has no sign`() {
        assertThat(RecipeDiff.between(base, base.withExposure(0.00001f))).isEmpty()
        assertThat(RecipeDiff.between(base.withExposure(0.5f), base))
            .containsExactly(HistoryStep(HistoryField.EXPOSURE, "0.00"))
    }

    @Test
    fun `temperature and tint produce their own steps, null means as shot`() {
        val warm = base.withWb(WhiteBalance(5600f, 10f))

        assertThat(RecipeDiff.between(base, warm)).containsExactly(
            HistoryStep(HistoryField.TEMPERATURE, "5600 K"),
            HistoryStep(HistoryField.TINT, "+10"),
        ).inOrder()
        assertThat(RecipeDiff.between(warm, base)).containsExactly(
            HistoryStep(HistoryField.TEMPERATURE, null),
            HistoryStep(HistoryField.TINT, null),
        ).inOrder()
    }

    @Test
    fun `steps follow pipeline order`() {
        val after = base.withExposure(1f).withWb(WhiteBalance(4000f, null))

        assertThat(RecipeDiff.between(base, after).map { it.field })
            .containsExactly(HistoryField.EXPOSURE, HistoryField.TEMPERATURE).inOrder()
    }

    @Test
    fun `every history field has a string resource in both languages`() {
        val english = java.io.File("src/main/res/values/strings.xml").readText()
        val portuguese = java.io.File("src/main/res/values-pt-rBR/strings.xml").readText()

        for (field in HistoryField.entries) {
            val name = "history_${field.name.lowercase()}"
            assertThat(english).contains("name=\"$name\"")
            assertThat(portuguese).contains("name=\"$name\"")
        }
    }

    @Test
    fun `each new field has a labelled step`() {
        fun one(after: Recipe) = RecipeDiff.between(base, after).single()

        assertThat(one(base.copy(profile = Profile(BaseLook.CAMERA_MATCH))))
            .isEqualTo(HistoryStep(HistoryField.BASE_LOOK, "camera_match"))
        assertThat(one(base.copy(profile = Profile(amountPercent = 150f))))
            .isEqualTo(HistoryStep(HistoryField.PROFILE_AMOUNT, "150 %"))
        assertThat(one(base.copy(light = Light(contrast = 12f)))).isEqualTo(HistoryStep(HistoryField.CONTRAST, "+12"))
        assertThat(one(base.copy(light = Light(highlights = -40f))))
            .isEqualTo(HistoryStep(HistoryField.HIGHLIGHTS, "−40"))
        assertThat(one(base.copy(light = Light(shadows = 5f)))).isEqualTo(HistoryStep(HistoryField.SHADOWS, "+5"))
        assertThat(one(base.copy(light = Light(whites = 1f)))).isEqualTo(HistoryStep(HistoryField.WHITES, "+1"))
        assertThat(one(base.copy(light = Light(blacks = -1f)))).isEqualTo(HistoryStep(HistoryField.BLACKS, "−1"))
        assertThat(one(base.withWb(WhiteBalance(mode = WbMode.TUNGSTEN))))
            .isEqualTo(HistoryStep(HistoryField.WB_MODE, "tungsten"))
        assertThat(one(base.withWb(WhiteBalance(relTemperature = 20f))))
            .isEqualTo(HistoryStep(HistoryField.REL_TEMPERATURE, "+20"))
        assertThat(one(base.withWb(WhiteBalance(relTint = -3f))))
            .isEqualTo(HistoryStep(HistoryField.REL_TINT, "−3"))
        assertThat(one(base.copy(colour = Colour(vibrance = 30f))))
            .isEqualTo(HistoryStep(HistoryField.VIBRANCE, "+30"))
        assertThat(one(base.copy(colour = Colour(saturation = -30f))))
            .isEqualTo(HistoryStep(HistoryField.SATURATION, "−30"))
        assertThat(one(base.copy(geometry = Geometry(crop = CropRect(0f, 0f, 0.5f, 1f)))))
            .isEqualTo(HistoryStep(HistoryField.CROP, "crop"))
        assertThat(one(base.copy(geometry = Geometry(angle = 1.5f)))).isEqualTo(HistoryStep(HistoryField.ANGLE, "1.5°"))
        assertThat(one(base.copy(geometry = Geometry(rotate = 3)))).isEqualTo(HistoryStep(HistoryField.ROTATE, "270°"))
        assertThat(one(base.copy(geometry = Geometry(flipH = true)))).isEqualTo(HistoryStep(HistoryField.FLIP_H, null))
        assertThat(one(base.copy(geometry = Geometry(flipV = true)))).isEqualTo(HistoryStep(HistoryField.FLIP_V, null))
        assertThat(one(base.copy(geometry = Geometry(aspect = Aspect(AspectPreset.R16_9)))))
            .isEqualTo(HistoryStep(HistoryField.ASPECT, "r16_9"))
    }

    @Test
    fun `removing the crop is a step with no value`() {
        val cropped = base.copy(geometry = Geometry(crop = CropRect(0f, 0f, 0.5f, 1f)))

        assertThat(RecipeDiff.between(cropped, base)).containsExactly(HistoryStep(HistoryField.CROP, null))
    }

    private fun Recipe.withExposure(ev: Float) = copy(light = Light(ev))

    private fun Recipe.withWb(wb: WhiteBalance) = copy(colour = Colour(wb))
}
