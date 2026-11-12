@file:Suppress("MaxLineLength", "LargeClass", "MagicNumber")

package dev.hfps.reveal.pipeline.preset

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Reveal's built-in presets (docs/adr/0020, "Built-in presets"): styles designed as recipe patches with the M2 tools
 * and checked on real photos (contact sheets), grouped by style, plus the built-in smart presets. Pure data: names
 * are keys ([BuiltInPreset.key]); the app shows them through string resources (`preset_<key>`,
 * `preset_group_<group>`), so the [Preset.name] here is the English fallback only.
 *
 * Design rules: a style never sets exposure, white balance or the lens/geometry sections (they describe one photo);
 * tone is shaped by contrast, the parametric and point curves and the tone sliders by small amounts; colour by the
 * mixer and grading; skin (the orange band, OkLab hue about 55°) is never pushed more than a few units in portrait
 * looks; every preset leaves highlights unclipped on the CC0 and the owner's test photos at 100 %.
 */
object BuiltInPresets {
    /** The style groups, in panel order. */
    enum class Group(val key: String) {
        SMART("smart"),
        NATURAL("natural"),
        PORTRAIT("portrait"),
        LANDSCAPE("landscape"),
        BLACK_WHITE("bw"),
        FILM("film"),
        MATTE("matte"),
        WARM("warm"),
        COOL("cool"),
    }

    /** A built-in preset: [key] names its string resource, [preset] is the preset itself. */
    class BuiltInPreset(val key: String, val group: Group, val preset: Preset)

    /** Every built-in preset, in panel order. */
    val all: List<BuiltInPreset> by lazy { definitions.map(::build) }

    /** A built-in preset by its id, `null` when [id] is not one. */
    fun byId(id: String): BuiltInPreset? = all.firstOrNull { it.preset.id == id }

    /**
     * The stable id of the built-in [key]: a version 4 UUID made from SHA-256 of `reveal-builtin-<key>` (so the id
     * never changes between releases, and favourites and recents survive updates).
     */
    fun idOf(key: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest("reveal-builtin-$key".toByteArray()).copyOf(UUID_BYTES)
        h[VERSION_BYTE] = ((h[VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_4).toByte()
        h[VARIANT_BYTE] = ((h[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
        val msb = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and BYTE) }
        val lsb = (8 until UUID_BYTES).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and BYTE) }
        return UUID(msb, lsb).toString()
    }

    private class Definition(
        val key: String,
        val group: Group,
        val name: String,
        val patch: String,
        val smart: SmartRules? = null,
    )

    private val json = Json { isLenient = false }

    private fun build(d: Definition): BuiltInPreset {
        val patch = PresetPatch.fromJson(json.parseToJsonElement(d.patch) as JsonObject)
        check(patch.ignored.isEmpty()) { "built-in ${d.key} has ignored fields ${patch.ignored}" }
        return BuiltInPreset(
            d.key,
            d.group,
            Preset(
                id = idOf(d.key),
                name = d.name,
                patch = patch.patch,
                group = d.group.name,
                author = "Reveal",
                smart = d.smart?.let { SmartPart(it.toJson()) },
            ),
        )
    }

    // A gentle film-like base: lifted black, rolled-off white (point curve), used by several looks.
    private const val FADED = """[{"x":0,"y":0.07},{"x":0.25,"y":0.27},{"x":0.75,"y":0.76},{"x":1,"y":0.96}]"""
    private const val MATTE = """[{"x":0,"y":0.09},{"x":0.2,"y":0.22},{"x":0.5,"y":0.5},""" +
        """{"x":0.85,"y":0.84},{"x":1,"y":0.95}]"""
    private const val S_CURVE = """[{"x":0,"y":0},{"x":0.25,"y":0.22},{"x":0.75,"y":0.79},{"x":1,"y":1}]"""

    private val definitions = listOf(
        // Smart presets: the correction from each photo, then a light style.
        Definition(
            "smart_natural_portrait",
            Group.SMART,
            "Natural portrait",
            """{"profile":{"base":"portrait"},"light":{"curve":{"parametric":{"shadows":4}}},"colour":{"mixer":{"luminance":{"orange":5}}},"effects":{"texture":-10}}""",
            SmartRules(
                SmartRules.ExposureRule(faceBrightness = SmartRules.DEFAULTS.faceTarget * BRIGHT),
                SmartRules.ToneRule(maxHighlights = 60, maxShadows = 35),
                SmartRules.WhiteBalanceRule(),
                SmartRules.VibranceRule(max = 12),
            ),
        ),
        Definition(
            "smart_bright_outdoor",
            Group.SMART,
            "Bright outdoor",
            """{"profile":{"base":"landscape","amount":80},"colour":{"mixer":{"saturation":{"blue":8},"luminance":{"blue":-8}}},"effects":{"clarity":8,"dehaze":5}}""",
            SmartRules(
                SmartRules.ExposureRule(
                    subjectBrightness = SmartRules.DEFAULTS.targetGrey * 1.12,
                    faceBrightness =
                        SmartRules.DEFAULTS.faceTarget * 1.12,
                ),
                SmartRules.ToneRule(maxHighlights = 80, maxShadows = 40),
                SmartRules.WhiteBalanceRule(),
                SmartRules.VibranceRule(max = 25),
                SmartRules.StraightenRule(),
            ),
        ),
        Definition(
            "smart_moody_indoor",
            Group.SMART,
            "Moody indoor",
            """{"light":{"curve":{"parametric":{"darks":-6,"highlights":-4}}},"colour":{"saturation":-8,"grading":{"shadows":{"hue":230,"saturation":8},"highlights":{"hue":60,"saturation":10}}},"effects":{"vignetteAmount":-18,"clarity":6}}""",
            SmartRules(
                SmartRules.ExposureRule(
                    subjectBrightness = SmartRules.DEFAULTS.targetGrey * 0.8,
                    faceBrightness =
                        SmartRules.DEFAULTS.faceTarget * 0.92,
                ),
                SmartRules.ToneRule(maxHighlights = 60, maxShadows = 15),
                SmartRules.WhiteBalanceRule(),
            ),
        ),
        Definition(
            "smart_clean_auto",
            Group.SMART,
            "Clean auto",
            """{"detail":{"sharpenAmount":25}}""",
            SmartRules.AUTO.copy(straighten = SmartRules.StraightenRule()),
        ),

        // Natural.
        Definition(
            "natural_clean",
            Group.NATURAL,
            "Clean",
            """{"light":{"contrast":6,"curve":{"parametric":{"lights":3,"darks":-3}}},"colour":{"vibrance":10},"effects":{"clarity":5}}""",
        ),
        Definition(
            "natural_soft",
            Group.NATURAL,
            "Soft",
            """{"light":{"contrast":-10,"highlights":-20,"shadows":15},"colour":{"vibrance":8},"effects":{"clarity":-6,"texture":-5}}""",
        ),
        Definition(
            "natural_crisp",
            Group.NATURAL,
            "Crisp",
            """{"light":{"contrast":14,"blacks":-6,"whites":6},"colour":{"vibrance":12},"effects":{"clarity":12,"texture":10},"detail":{"sharpenAmount":35}}""",
        ),
        Definition(
            "natural_airy",
            Group.NATURAL,
            "Bright and airy",
            """{"light":{"contrast":-12,"shadows":25,"whites":12,"curve":{"parametric":{"lights":8,"darks":6}}},"colour":{"vibrance":4,"saturation":-6,"grading":{"highlights":{"hue":70,"saturation":4}}}}""",
        ),

        // Portrait.
        Definition(
            "portrait_soft_skin",
            Group.PORTRAIT,
            "Soft skin",
            """{"profile":{"base":"portrait"},"light":{"highlights":-15,"shadows":10},"colour":{"mixer":{"saturation":{"orange":-5},"luminance":{"orange":8,"red":4}}},"effects":{"texture":-20,"clarity":-8}}""",
        ),
        Definition(
            "portrait_warm_glow",
            Group.PORTRAIT,
            "Warm glow",
            """{"profile":{"base":"portrait"},"light":{"contrast":-6,"highlights":-10},"colour":{"vibrance":5,"mixer":{"luminance":{"orange":6}},"grading":{"highlights":{"hue":65,"saturation":12},"shadows":{"hue":40,"saturation":5}}},"effects":{"texture":-8}}""",
        ),
        Definition(
            "portrait_studio",
            Group.PORTRAIT,
            "Studio",
            """{"profile":{"base":"portrait"},"light":{"contrast":10,"whites":10,"blacks":-5},"colour":{"mixer":{"luminance":{"orange":4}}},"effects":{"clarity":4,"texture":-10}}""",
        ),
        Definition(
            "portrait_moody",
            Group.PORTRAIT,
            "Moody portrait",
            """{"profile":{"base":"portrait"},"light":{"contrast":8,"shadows":-6,"blacks":-8,"curve":{"parametric":{"highlights":-6}}},"colour":{"saturation":-10,"mixer":{"saturation":{"orange":-6}},"grading":{"shadows":{"hue":235,"saturation":8}}},"effects":{"vignetteAmount":-22,"texture":-6}}""",
        ),
        Definition(
            "portrait_golden",
            Group.PORTRAIT,
            "Golden",
            """{"profile":{"base":"portrait"},"light":{"contrast":4},"colour":{"vibrance":6,"mixer":{"hue":{"orange":-4},"saturation":{"yellow":8}},"grading":{"global":{"hue":70,"saturation":8},"highlights":{"hue":60,"saturation":10}}},"effects":{"texture":-6}}""",
        ),

        // Landscape.
        Definition(
            "landscape_vivid",
            Group.LANDSCAPE,
            "Vivid",
            """{"profile":{"base":"landscape"},"light":{"contrast":8},"colour":{"vibrance":18,"mixer":{"saturation":{"blue":8},"luminance":{"green":-5}}},"effects":{"clarity":14,"dehaze":8}}""",
        ),
        Definition(
            "landscape_clear_sky",
            Group.LANDSCAPE,
            "Clear sky",
            """{"profile":{"base":"landscape","amount":80},"light":{"highlights":-35,"whites":5},"colour":{"vibrance":8,"mixer":{"saturation":{"blue":10,"aqua":6},"luminance":{"blue":-16,"aqua":-10}}},"effects":{"dehaze":8}}""",
        ),
        Definition(
            "landscape_lush",
            Group.LANDSCAPE,
            "Lush green",
            """{"profile":{"base":"landscape","amount":80},"light":{"shadows":10},"colour":{"vibrance":8,"mixer":{"hue":{"green":-8,"yellow":-10},"saturation":{"green":12,"yellow":6},"luminance":{"green":5}}},"effects":{"clarity":8}}""",
        ),
        Definition(
            "landscape_golden_hour",
            Group.LANDSCAPE,
            "Golden hour",
            """{"profile":{"base":"landscape","amount":70},"light":{"highlights":-15},"colour":{"vibrance":10,"mixer":{"saturation":{"orange":8}},"grading":{"highlights":{"hue":65,"saturation":18},"shadows":{"hue":250,"saturation":8}}},"effects":{"clarity":6}}""",
        ),
        Definition(
            "landscape_misty",
            Group.LANDSCAPE,
            "Misty",
            """{"light":{"contrast":-14,"curve":{"rgb":$FADED}},"colour":{"saturation":-10,"grading":{"highlights":{"hue":220,"saturation":5}}},"effects":{"dehaze":-12,"clarity":-6}}""",
        ),

        // Black and white.
        Definition(
            "bw_classic",
            Group.BLACK_WHITE,
            "Classic",
            """{"profile":{"treatment":"bw"},"light":{"contrast":14},"colour":{"greyMixer":{"red":5,"orange":8,"blue":-8}}}""",
        ),
        Definition(
            "bw_high_contrast",
            Group.BLACK_WHITE,
            "High contrast",
            """{"profile":{"treatment":"bw"},"light":{"contrast":32,"whites":12,"blacks":-18,"curve":{"rgb":$S_CURVE}},"colour":{"greyMixer":{"red":10,"orange":10,"blue":-20}},"effects":{"clarity":15}}""",
        ),
        Definition(
            "bw_soft_matte",
            Group.BLACK_WHITE,
            "Soft matte",
            """{"profile":{"treatment":"bw"},"light":{"contrast":-8,"curve":{"rgb":$MATTE}},"colour":{"greyMixer":{"orange":12}},"effects":{"grainAmount":15,"clarity":-4}}""",
        ),
        Definition(
            "bw_dramatic_sky",
            Group.BLACK_WHITE,
            "Dramatic sky",
            """{"profile":{"treatment":"bw"},"light":{"contrast":20,"highlights":-20},"colour":{"greyMixer":{"orange":10,"yellow":6,"aqua":-25,"blue":-40}},"effects":{"clarity":12,"vignetteAmount":-12}}""",
        ),
        Definition(
            "bw_selenium",
            Group.BLACK_WHITE,
            "Selenium",
            """{"profile":{"treatment":"bw"},"light":{"contrast":12},"colour":{"greyMixer":{"orange":8},"grading":{"shadows":{"hue":300,"saturation":10},"highlights":{"hue":80,"saturation":4}}}}""",
        ),
        Definition(
            "bw_sepia",
            Group.BLACK_WHITE,
            "Sepia",
            """{"profile":{"treatment":"bw"},"light":{"contrast":6,"curve":{"rgb":$FADED}},"colour":{"greyMixer":{"orange":10},"grading":{"global":{"hue":70,"saturation":18}}},"effects":{"grainAmount":12}}""",
        ),

        // Film-like.
        Definition(
            "film_warm",
            Group.FILM,
            "Warm film",
            """{"light":{"contrast":-4,"curve":{"rgb":$FADED}},"colour":{"saturation":-6,"mixer":{"hue":{"green":10},"saturation":{"green":-12,"blue":-8}},"grading":{"shadows":{"hue":215,"saturation":8},"highlights":{"hue":70,"saturation":14}}},"effects":{"grainAmount":18,"grainSize":30}}""",
        ),
        Definition(
            "film_cool",
            Group.FILM,
            "Cool film",
            """{"light":{"curve":{"rgb":$S_CURVE,"parametric":{"darks":4}}},"colour":{"saturation":-4,"mixer":{"hue":{"green":14,"red":4},"saturation":{"red":-6,"orange":-4}},"grading":{"shadows":{"hue":185,"saturation":10},"highlights":{"hue":95,"saturation":6}}},"effects":{"grainAmount":15}}""",
        ),
        Definition(
            "film_faded",
            Group.FILM,
            "Faded",
            """{"light":{"contrast":-10,"curve":{"rgb":[{"x":0,"y":0.1},{"x":0.3,"y":0.31},{"x":0.7,"y":0.7},{"x":1,"y":0.92}]}},"colour":{"saturation":-15,"grading":{"shadows":{"hue":230,"saturation":6}}},"effects":{"grainAmount":10}}""",
        ),
        Definition(
            "film_cinematic",
            Group.FILM,
            "Cinematic",
            """{"light":{"contrast":10,"curve":{"parametric":{"highlights":-8}}},"colour":{"vibrance":-5,"mixer":{"saturation":{"orange":5},"hue":{"aqua":-6,"blue":-6}},"grading":{"shadows":{"hue":200,"saturation":20},"highlights":{"hue":60,"saturation":16},"balance":-10}},"effects":{"vignetteAmount":-10}}""",
        ),
        Definition(
            "film_vintage",
            Group.FILM,
            "Vintage",
            """{"light":{"contrast":-6,"curve":{"rgb":$MATTE}},"colour":{"saturation":-20,"grading":{"global":{"hue":75,"saturation":10},"shadows":{"hue":40,"saturation":6}}},"effects":{"vignetteAmount":-15,"grainAmount":28,"grainSize":35}}""",
        ),

        // Matte.
        Definition(
            "matte_soft",
            Group.MATTE,
            "Soft matte",
            """{"light":{"contrast":-5,"curve":{"rgb":$MATTE}},"colour":{"saturation":-8}}""",
        ),
        Definition(
            "matte_warm",
            Group.MATTE,
            "Warm matte",
            """{"light":{"contrast":-5,"curve":{"rgb":$MATTE}},"colour":{"saturation":-6,"grading":{"global":{"hue":65,"saturation":8}}}}""",
        ),
        Definition(
            "matte_cool",
            Group.MATTE,
            "Cool matte",
            """{"light":{"contrast":-5,"curve":{"rgb":$MATTE}},"colour":{"saturation":-8,"grading":{"global":{"hue":230,"saturation":7}}}}""",
        ),

        // Warm and cool.
        Definition(
            "warm_sunlit",
            Group.WARM,
            "Sunlit",
            """{"light":{"shadows":8},"colour":{"vibrance":8,"grading":{"highlights":{"hue":70,"saturation":14},"midtones":{"hue":60,"saturation":5}}}}""",
        ),
        Definition(
            "warm_cosy",
            Group.WARM,
            "Cosy",
            """{"light":{"contrast":5,"curve":{"parametric":{"darks":-4}}},"colour":{"grading":{"global":{"hue":55,"saturation":10},"shadows":{"hue":35,"saturation":8}}},"effects":{"vignetteAmount":-10}}""",
        ),
        Definition(
            "cool_crisp",
            Group.COOL,
            "Crisp blue",
            """{"light":{"contrast":6},"colour":{"grading":{"global":{"hue":240,"saturation":8},"highlights":{"hue":220,"saturation":5}}},"effects":{"clarity":8}}""",
        ),
        Definition(
            "cool_nordic",
            Group.COOL,
            "Nordic",
            """{"light":{"contrast":4,"curve":{"rgb":$FADED}},"colour":{"saturation":-15,"mixer":{"saturation":{"blue":-10,"orange":-6}},"grading":{"shadows":{"hue":235,"saturation":12},"highlights":{"hue":210,"saturation":4}}}}""",
        ),
    )

    /** The owner's taste ("bright, natural"): the natural portrait's faces a little brighter than the camera's. */
    private const val BRIGHT = 1.07
    private const val UUID_BYTES = 16
    private const val VERSION_BYTE = 6
    private const val VARIANT_BYTE = 8
    private const val LOW_NIBBLE = 0x0F
    private const val VERSION_4 = 0x40
    private const val VARIANT_MASK = 0x3F
    private const val VARIANT_RFC = 0x80
    private const val BYTE = 0xFFL
}
