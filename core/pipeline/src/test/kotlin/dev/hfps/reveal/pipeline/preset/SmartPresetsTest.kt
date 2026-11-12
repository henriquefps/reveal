@file:Suppress("MaxLineLength")

package dev.hfps.reveal.pipeline.preset

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.PhotoFeatures
import dev.hfps.reveal.pipeline.faces.FaceFixtures
import dev.hfps.reveal.pipeline.geometry.ImageLines
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.synthetic.PerspectiveScenes
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** Smart presets (docs/adr/0020): the rules' file format, the correction per photo, batch adaptation, strength. */
class SmartPresetsTest {
    private val style = PresetPatch.fromJson(
        Json.parseToJsonElement(
            """{"colour":{"saturation":-10},"effects":{"grainAmount":20,"clarity":12}}""",
        ) as JsonObject,
    ).patch
    private val rules = SmartRules(
        SmartRules.ExposureRule(),
        SmartRules.ToneRule(),
        SmartRules.WhiteBalanceRule(),
        SmartRules.VibranceRule(),
    )
    private val preset = Preset(PresetIds.newId(), "Smart test", PresetPatch.EMPTY).copy(patch = style).smart(rules)

    private fun scaled(input: PipelineInput, k: Float) = PipelineInput(
        input.kind,
        input.width,
        input.height,
        FloatArray(input.linearRgb.size) { input.linearRgb[it] * k },
        input.cameraToXyz,
        input.asShotMultipliers,
        input.saturation,
    )

    @Test
    fun `rules round trip through the file's smart object`() {
        val all = SmartRules(
            SmartRules.ExposureRule(0.2, 0.15),
            SmartRules.ToneRule(70, 30),
            SmartRules.WhiteBalanceRule(SmartRules.WhiteBalanceRule.Mode.AS_SHOT, skinGuard = false),
            SmartRules.VibranceRule(12),
            SmartRules.StraightenRule(4.0),
        )
        assertThat(SmartRules.fromJson(all.toJson())).isEqualTo(all)
        assertThat(SmartRules.fromJson(SmartRules().toJson())).isEqualTo(SmartRules())
        // Through a whole preset file.
        val decoded = PresetFiles.decode(PresetFiles.encode(preset)).preset
        assertThat(SmartRules.fromJson(checkNotNull(decoded.smart).rules)).isEqualTo(rules)
    }

    @Test
    fun `out-of-range values are clamped, a newer version is not applied`() {
        val json = Json.parseToJsonElement(
            """{"smartVersion":1,"exposure":{"subjectBrightness":5},"tone":{"maxShadows":-3},"whiteBalance":{"mode":"later"},"future":{}}""",
        ) as JsonObject
        val r = checkNotNull(SmartRules.fromJson(json))
        assertThat(r.exposure!!.subjectBrightness).isEqualTo(SmartRules.BRIGHTNESS.endInclusive)
        assertThat(r.tone!!.maxShadows).isEqualTo(0)
        assertThat(r.whiteBalance!!.mode).isEqualTo(SmartRules.WhiteBalanceRule.Mode.AUTO)
        assertThat(r.vibrance).isNull()
        val newer = Json.parseToJsonElement("""{"smartVersion":2,"exposure":{}}""") as JsonObject
        assertThat(SmartRules.fromJson(newer)).isNull()
    }

    @Test
    fun `making a preset smart drops the style fields the correction owns`() {
        val withExposure = PresetPatch.fromJson(
            Json.parseToJsonElement(
                """{"light":{"exposure":0.5,"contrast":12},"colour":{"vibrance":30}}""",
            ) as JsonObject,
        ).patch
        val p = Preset(PresetIds.newId(), "Mine", withExposure).smart(SmartRules(exposure = SmartRules.ExposureRule()))
        assertThat(p.patch.paths).containsExactly("light.contrast", "colour.vibrance")
        assertThat(Preset(PresetIds.newId(), "Mine", withExposure).smart(SmartRules()).smart).isNull()
    }

    @Test
    fun `in a batch each photo gets its own correction and the same style`() {
        val bright = SyntheticImages.scene(240, 160)
        val dark = scaled(bright, 0.25f)
        val a = preset.apply(Recipe(), 1f, smartCorrection = SmartCorrector(PhotoFeatures.of(bright)))
        val b = preset.apply(Recipe(), 1f, smartCorrection = SmartCorrector(PhotoFeatures.of(dark)))
        // Two stops darker: about two stops more exposure, the style identical.
        assertThat(b.light.exposureEv - a.light.exposureEv).isWithin(0.15f).of(2f)
        assertThat(a.effects.clarity).isEqualTo(12f)
        assertThat(b.effects.clarity).isEqualTo(12f)
        assertThat(a.colour.saturation).isEqualTo(-10f)
        assertThat(a.effects.grainAmount).isEqualTo(20f)
        assertThat(a.colour.whiteBalance.mode).isEqualTo(WbMode.AUTO)
    }

    @Test
    fun `the correction is idempotent and the strength scales the style only`() {
        val input = SyntheticImages.scene(240, 160)
        val corrector = SmartCorrector(PhotoFeatures.of(input))
        val once = preset.apply(Recipe(), 1f, smartCorrection = corrector)
        val twice = preset.apply(once, 1f, smartCorrection = corrector)
        assertThat(twice).isEqualTo(once)
        val half = preset.apply(Recipe(), 0.5f, smartCorrection = corrector)
        assertThat(half.light.exposureEv).isEqualTo(once.light.exposureEv)
        assertThat(half.light.highlights).isEqualTo(once.light.highlights)
        assertThat(half.colour.saturation).isEqualTo(-5f)
        assertThat(half.effects.grainAmount).isEqualTo(10f)
    }

    @Test
    fun `rules that are off keep the photo's values`() {
        val input = SyntheticImages.scene(240, 160)
        val before = Recipe(light = Light(exposureEv = 0.7f, shadows = 5f))
        val out = SmartCorrector(
            PhotoFeatures.of(input),
        ).correct(SmartRules(vibrance = SmartRules.VibranceRule()), before)
        assertThat(out.light).isEqualTo(before.light)
        assertThat(out.colour.whiteBalance).isEqualTo(before.colour.whiteBalance)
    }

    @Test
    fun `the face brightness target steers the exposure of portraits`() {
        val input = FaceFixtures.input("studio-portrait")
        val features = PhotoFeatures.of(input)
        assertThat(features.faces).hasSize(1)
        fun ev(face: Double) = SmartCorrector(features).correct(
            SmartRules(exposure = SmartRules.ExposureRule(faceBrightness = face)),
            Recipe(),
        ).light.exposureEv
        // A brighter face target never gives less exposure, and a much brighter one gives more.
        assertThat(ev(0.3)).isAtLeast(ev(0.12))
        assertThat(ev(0.45)).isGreaterThan(ev(0.06))
    }

    @Test
    fun `straighten levels a tilted photo only when the lines agree`() {
        val w = 360
        val h = 240
        val gray = PerspectiveScenes.facade(w, h, tilt = 2.5)
        val rgb = FloatArray(w * h * 3) { gray[it / 3] * 0.8f }
        val input = PipelineInput.linearSrgb(w, h, rgb)
        val features = PhotoFeatures.of(input, detectFaces = false, findLines = true)
        val straight = SmartCorrector(features).correct(SmartRules(straighten = SmartRules.StraightenRule()), Recipe())
        assertThat(abs(straight.geometry.angle + 2.5f)).isLessThan(0.6f)
        // Over the rule's limit: left alone.
        val limited = SmartCorrector(
            features,
        ).correct(SmartRules(straighten = SmartRules.StraightenRule(1.0)), Recipe())
        assertThat(limited.geometry.angle).isEqualTo(0f)
        // No lines (a smooth image): left alone.
        val flat = PipelineInput.linearSrgb(w, h, FloatArray(w * h * 3) { 0.2f })
        val none = SmartCorrector(PhotoFeatures.of(flat, detectFaces = false, lines = ImageLines.of(flat)))
            .correct(SmartRules(straighten = SmartRules.StraightenRule()), Recipe())
        assertThat(none.geometry.angle).isEqualTo(0f)
    }
}
