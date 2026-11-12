package dev.hfps.reveal.pipeline.preset

import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.ControlRange
import dev.hfps.reveal.pipeline.recipe.GradeRegion
import dev.hfps.reveal.pipeline.recipe.MixerProperty
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * How [PresetStrength] scales one field between the photo's value (strength 0 %) and the preset's (100 %), and past it
 * up to 200 % (docs/adr/0019, "Strength").
 */
enum class FieldKind {
    /** An additive slider (exposure, contrast, HSL bands, …): `photo + s · (preset − photo)`, clamped to its range. */
    LINEAR,

    /** A multiplicative value (sharpening radius, transform scale): interpolated in log space, clamped. */
    RATIO,

    /** Absolute temperature in kelvin (nullable = as shot): interpolated in mired (1e6 / K), clamped to its range. */
    KELVIN,

    /** Absolute tint (nullable = as shot): linear, as shot resolved through [ApplyContext]. */
    TINT,

    /** A colour grading wheel's hue: blended with its saturation as one 2D vector (or along the shortest arc). */
    GRADE_HUE,

    /** A colour grading wheel's saturation: see [GRADE_HUE]. */
    GRADE_SATURATION,

    /** A point curve (a list of points): both curves sampled at the same x, y blended, kept monotone in x. */
    CURVE,

    /** Cannot scale (look, treatment, a mode, a switch): the preset's value from 50 % strength, the photo's below. */
    SWITCH,
}

/**
 * The parts of a recipe a preset can set, as the "Create preset" chooser lists them (docs/adr/0019, "Sections"). Each
 * section owns the [PresetField]s whose path starts with one of its [prefixes].
 */
enum class PresetSection(val prefixes: List<String>) {
    PROFILE(listOf("profile.base", "profile.amount")),
    TREATMENT(listOf("profile.treatment", "colour.greyMixer")),
    WHITE_BALANCE(listOf("colour.whiteBalance")),
    EXPOSURE(listOf("light.exposure")),
    CONTRAST(listOf("light.contrast")),
    HIGHLIGHTS(listOf("light.highlights")),
    SHADOWS(listOf("light.shadows")),
    WHITES(listOf("light.whites")),
    BLACKS(listOf("light.blacks")),
    TONE_CURVE(listOf("light.curve")),
    VIBRANCE(listOf("colour.vibrance")),
    SATURATION(listOf("colour.saturation")),
    COLOUR_MIXER(listOf("colour.mixer")),
    COLOUR_GRADING(listOf("colour.grading")),
    TEXTURE(listOf("effects.texture")),
    CLARITY(listOf("effects.clarity")),
    DEHAZE(listOf("effects.dehaze")),
    VIGNETTE(listOf("effects.vignette")),
    GRAIN(listOf("effects.grain")),
    SHARPENING(listOf("detail.sharpen")),
    NOISE_REDUCTION(listOf("detail.nr")),
    LENS_CORRECTIONS(listOf("optics.lensCorrections", "optics.distortion", "optics.vignetting")),
    CHROMATIC_ABERRATION(listOf("optics.removeCa")),
    DEFRINGE(listOf("optics.purple", "optics.green")),
    TRANSFORM(listOf("geometry.transform")),
    ;

    /** True when [path] belongs to this section. */
    fun owns(path: String): Boolean = prefixes.any { path == it || path.startsWith(it) }

    companion object {
        /**
         * What "Create preset" offers checked: the style of an edit, not what belongs to one photo (white balance,
         * exposure, the lens and its perspective).
         */
        val PHOTO_SPECIFIC: Set<PresetSection> =
            setOf(WHITE_BALANCE, EXPOSURE, LENS_CORRECTIONS, CHROMATIC_ABERRATION, DEFRINGE, TRANSFORM)

        /** The section of [path] (every [PresetField] has one). */
        fun of(path: String): PresetSection = entries.first { it.owns(path) }

        /** The sections in which [recipe] differs from the default recipe (what an edit changed). */
        fun changedIn(recipe: Recipe): Set<PresetSection> {
            val json = JsonObjectParser.parse(RecipeCodec.Default.encode(recipe))
            return PresetFields.all.filter { field ->
                JsonTree.get(json, field.segments) != JsonTree.get(PresetFields.defaultJson, field.segments)
            }.mapTo(LinkedHashSet()) { it.section }
        }

        /** What "Create preset" offers ticked for [recipe]: the changed sections that describe a style. */
        fun suggestedFor(recipe: Recipe): Set<PresetSection> = changedIn(recipe) - PHOTO_SPECIFIC
    }
}

/**
 * One settable unit of a preset: a leaf of the recipe JSON (ADR 0003) by its dotted [path] (`light.exposure`,
 * `colour.mixer.hue.red`, `light.curve.rgb`), the [kind] that says how strength scales it, the control [range] it is
 * clamped to (null for non-scalars), and its chooser [section].
 */
data class PresetField(val path: String, val kind: FieldKind, val range: ControlRange?) {
    val section: PresetSection get() = PresetSection.of(path)
    val segments: List<String> get() = path.split('.')
}

/**
 * The registry of [PresetField]s, derived from the default recipe's JSON so a field added to the recipe later is
 * presettable without touching this file (a number scales linearly, anything else switches). Not presettable: the
 * schema version, masks and heals (in image coordinates of one photo), the crop, straighten angle, quarter turns,
 * flips, aspect and its constraint, Upright's mode and guides (they describe one frame), and the automatic CA
 * estimate (measured on one photo).
 */
object PresetFields {
    /** Paths (or path prefixes) a preset never sets; a file that sets them has those values ignored. */
    val EXCLUDED: List<String> = listOf(
        "v",
        "masks",
        "heals",
        "optics.caRed",
        "optics.caBlue",
        "geometry.crop",
        "geometry.angle",
        "geometry.rotate",
        "geometry.flipH",
        "geometry.flipV",
        "geometry.aspect",
        "geometry.constrain",
        "geometry.transform.upright",
        "geometry.transform.guides",
    )

    private val kinds: Map<String, FieldKind> = buildMap {
        put("colour.whiteBalance.temperature", FieldKind.KELVIN)
        put("colour.whiteBalance.tint", FieldKind.TINT)
        put("detail.sharpenRadius", FieldKind.RATIO)
        put("geometry.transform.scale", FieldKind.RATIO)
        for (channel in listOf("rgb", "red", "green", "blue")) put("light.curve.$channel", FieldKind.CURVE)
        for (wheel in listOf("shadows", "midtones", "highlights", "global")) {
            put("colour.grading.$wheel.hue", FieldKind.GRADE_HUE)
            put("colour.grading.$wheel.saturation", FieldKind.GRADE_SATURATION)
        }
    }

    /** The control behind each scalar path (its range clamps a scaled value). */
    private val controls: Map<String, ControlId> = buildMap {
        put("profile.amount", ControlId.PROFILE_AMOUNT)
        put("light.exposure", ControlId.EXPOSURE)
        put("light.contrast", ControlId.CONTRAST)
        put("light.highlights", ControlId.HIGHLIGHTS)
        put("light.shadows", ControlId.SHADOWS)
        put("light.whites", ControlId.WHITES)
        put("light.blacks", ControlId.BLACKS)
        put("colour.whiteBalance.temperature", ControlId.TEMPERATURE)
        put("colour.whiteBalance.tint", ControlId.TINT)
        put("colour.whiteBalance.relTemperature", ControlId.REL_TEMPERATURE)
        put("colour.whiteBalance.relTint", ControlId.REL_TINT)
        put("colour.vibrance", ControlId.VIBRANCE)
        put("colour.saturation", ControlId.SATURATION)
        put("detail.sharpenAmount", ControlId.SHARPEN_AMOUNT)
        put("detail.sharpenRadius", ControlId.SHARPEN_RADIUS)
        put("detail.sharpenDetail", ControlId.SHARPEN_DETAIL)
        put("detail.sharpenMasking", ControlId.SHARPEN_MASKING)
        put("detail.nrLuminance", ControlId.NR_LUMINANCE)
        put("detail.nrLuminanceDetail", ControlId.NR_LUMINANCE_DETAIL)
        put("detail.nrLuminanceContrast", ControlId.NR_LUMINANCE_CONTRAST)
        put("detail.nrColour", ControlId.NR_COLOUR)
        put("detail.nrColourDetail", ControlId.NR_COLOUR_DETAIL)
        put("detail.nrColourSmoothness", ControlId.NR_COLOUR_SMOOTHNESS)
        put("effects.texture", ControlId.TEXTURE)
        put("effects.clarity", ControlId.CLARITY)
        put("effects.dehaze", ControlId.DEHAZE)
        put("effects.vignetteAmount", ControlId.VIGNETTE_AMOUNT)
        put("effects.vignetteMidpoint", ControlId.VIGNETTE_MIDPOINT)
        put("effects.vignetteRoundness", ControlId.VIGNETTE_ROUNDNESS)
        put("effects.vignetteFeather", ControlId.VIGNETTE_FEATHER)
        put("effects.vignetteHighlights", ControlId.VIGNETTE_HIGHLIGHTS)
        put("effects.grainAmount", ControlId.GRAIN_AMOUNT)
        put("effects.grainSize", ControlId.GRAIN_SIZE)
        put("effects.grainRoughness", ControlId.GRAIN_ROUGHNESS)
        put("optics.distortion", ControlId.LENS_DISTORTION)
        put("optics.vignetting", ControlId.LENS_VIGNETTING)
        put("optics.purpleAmount", ControlId.DEFRINGE_PURPLE_AMOUNT)
        put("optics.purpleHueLow", ControlId.DEFRINGE_PURPLE_HUE_LOW)
        put("optics.purpleHueHigh", ControlId.DEFRINGE_PURPLE_HUE_HIGH)
        put("optics.greenAmount", ControlId.DEFRINGE_GREEN_AMOUNT)
        put("optics.greenHueLow", ControlId.DEFRINGE_GREEN_HUE_LOW)
        put("optics.greenHueHigh", ControlId.DEFRINGE_GREEN_HUE_HIGH)
        put("geometry.transform.vertical", ControlId.TRANSFORM_VERTICAL)
        put("geometry.transform.horizontal", ControlId.TRANSFORM_HORIZONTAL)
        put("geometry.transform.rotate", ControlId.TRANSFORM_ROTATE)
        put("geometry.transform.aspect", ControlId.TRANSFORM_ASPECT)
        put("geometry.transform.scale", ControlId.TRANSFORM_SCALE)
        put("geometry.transform.offsetX", ControlId.TRANSFORM_OFFSET_X)
        put("geometry.transform.offsetY", ControlId.TRANSFORM_OFFSET_Y)
        put("light.curve.parametric.shadows", ControlId.CURVE_SHADOWS)
        put("light.curve.parametric.darks", ControlId.CURVE_DARKS)
        put("light.curve.parametric.lights", ControlId.CURVE_LIGHTS)
        put("light.curve.parametric.highlights", ControlId.CURVE_HIGHLIGHTS)
        put("light.curve.parametric.splitShadows", ControlId.CURVE_SPLIT_SHADOWS)
        put("light.curve.parametric.splitMidtones", ControlId.CURVE_SPLIT_MIDTONES)
        put("light.curve.parametric.splitHighlights", ControlId.CURVE_SPLIT_HIGHLIGHTS)
        for (band in ColourBand.entries) {
            val key = band.name.lowercase()
            put("colour.mixer.hue.$key", ControlId.mixer(MixerProperty.HUE, band))
            put("colour.mixer.saturation.$key", ControlId.mixer(MixerProperty.SATURATION, band))
            put("colour.mixer.luminance.$key", ControlId.mixer(MixerProperty.LUMINANCE, band))
            put("colour.greyMixer.$key", ControlId.grey(band))
        }
        for (region in GradeRegion.entries) {
            val key = region.name.lowercase()
            put("colour.grading.$key.hue", ControlId.grade(region, MixerProperty.HUE))
            put("colour.grading.$key.saturation", ControlId.grade(region, MixerProperty.SATURATION))
            put("colour.grading.$key.luminance", ControlId.grade(region, MixerProperty.LUMINANCE))
        }
        put("colour.grading.blending", ControlId.GRADE_BLENDING)
        put("colour.grading.balance", ControlId.GRADE_BALANCE)
    }

    /** The default recipe as JSON: the shape every patch is checked against. */
    internal val defaultJson: JsonObject =
        JsonObjectParser.parse(RecipeCodec.Default.encode(Recipe.DEFAULT))

    /** Every presettable field in recipe JSON order. */
    val all: List<PresetField> = buildList { collect(defaultJson, "", this) }

    private val byPath: Map<String, PresetField> = all.associateBy { it.path }

    /** The field at [path], or null when it is unknown or not presettable. */
    operator fun get(path: String): PresetField? = byPath[path]

    /** The fields of [sections]. */
    fun of(sections: Collection<PresetSection>): Set<String> =
        all.filter { it.section in sections }.mapTo(LinkedHashSet()) { it.path }

    /** True when [path] (or a parent of it) is never presettable. */
    fun isExcluded(path: String): Boolean = EXCLUDED.any { path == it || path.startsWith("$it.") }

    private fun collect(node: JsonObject, prefix: String, out: MutableList<PresetField>) {
        for ((key, value) in node) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (isExcluded(path)) continue
            if (value is JsonObject) {
                collect(value, path, out)
            } else {
                out += PresetField(path, kindOf(path, value), controls[path]?.let { RecipeRanges[it] })
            }
        }
    }

    private fun kindOf(path: String, value: JsonElement): FieldKind = kinds[path] ?: when {
        value is JsonArray || value is JsonNull -> FieldKind.SWITCH
        value is JsonPrimitive && (value.isString || value.booleanOrNull != null) -> FieldKind.SWITCH
        else -> FieldKind.LINEAR
    }
}

/** A tiny shared JSON parser for registry/bootstrap use. */
internal object JsonObjectParser {
    private val json = kotlinx.serialization.json.Json

    fun parse(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject
}
