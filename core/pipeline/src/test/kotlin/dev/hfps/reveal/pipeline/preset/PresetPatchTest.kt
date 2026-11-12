package dev.hfps.reveal.pipeline.preset

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

/** The patch model: which fields a preset sets, applying it, and its JSON (docs/adr/0019). */
class PresetPatchTest {
    private val edit = Recipe(
        profile = Profile(BaseLook.VIVID, 140f, Treatment.COLOUR),
        light = Light(exposureEv = 0.7f, contrast = 25f, shadows = 30f, curve = Curves(rgb = Curves.MEDIUM_CONTRAST)),
        colour = Colour(
            WhiteBalance(5200f, 4f, WbMode.CUSTOM),
            vibrance = 15f,
            mixer = ColourMixer(hue = BandValues(orange = -10f)),
            grading = ColourGrading(shadows = GradeWheel(220f, 30f, -5f)),
        ),
        effects = Effects(clarity = 20f, grainAmount = 30f),
        optics = Optics(lensCorrections = true, caRed = 3f),
        geometry = Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f), angle = 2f),
        masks = listOf(Mask("m")),
    )

    private fun parse(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `the registry derives every presettable field and excludes the frame and per-photo values`() {
        val paths = PresetFields.all.map { it.path }
        assertThat(paths).containsAtLeast(
            "profile.base", "profile.amount", "light.exposure", "light.curve.rgb", "colour.whiteBalance.temperature",
            "colour.mixer.hue.red", "colour.grading.global.hue", "effects.grainRoughness", "detail.nrColour",
            "optics.lensCorrections", "geometry.transform.vertical",
        )
        assertThat(paths.filter { PresetFields.isExcluded(it) }).isEmpty()
        assertThat(paths.none { it.startsWith("geometry.crop") || it == "geometry.angle" || it.startsWith("masks") })
            .isTrue()
        assertThat(paths).doesNotContain("optics.caRed")
        assertThat(paths).doesNotContain("geometry.transform.upright")
        // Every field has a section, every scalar slider a range.
        PresetFields.all.forEach { assertThat(PresetSection.entries.count { s -> s.owns(it.path) }).isEqualTo(1) }
        assertThat(PresetFields["light.exposure"]!!.range).isNotNull()
        assertThat(PresetFields["light.curve.rgb"]!!.kind).isEqualTo(FieldKind.CURVE)
        assertThat(PresetFields["profile.base"]!!.kind).isEqualTo(FieldKind.SWITCH)
        assertThat(PresetFields["colour.whiteBalance.temperature"]!!.kind).isEqualTo(FieldKind.KELVIN)
    }

    @Test
    fun `a patch from sections keeps only those fields and applying it leaves the others alone`() {
        val patch = PresetPatch.fromSections(edit, listOf(PresetSection.CONTRAST, PresetSection.TONE_CURVE))
        assertThat(patch.paths).contains("light.contrast")
        assertThat(patch.paths).contains("light.curve.rgb")
        assertThat(patch.paths).doesNotContain("light.exposure")
        val target = Recipe(light = Light(exposureEv = -1f, contrast = -40f), colour = Colour(saturation = 9f))
        val applied = patch.applyFull(target)
        assertThat(applied.light.contrast).isEqualTo(25f)
        assertThat(applied.light.curve).isEqualTo(edit.light.curve)
        assertThat(applied.light.exposureEv).isEqualTo(-1f)
        assertThat(applied.colour.saturation).isEqualTo(9f)
    }

    @Test
    fun `a section left at its default still sets it, so a preset can reset a slider`() {
        val patch = PresetPatch.fromSections(Recipe.DEFAULT, listOf(PresetSection.CLARITY))
        val applied = patch.applyFull(Recipe(effects = Effects(clarity = 50f)))
        assertThat(applied.effects.clarity).isEqualTo(0f)
    }

    @Test
    fun `the json round trip is a nested partial recipe`() {
        val patch = PresetPatch.fromSections(edit, PresetSection.entries)
        val json = patch.toJson()
        assertThat((json["light"] as JsonObject)["exposure"].toString()).isEqualTo("0.7")
        assertThat(json.containsKey("geometry")).isTrue() // transform sliders only
        assertThat((json["geometry"] as JsonObject).keys).containsExactly("transform")
        assertThat(json.containsKey("masks")).isFalse()
        val decoded = PresetPatch.fromJson(json)
        assertThat(decoded.ignored).isEmpty()
        assertThat(decoded.patch).isEqualTo(patch)
        assertThat(decoded.patch.applyFull(Recipe.DEFAULT)).isEqualTo(patch.applyFull(Recipe.DEFAULT))
    }

    @Test
    fun `every section applied at full strength reproduces the edit's style`() {
        val patch = PresetPatch.fromSections(edit, PresetSection.entries)
        val applied = patch.applyFull(Recipe(geometry = Geometry(rotate = 1)))
        assertThat(applied.profile).isEqualTo(edit.profile)
        assertThat(applied.light).isEqualTo(edit.light)
        assertThat(applied.colour).isEqualTo(edit.colour)
        assertThat(applied.effects).isEqualTo(edit.effects)
        assertThat(applied.optics).isEqualTo(edit.optics.copy(caRed = 0f)) // the CA estimate is the photo's own
        assertThat(applied.geometry).isEqualTo(Geometry(rotate = 1)) // never the crop, angle or turns
        assertThat(applied.masks).isEmpty()
    }

    @Test
    fun `unknown fields, frame fields and unknown enum values are ignored and listed`() {
        val decoded = PresetPatch.fromJson(
            parse(
                """{"light": {"contrast": 10, "futureSlider": 3}, "geometry": {"crop": {"left": 0}},
                   "profile": {"base": "hyperreal"}, "masks": [], "newGroup": {"a": 1}}""",
            ),
        )
        assertThat(decoded.patch.paths).containsExactly("light.contrast")
        assertThat(decoded.ignored).containsExactly(
            "light.futureSlider",
            "geometry.crop",
            "profile.base",
            "masks",
            "newGroup.a",
        )
    }

    @Test
    fun `values outside their range are clamped`() {
        val patch = PresetPatch.fromJson(parse("""{"light": {"exposure": 40, "contrast": -900}}""")).patch
        val applied = patch.applyFull(Recipe.DEFAULT)
        assertThat(applied.light.exposureEv).isEqualTo(5f)
        assertThat(applied.light.contrast).isEqualTo(-100f)
    }

    @Test
    fun `wrong types and malformed curves are refused`() {
        listOf(
            """{"light": {"exposure": "0.5"}}""",
            """{"light": {"exposure": true}}""",
            """{"light": {"exposure": null}}""",
            """{"optics": {"lensCorrections": 1}}""",
            """{"profile": {"base": 3}}""",
            """{"light": {"curve": {"rgb": [{"x": 0, "y": 0}]}}}""",
            """{"light": {"curve": {"rgb": [{"x": 0.5, "y": 0}, {"x": 0.2, "y": 1}]}}}""",
            """{"light": {"curve": {"rgb": [{"x": 0, "y": 0}, {"x": 1, "y": 2}]}}}""",
            """{"light": {"curve": {"rgb": {"x": 0}}}}""",
        ).forEach { text ->
            val e = assertThrows(PresetFormatException::class.java) { PresetPatch.fromJson(parse(text)) }
            assertThat(e.error).isEqualTo(PresetError.INVALID_VALUE)
        }
    }

    @Test
    fun `a null temperature means as shot and is a valid value`() {
        val patch = PresetPatch.fromJson(
            parse("""{"colour": {"whiteBalance": {"temperature": null, "mode": "as_shot"}}}"""),
        )
        val applied = patch.patch.applyFull(Recipe(colour = Colour(WhiteBalance(4000f, null, WbMode.CUSTOM))))
        assertThat(applied.colour.whiteBalance.temperatureK).isNull()
        assertThat(applied.colour.whiteBalance.mode).isEqualTo(WbMode.AS_SHOT)
    }

    @Test
    fun `the chooser suggests the changed style sections, not the photo-specific ones`() {
        assertThat(PresetSection.changedIn(Recipe.DEFAULT)).isEmpty()
        val changed = PresetSection.changedIn(edit)
        assertThat(changed).containsAtLeast(
            PresetSection.PROFILE, PresetSection.EXPOSURE, PresetSection.CONTRAST, PresetSection.TONE_CURVE,
            PresetSection.WHITE_BALANCE, PresetSection.COLOUR_MIXER, PresetSection.LENS_CORRECTIONS,
        )
        val suggested = PresetSection.suggestedFor(edit)
        assertThat(suggested).containsNoneOf(
            PresetSection.EXPOSURE, PresetSection.WHITE_BALANCE, PresetSection.LENS_CORRECTIONS,
        )
        assertThat(suggested).contains(PresetSection.CONTRAST)
    }
}
