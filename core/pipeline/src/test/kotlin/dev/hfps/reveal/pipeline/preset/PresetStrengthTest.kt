package dev.hfps.reveal.pipeline.preset

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.MonotoneSpline
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import kotlin.math.abs
import org.junit.Test

/** Strength per field kind (docs/adr/0019, "Strength"). */
class PresetStrengthTest {
    private fun patchOf(recipe: Recipe, vararg sections: PresetSection) =
        PresetPatch.fromSections(recipe, sections.toList())

    @Test
    fun `strength 0 is the photo, 100 percent the preset, exactly`() {
        val preset = Recipe(light = Light(exposureEv = 0.33f, contrast = 17f), profile = Profile(BaseLook.VIVID, 120f))
        val patch = PresetPatch.fromSections(preset, PresetSection.entries)
        val photo = Recipe(light = Light(exposureEv = -1.1f, contrast = -3f), colour = Colour(saturation = 12f))
        assertThat(patch.apply(photo, 0f)).isEqualTo(photo)
        val full = patch.apply(photo, 1f)
        assertThat(full.light).isEqualTo(preset.light)
        assertThat(full.profile).isEqualTo(preset.profile)
    }

    @Test
    fun `additive sliders scale from the photo's value and extrapolate to 200 percent, clamped`() {
        val patch =
            patchOf(
                Recipe(light = Light(contrast = 40f, exposureEv = 2f)),
                PresetSection.CONTRAST,
                PresetSection.EXPOSURE,
            )
        val photo = Recipe(light = Light(contrast = 10f, exposureEv = 1f))
        assertThat(patch.apply(photo, 0.5f).light.contrast).isWithin(1e-4f).of(25f)
        assertThat(patch.apply(photo, 2f).light.contrast).isWithin(1e-4f).of(70f)
        assertThat(patch.apply(photo, 0.25f).light.exposureEv).isWithin(1e-4f).of(1.25f)
        // 1 + 2 * (2 - 1) = 3; contrast past its range clamps.
        val strong = patchOf(Recipe(light = Light(contrast = 90f)), PresetSection.CONTRAST)
        assertThat(strong.apply(Recipe(light = Light(contrast = 20f)), 2f).light.contrast).isEqualTo(100f)
        // Strength itself clamps to 0..2.
        assertThat(patch.apply(photo, 9f)).isEqualTo(patch.apply(photo, 2f))
    }

    @Test
    fun `multiplicative values interpolate in log space`() {
        val patch = patchOf(
            Recipe(detail = Detail(sharpenRadius = 2f), geometry = Geometry(transform = Transform(scale = 120f))),
            PresetSection.SHARPENING,
            PresetSection.TRANSFORM,
        )
        val photo = Recipe(detail = Detail(sharpenRadius = 0.5f))
        // Geometric mean of 0.5 and 2 is 1.
        assertThat(patch.apply(photo, 0.5f).detail.sharpenRadius).isWithin(1e-4f).of(1f)
        assertThat(patch.apply(photo, 2f).detail.sharpenRadius).isEqualTo(3f) // 8, clamped to the range
        assertThat(
            patch.apply(photo, 0.5f).geometry.transform.scale,
        ).isWithin(1e-3f).of(Math.sqrt(120.0).toFloat() * 10f)
    }

    @Test
    fun `temperature blends in mired, from as shot when the context knows it`() {
        val patch =
            patchOf(Recipe(colour = Colour(WhiteBalance(3000f, 10f, WbMode.CUSTOM))), PresetSection.WHITE_BALANCE)
        val photo = Recipe(colour = Colour(WhiteBalance(6000f, 0f, WbMode.CUSTOM)))
        // Mired: 166.7 and 333.3, half way 250 = 4000 K.
        assertThat(patch.apply(photo, 0.5f).colour.whiteBalance.temperatureK!!).isWithin(0.5f).of(4000f)
        assertThat(patch.apply(photo, 0.5f).colour.whiteBalance.tint!!).isWithin(1e-4f).of(5f)

        val asShot = Recipe() // temperature null
        val ctx = ApplyContext(asShotTemperatureK = 6000f, asShotTint = 0f)
        assertThat(patch.apply(asShot, 0.5f, ctx).colour.whiteBalance.temperatureK!!).isWithin(0.5f).of(4000f)
        // Without the camera's value the absolute pair switches at 50 %.
        assertThat(patch.apply(asShot, 0.4f).colour.whiteBalance.temperatureK).isNull()
        assertThat(patch.apply(asShot, 0.6f).colour.whiteBalance.temperatureK).isEqualTo(3000f)
        // A preset that says "as shot" lands on null at 100 %.
        val toAsShot = patchOf(Recipe(), PresetSection.WHITE_BALANCE)
        assertThat(toAsShot.apply(photo, 1f, ctx).colour.whiteBalance.temperatureK).isNull()
        assertThat(toAsShot.apply(photo, 0.5f, ctx).colour.whiteBalance.temperatureK!!).isWithin(0.5f).of(6000f)
    }

    @Test
    fun `each photo of a batch blends from its own as-shot white balance`() {
        val patch =
            patchOf(Recipe(colour = Colour(WhiteBalance(5000f, 10f, WbMode.CUSTOM))), PresetSection.WHITE_BALANCE)
        assertThat(patch.usesAsShot).isTrue()
        assertThat(patchOf(Recipe(light = Light(contrast = 20f)), PresetSection.CONTRAST).usesAsShot).isFalse()
        val unedited = Recipe() // as shot
        // Two RAWs shot at 3200 K and 7000 K: half way in mired to 5000 K, each from its own camera value.
        val tungsten = patch.apply(unedited, 0.5f, ApplyContext(3200f, -4f)).colour.whiteBalance
        val shade = patch.apply(unedited, 0.5f, ApplyContext(7000f, 6f)).colour.whiteBalance
        assertThat(tungsten.temperatureK!!).isWithin(0.5f).of(mired(3200f, 5000f))
        assertThat(shade.temperatureK!!).isWithin(0.5f).of(mired(7000f, 5000f))
        assertThat(tungsten.tint!!).isWithin(1e-4f).of(3f)
        assertThat(shade.tint!!).isWithin(1e-4f).of(8f)
        // The context rounds the camera's values as the editor's sliders show them; a JPEG has none.
        val ctx = ApplyContext.of(dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel.Cct(5123.4, -2.6))
        assertThat(ctx).isEqualTo(ApplyContext(5123f, -3f))
        assertThat(ApplyContext.of(null as dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel.Cct?))
            .isEqualTo(ApplyContext.UNKNOWN)
    }

    private fun mired(from: Float, to: Float): Float = (1e6 / ((1e6 / from + 1e6 / to) / 2)).toFloat()

    @Test
    fun `switches take the preset's value from half strength`() {
        val patch = patchOf(
            Recipe(profile = Profile(BaseLook.PORTRAIT, 100f, Treatment.BLACK_WHITE)),
            PresetSection.PROFILE,
            PresetSection.TREATMENT,
        )
        val photo = Recipe(profile = Profile(BaseLook.VIVID, 100f))
        assertThat(patch.apply(photo, 0.49f).profile.base).isEqualTo(BaseLook.VIVID)
        assertThat(patch.apply(photo, 0.49f).profile.treatment).isEqualTo(Treatment.COLOUR)
        assertThat(patch.apply(photo, 0.5f).profile.base).isEqualTo(BaseLook.PORTRAIT)
        assertThat(patch.apply(photo, 2f).profile.treatment).isEqualTo(Treatment.BLACK_WHITE)
    }

    @Test
    fun `point curves blend their sampled y values and stay valid`() {
        val preset = Recipe(light = Light(curve = Curves(rgb = Curves.STRONG_CONTRAST)))
        val patch = patchOf(preset, PresetSection.TONE_CURVE)
        val half = patch.apply(Recipe(), 0.5f).light.curve.rgb
        val strong = MonotoneSpline.of(Curves.STRONG_CONTRAST)!!
        for (p in half) {
            val expected = (p.x + strong.eval(p.x.toDouble()).toFloat()) / 2f
            assertThat(p.y).isWithin(1e-5f).of(expected)
        }
        // 200 % of a straight photo curve stays inside [0, 1] with increasing x.
        val double = patch.apply(Recipe(), 2f).light.curve.rgb
        assertThat(double.all { it.y in 0f..1f }).isTrue()
        assertThat(double.zipWithNext().all { (a, b) -> a.x < b.x }).isTrue()
        // A preset with a straight curve flattens the photo's.
        val flatten = patchOf(Recipe(), PresetSection.TONE_CURVE)
        val photo = Recipe(light = Light(curve = Curves(red = Curves.MEDIUM_CONTRAST)))
        assertThat(flatten.apply(photo, 1f).light.curve.red).isEmpty()
        assertThat(flatten.apply(photo, 0.5f).light.curve.red).isNotEmpty()
        // Many points: sampled at 16 evenly spaced x.
        val busy = List(16) { CurvePoint(it / 15f, (it / 15f) * (it / 15f)) }
        val other = List(16) { CurvePoint(it / 15.5f, (it / 15.5f)) }.drop(1).let { listOf(CurvePoint(0f, 0.1f)) + it }
        val many = patchOf(Recipe(light = Light(curve = Curves(green = busy))), PresetSection.TONE_CURVE)
        val result = many.apply(Recipe(light = Light(curve = Curves(green = other))), 0.3f).light.curve.green
        assertThat(result.size).isAtMost(Curves.MAX_POINTS)
    }

    @Test
    fun `a grading tint fades through grey, a hue alone takes the shortest arc`() {
        val preset = Recipe(colour = Colour(grading = ColourGrading(shadows = GradeWheel(0f, 40f))))
        val photo = Recipe(colour = Colour(grading = ColourGrading(shadows = GradeWheel(180f, 40f))))
        val patch = patchOf(preset, PresetSection.COLOUR_GRADING)
        val half = patch.apply(photo, 0.5f).colour.grading.shadows
        assertThat(half.saturation).isWithin(1e-3f).of(0f) // opposite tints cancel
        val quarter = patch.apply(photo, 0.25f).colour.grading.shadows
        assertThat(quarter.hue).isWithin(1e-3f).of(180f)
        assertThat(quarter.saturation).isWithin(1e-3f).of(20f)
        // From no tint the preset's hue is kept and only saturation grows.
        val fromGrey = patch.apply(Recipe(), 0.5f).colour.grading.shadows
        assertThat(fromGrey.hue).isWithin(1e-3f).of(0f)
        assertThat(fromGrey.saturation).isWithin(1e-3f).of(20f)

        val hueOnly = PresetPatch.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"colour":{"grading":{"global":{"hue":10}}}}""")
                as kotlinx.serialization.json.JsonObject,
        ).patch
        val arc = hueOnly.apply(Recipe(colour = Colour(grading = ColourGrading(global = GradeWheel(350f, 30f)))), 0.5f)
        assertThat(arc.colour.grading.global.hue).isWithin(1e-3f).of(0f)
        assertThat(arc.colour.grading.global.saturation).isEqualTo(30f)
    }

    @Test
    fun `every field kind keeps the recipe valid at any strength`() {
        val preset = Recipe(
            profile = Profile(BaseLook.LANDSCAPE, 180f, Treatment.BLACK_WHITE),
            light = Light(2f, 50f, -60f, 70f, 10f, -10f, Curves(rgb = Curves.STRONG_CONTRAST)),
            colour = Colour(WhiteBalance(9000f, 40f, WbMode.CLOUDY, 30f, -10f), 40f, -30f),
        )
        val patch = PresetPatch.fromSections(preset, PresetSection.entries)
        val photo = Recipe(colour = Colour(WhiteBalance(2500f, -100f, WbMode.CUSTOM)))
        var s = 0f
        while (s <= 2f) {
            val r = patch.apply(photo, s, ApplyContext(5000f, 0f))
            assertThat(abs(r.light.contrast)).isAtMost(100f)
            assertThat(r.colour.whiteBalance.temperatureK!!).isIn(com.google.common.collect.Range.closed(2000f, 15000f))
            s += 0.05f
        }
    }
}
