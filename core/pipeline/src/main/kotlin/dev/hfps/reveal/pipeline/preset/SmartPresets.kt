@file:Suppress("MagicNumber", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")

package dev.hfps.reveal.pipeline.preset

import dev.hfps.reveal.pipeline.auto.AutoAnalyser
import dev.hfps.reveal.pipeline.auto.AutoParams
import dev.hfps.reveal.pipeline.auto.AutoRules
import dev.hfps.reveal.pipeline.auto.PhotoFeatures
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.geometry.Upright
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import kotlin.math.abs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The correction part of a smart preset (docs/adr/0020): which of Auto's rules run on each photo and with which
 * targets. A `null` rule does not run (the photo's own values stay). Stored as the preset file's `smart` object
 * ([toJson] / [fromJson]); unknown members are ignored and numbers are clamped, so a file from a newer app still
 * applies what this one understands.
 *
 * @property exposure exposure from the key and the faces: [ExposureRule.subjectBrightness] is
 *   [AutoParams.targetGrey], [ExposureRule.faceBrightness] [AutoParams.faceTarget].
 * @property tone highlights, shadows, contrast, whites and blacks, with the largest highlight pull and shadow lift.
 * @property whiteBalance the Auto white balance (with the faces' skin guard), or back to as shot.
 * @property vibrance vibrance towards the target chroma, at most [VibranceRule.max].
 * @property straighten levels the horizon / plumbs the verticals when the lines agree, at most
 *   [StraightenRule.maxAngle] degrees.
 */
data class SmartRules(
    val exposure: ExposureRule? = null,
    val tone: ToneRule? = null,
    val whiteBalance: WhiteBalanceRule? = null,
    val vibrance: VibranceRule? = null,
    val straighten: StraightenRule? = null,
) {
    data class ExposureRule(
        val subjectBrightness: Double = DEFAULTS.targetGrey,
        val faceBrightness: Double = DEFAULTS.faceTarget,
    ) {
        init {
            require(subjectBrightness in BRIGHTNESS && faceBrightness in BRIGHTNESS) { "brightness out of range" }
        }
    }

    data class ToneRule(val maxHighlights: Int = DEFAULTS.maxHighlights, val maxShadows: Int = DEFAULTS.maxShadows) {
        init {
            require(maxHighlights in 0..100 && maxShadows in 0..100) { "tone limits are 0..100" }
        }
    }

    /** [mode] AUTO: Auto WB; [skinGuard] uses the faces (ADR 0010); AS_SHOT: the camera's. */
    data class WhiteBalanceRule(val mode: Mode = Mode.AUTO, val skinGuard: Boolean = true) {
        enum class Mode(val serial: String) {
            AUTO("auto"),
            AS_SHOT("as_shot"),
        }
    }

    data class VibranceRule(val max: Int = DEFAULTS.maxVibrance) {
        init {
            require(max in 0..100) { "vibrance limit is 0..100" }
        }
    }

    data class StraightenRule(val maxAngle: Double = DEFAULT_MAX_ANGLE) {
        init {
            require(maxAngle in 0.0..MAX_ANGLE) { "straighten limit is 0..$MAX_ANGLE degrees" }
        }
    }

    /** The corrections a user can switch on for a preset ("Smart corrections", docs/adr/0020). */
    enum class Kind { EXPOSURE, TONE, WHITE_BALANCE, VIBRANCE, STRAIGHTEN }

    /** The rules that run. */
    val kinds: Set<Kind>
        get() = buildSet {
            if (exposure != null) add(Kind.EXPOSURE)
            if (tone != null) add(Kind.TONE)
            if (whiteBalance != null) add(Kind.WHITE_BALANCE)
            if (vibrance != null) add(Kind.VIBRANCE)
            if (straighten != null) add(Kind.STRAIGHTEN)
        }

    /** These rules with exactly [kinds] running: a kept rule keeps its targets, a new one starts at Auto's. */
    fun withKinds(kinds: Set<Kind>): SmartRules = SmartRules(
        exposure = if (Kind.EXPOSURE in kinds) exposure ?: ExposureRule() else null,
        tone = if (Kind.TONE in kinds) tone ?: ToneRule() else null,
        whiteBalance = if (Kind.WHITE_BALANCE in kinds) whiteBalance ?: WhiteBalanceRule() else null,
        vibrance = if (Kind.VIBRANCE in kinds) vibrance ?: VibranceRule() else null,
        straighten = if (Kind.STRAIGHTEN in kinds) straighten ?: StraightenRule() else null,
    )

    /** True when no rule runs (the preset then only has its style). */
    val isEmpty: Boolean get() = exposure == null && tone == null && whiteBalance == null && vibrance == null &&
        straighten == null

    /** The preset sections the correction sets: a style patch should not set them too (it would override them). */
    val sections: Set<PresetSection>
        get() = buildSet {
            if (exposure != null) add(PresetSection.EXPOSURE)
            if (tone != null) addAll(TONE_SECTIONS)
            if (whiteBalance != null) add(PresetSection.WHITE_BALANCE)
            if (vibrance != null) add(PresetSection.VIBRANCE)
        }

    /** Auto's parameters with this preset's targets. */
    fun params(): AutoParams = DEFAULTS.copy(
        targetGrey = exposure?.subjectBrightness ?: DEFAULTS.targetGrey,
        faceTarget = exposure?.faceBrightness ?: DEFAULTS.faceTarget,
        maxHighlights = tone?.maxHighlights ?: DEFAULTS.maxHighlights,
        maxShadows = tone?.maxShadows ?: DEFAULTS.maxShadows,
        maxVibrance = vibrance?.max ?: DEFAULTS.maxVibrance,
    )

    /** The `smart` member of a `.revealpreset` file. */
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("smartVersion", JsonPrimitive(VERSION))
            exposure?.let {
                put(
                    "exposure",
                    obj(
                        "subjectBrightness" to JsonPrimitive(it.subjectBrightness),
                        "faceBrightness" to JsonPrimitive(it.faceBrightness),
                    ),
                )
            }
            tone?.let {
                put(
                    "tone",
                    obj(
                        "maxHighlights" to JsonPrimitive(it.maxHighlights),
                        "maxShadows" to JsonPrimitive(it.maxShadows),
                    ),
                )
            }
            whiteBalance?.let {
                put(
                    "whiteBalance",
                    obj(
                        "mode" to JsonPrimitive(it.mode.serial),
                        "skinGuard" to JsonPrimitive(it.skinGuard),
                    ),
                )
            }
            vibrance?.let { put("vibrance", obj("max" to JsonPrimitive(it.max))) }
            straighten?.let { put("straighten", obj("maxAngle" to JsonPrimitive(it.maxAngle))) }
        },
    )

    companion object {
        /** Version of the `smart` object this app writes (and the newest it reads). */
        const val VERSION: Int = 1

        /** The default (Auto's) parameters. */
        val DEFAULTS: AutoParams = AutoParams()

        const val DEFAULT_MAX_ANGLE: Double = 5.0
        const val MAX_ANGLE: Double = 15.0
        val BRIGHTNESS: ClosedFloatingPointRange<Double> = 0.03..0.6
        val TONE_SECTIONS: Set<PresetSection> = setOf(
            PresetSection.CONTRAST,
            PresetSection.HIGHLIGHTS,
            PresetSection.SHADOWS,
            PresetSection.WHITES,
            PresetSection.BLACKS,
        )

        /** Every rule with Auto's own targets (straighten off): "Auto as a preset". */
        val AUTO: SmartRules = SmartRules(ExposureRule(), ToneRule(), WhiteBalanceRule(), VibranceRule())

        /**
         * The rules of a file's `smart` object; `null` when it is not one this app understands (a newer
         * `smartVersion`): the preset then applies its style only. Values are clamped to their ranges.
         */
        fun fromJson(json: JsonObject): SmartRules? {
            val version = (json["smartVersion"] as? JsonPrimitive)?.intOrNull ?: return null
            if (version !in 1..VERSION) return null
            fun section(name: String) = json[name] as? JsonObject
            fun JsonObject.num(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
            fun JsonObject.bool(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull
            return SmartRules(
                exposure = section("exposure")?.let {
                    ExposureRule(
                        (it.num("subjectBrightness") ?: DEFAULTS.targetGrey).coerceIn(BRIGHTNESS),
                        (it.num("faceBrightness") ?: DEFAULTS.faceTarget).coerceIn(BRIGHTNESS),
                    )
                },
                tone = section("tone")?.let {
                    ToneRule(
                        (it.num("maxHighlights") ?: DEFAULTS.maxHighlights.toDouble()).toInt().coerceIn(0, 100),
                        (it.num("maxShadows") ?: DEFAULTS.maxShadows.toDouble()).toInt().coerceIn(0, 100),
                    )
                },
                whiteBalance = section("whiteBalance")?.let { wb ->
                    val mode = (wb["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    WhiteBalanceRule(
                        WhiteBalanceRule.Mode.entries.firstOrNull { it.serial == mode } ?: WhiteBalanceRule.Mode.AUTO,
                        wb.bool("skinGuard") ?: true,
                    )
                },
                vibrance = section("vibrance")?.let {
                    VibranceRule((it.num("max") ?: DEFAULTS.maxVibrance.toDouble()).toInt().coerceIn(0, 100))
                },
                straighten = section("straighten")?.let {
                    StraightenRule((it.num("maxAngle") ?: DEFAULT_MAX_ANGLE).coerceIn(0.0, MAX_ANGLE))
                },
            )
        }

        private fun obj(vararg members: Pair<String, JsonElement>) = JsonObject(mapOf(*members))
    }
}

/**
 * Smart presets on one photo (docs/adr/0020): a [SmartCorrection] made with the photo's [features] (the editor's, or
 * a batch's per photo). The correction is Auto's rules ([AutoRules]) with the preset's targets, analysed with the
 * **style's look** (its profile, so a B&W or film look is exposed through its own curve) but written into the photo's
 * recipe as plain slider values; the preset's style patch is then applied on top with the strength. Deterministic and
 * idempotent: the analysis never reads the values it sets.
 */
class SmartCorrector(private val features: PhotoFeatures) : SmartCorrection {
    override fun correct(preset: Preset, smart: SmartPart, before: Recipe): Recipe {
        val rules = SmartRules.fromJson(smart.rules) ?: return before
        val key = Triple(rules, before, preset.patch)
        synchronized(this) { last?.let { (k, v) -> if (k == key) return v } }
        // The strength slider re-applies the preset per frame; the correction does not depend on it: memoised.
        return correct(rules, before, preset.patch).also { synchronized(this) { last = key to it } }
    }

    private var last: Pair<Triple<SmartRules, Recipe, PresetPatch>, Recipe>? = null

    /** [before] with [rules] applied; [style] only lends its look to the analysis. */
    fun correct(rules: SmartRules, before: Recipe, style: PresetPatch = PresetPatch.EMPTY): Recipe {
        if (rules.isEmpty) return before
        var out = before
        rules.whiteBalance?.let { rule ->
            out = when (rule.mode) {
                SmartRules.WhiteBalanceRule.Mode.AS_SHOT ->
                    out.copy(colour = out.colour.copy(whiteBalance = WhiteBalance()))

                SmartRules.WhiteBalanceRule.Mode.AUTO -> features.wb?.let { wb ->
                    val faces = if (rule.skinGuard) features.faces else emptyList()
                    out.copy(colour = out.colour.copy(whiteBalance = AutoRules.whiteBalance(wb, faces)))
                } ?: out
            }
        }
        val light = rules.exposure != null || rules.tone != null || rules.vibrance != null
        if (light) {
            // The look the photo will have: the style's profile (and white balance when the correction leaves it).
            val styled = style.applyFull(out)
            val look = out.copy(
                profile = styled.profile,
                colour = out.colour.copy(
                    whiteBalance = if (rules.whiteBalance !=
                        null
                    ) {
                        out.colour.whiteBalance
                    } else {
                        styled.colour.whiteBalance
                    },
                ),
            )
            val analysis = AutoAnalyser.analyse(features.sample, look, features.faces, features.saliency)
            val r = AutoRules.compute(analysis, rules.params())
            var l = out.light
            if (rules.exposure != null) l = l.copy(exposureEv = r.exposureEv)
            if (rules.tone != null) {
                l =
                    l.copy(
                        contrast = r.contrast,
                        highlights = r.highlights,
                        shadows = r.shadows,
                        whites = r.whites,
                        blacks = r.blacks,
                    )
            }
            out = out.copy(light = l)
            if (rules.vibrance != null) out = out.copy(colour = out.colour.copy(vibrance = r.vibrance))
        }
        rules.straighten?.let { rule -> straighten(out, rule)?.let { out = it } }
        return out
    }

    /** [recipe] straightened when the lines agree clearly and the angle is within the rule's limit; else `null`. */
    private fun straighten(recipe: Recipe, rule: SmartRules.StraightenRule): Recipe? {
        val lines = features.lines ?: return null
        val g = recipe.geometry
        val est = Upright.straightenEstimate(lines.normalised(features.lens, recipe.optics), g.transform) ?: return null
        if (est.support < MIN_SUPPORT || est.length < MIN_LENGTH) return null
        val angle = est.angle
        if (abs(angle) > rule.maxAngle || abs(angle) < MIN_ANGLE) return null
        return recipe.copy(geometry = CropEditing.straighten(g, angle.toFloat(), lines.width, lines.height))
    }

    private companion object {
        /** Share of the axis-aligned line length that must agree on the angle. */
        const val MIN_SUPPORT = 0.5

        /** Agreeing line length (normalised to the half diagonal): about two long edges. */
        const val MIN_LENGTH = 0.5

        /** Below this the photo is level already (and a 0.0x° change would be noise). */
        const val MIN_ANGLE = 0.1
    }
}

/** This preset's rules (`null` when it is not smart, or its `smart` object is from a newer app). */
val Preset.smartRules: SmartRules? get() = smart?.let { SmartRules.fromJson(it.rules) }

/** A smart preset made from [preset]: its [rules] run first, its style without the fields they own. */
fun Preset.smart(rules: SmartRules): Preset {
    val owned = rules.sections
    val kept = patch.values.filterKeys { PresetSection.of(it) !in owned }
    return copy(
        patch = kept.entries.fold(JsonObject(emptyMap())) { root, (path, value) ->
            JsonTree.set(root, path.split('.'), value)
        }.let { PresetPatch.fromJson(it).patch },
        smart = if (rules.isEmpty) null else SmartPart(rules.toJson()),
    )
}
