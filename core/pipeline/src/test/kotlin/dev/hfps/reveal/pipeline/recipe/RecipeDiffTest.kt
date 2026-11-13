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

        // Colour-tool fields after TREATMENT share templates ("%1$s: hue %2$s"), checked below.
        for (field in HistoryField.entries.filter { it.ordinal <= HistoryField.TREATMENT.ordinal }) {
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
    fun `colour tool templates and names exist in both languages`() {
        val english = java.io.File("src/main/res/values/strings.xml").readText()
        val portuguese = java.io.File("src/main/res/values-pt-rBR/strings.xml").readText()
        val names = listOf(
            "history_curve_shadows", "history_curve_darks", "history_curve_lights", "history_curve_highlights",
            "history_curve_split_shadows", "history_curve_split_midtones", "history_curve_split_highlights",
            "history_point_curve", "history_band_hue", "history_band_saturation", "history_band_luminance",
            "history_grey_mix", "history_grade_blending", "history_grade_balance", "history_value_treatment_colour",
            "history_value_treatment_black_white", "history_action_auto_mix", "history_action_curve_preset",
        ) + ColourBand.entries.map { "colour_band_${it.name.lowercase()}" } +
            GradeRegion.entries.map { "grade_region_${it.name.lowercase()}" } +
            CurveChannel.entries.map { "curve_channel_${it.name.lowercase()}" }
        for (name in names) {
            assertThat(english).contains("name=\"$name\"")
            assertThat(portuguese).contains("name=\"$name\"")
        }
    }

    @Test
    fun `colour tool fields have labelled steps`() {
        fun one(after: Recipe) = RecipeDiff.between(base, after).single()

        assertThat(one(base.copy(profile = Profile(treatment = Treatment.BLACK_WHITE))))
            .isEqualTo(HistoryStep(HistoryField.TREATMENT, "black_white"))
        assertThat(one(base.copy(light = Light(curve = Curves(parametric = ParametricCurve(darks = -20f))))))
            .isEqualTo(HistoryStep(HistoryField.CURVE_DARKS, "−20"))
        assertThat(one(base.copy(light = Light(curve = Curves(parametric = ParametricCurve(splitShadows = 30f))))))
            .isEqualTo(HistoryStep(HistoryField.CURVE_SPLIT_SHADOWS, "30 %"))
        assertThat(one(base.copy(light = Light(curve = Curves(green = Curves.MEDIUM_CONTRAST)))))
            .isEqualTo(HistoryStep(HistoryField.CURVE_GREEN, null))
        assertThat(one(base.copy(colour = Colour(mixer = ColourMixer(hue = BandValues(aqua = 15f))))))
            .isEqualTo(HistoryStep(HistoryField.HUE_AQUA, "+15"))
        assertThat(one(base.copy(colour = Colour(mixer = ColourMixer(luminance = BandValues(blue = -40f))))))
            .isEqualTo(HistoryStep(HistoryField.LUM_BLUE, "−40"))
        assertThat(one(base.copy(colour = Colour(greyMixer = BandValues(red = 22f)))))
            .isEqualTo(HistoryStep(HistoryField.GREY_RED, "+22"))
        assertThat(one(base.copy(colour = Colour(grading = ColourGrading(shadows = GradeWheel(hue = 210f))))))
            .isEqualTo(HistoryStep(HistoryField.GRADE_SHADOWS_HUE, "210°"))
        assertThat(one(base.copy(colour = Colour(grading = ColourGrading(global = GradeWheel(saturation = 35f))))))
            .isEqualTo(HistoryStep(HistoryField.GRADE_GLOBAL_SAT, "35"))
        assertThat(one(base.copy(colour = Colour(grading = ColourGrading(blending = 70f)))))
            .isEqualTo(HistoryStep(HistoryField.GRADE_BLENDING, "70"))
    }

    @Test
    fun `colour tool controls read and write the recipe and keep splits in order`() {
        for (id in ColourToolControls.all) {
            val range = RecipeRanges[id]
            val target = if (range.bipolar) range.max * 0.4f else range.min + (range.max - range.min) * 0.3f
            val r = ColourToolControls.set(base, id, target)
            if (id !in ColourToolControls.splits) {
                assertThat(ColourToolControls.get(r, id)).isWithin(1e-4f).of(range.clamp(target))
                // No other control moved.
                ColourToolControls.all.filter { it != id }.forEach { other ->
                    assertThat(ColourToolControls.get(r, other)).isEqualTo(ColourToolControls.get(base, other))
                }
            }
        }
        val crossed = ColourToolControls.set(base, ControlId.CURVE_SPLIT_SHADOWS, 90f)
        assertThat(crossed.light.curve.parametric.splitShadows).isEqualTo(45f)
    }

    @Test
    fun `removing the crop is a step with no value`() {
        val cropped = base.copy(geometry = Geometry(crop = CropRect(0f, 0f, 0.5f, 1f)))

        assertThat(RecipeDiff.between(cropped, base)).containsExactly(HistoryStep(HistoryField.CROP, null))
    }

    private fun Recipe.withExposure(ev: Float) = copy(light = Light(ev))

    private fun Recipe.withWb(wb: WhiteBalance) = copy(colour = Colour(wb))

    @Test
    fun `detail sliders are labelled with their value`() {
        val steps = RecipeDiff.between(
            Recipe(),
            Recipe(detail = Detail(sharpenAmount = 40f, sharpenRadius = 1.4f, nrLuminance = 25f)),
        )
        assertThat(steps).containsExactly(
            HistoryStep(HistoryField.NR_LUMINANCE, "25"),
            HistoryStep(HistoryField.SHARPEN_AMOUNT, "40"),
            HistoryStep(HistoryField.SHARPEN_RADIUS, "1.4"),
        ).inOrder()
        assertThat(steps.map { it.field.group }.toSet()).containsExactly(RecipeGroup.DETAIL)
    }

    @Test
    fun `mask steps name the mask`() {
        val sky = Mask("a", name = "Sky", components = listOf(MaskComponent.Linear()))
        val face = Mask("b", name = "Face")
        val one = Recipe(masks = listOf(sky, face))
        fun steps(after: Recipe) = RecipeDiff.between(one, after)

        assertThat(RecipeDiff.between(Recipe(), one)).containsExactly(
            HistoryStep(HistoryField.MASK_ADD, "Sky"),
            HistoryStep(HistoryField.MASK_ADD, "Face"),
        ).inOrder()
        assertThat(steps(one.copy(masks = listOf(face)))).containsExactly(HistoryStep(HistoryField.MASK_REMOVE, "Sky"))
        assertThat(steps(one.copy(masks = listOf(sky.copy(name = "Clouds"), face))))
            .containsExactly(HistoryStep(HistoryField.MASK_RENAME, "Clouds"))
        assertThat(steps(one.copy(masks = listOf(sky.copy(enabled = false), face))))
            .containsExactly(HistoryStep(HistoryField.MASK_HIDE, "Sky"))
        assertThat(steps(one.copy(masks = listOf(sky, face.copy(components = listOf(MaskComponent.Radial()))))))
            .containsExactly(HistoryStep(HistoryField.MASK_SHAPE, "Face"))
        assertThat(steps(one.copy(masks = listOf(sky.copy(adjustments = LocalAdjustments(exposure = 1f)), face))))
            .containsExactly(HistoryStep(HistoryField.MASK_ADJUST, "Sky"))
        assertThat(steps(one.copy(masks = listOf(sky.copy(amount = 50f), face))))
            .containsExactly(HistoryStep(HistoryField.MASK_ADJUST, "Sky"))
        assertThat(
            steps(one.copy(masks = listOf(face, sky))),
        ).containsExactly(HistoryStep(HistoryField.MASK_ORDER, null))
        val hidden = one.copy(masks = listOf(sky.copy(enabled = false), face))
        assertThat(RecipeDiff.between(hidden, one)).containsExactly(HistoryStep(HistoryField.MASK_SHOW, "Sky"))
    }
}
