// A conversion table: one entry per Camera Raw field, its constants are Camera Raw's own ranges and defaults.
@file:Suppress("MagicNumber", "TooManyFunctions", "LongParameterList", "ReturnCount")

package dev.hfps.reveal.data.xmp

import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.GradeRegion
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.UprightMode
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the Camera Raw mapping needs to know about the photo: RAW or not (white balance is absolute kelvin for a RAW,
 * relative -100..+100 otherwise; Lightroom's sharpening and colour noise defaults differ), the EXIF [orientation]
 * (1..8) and the stored pixel size (Camera Raw's crop lives in the stored, un-oriented image).
 */
data class PhotoFrame(val raw: Boolean, val orientation: Int = 1, val width: Int? = null, val height: Int? = null) {
    private val swaps: Boolean get() = orientation in 5..8

    /** The upright (oriented) size, or null when the header was not read yet. */
    val uprightWidth: Int? get() = if (swaps) height else width
    val uprightHeight: Int? get() = if (swaps) width else height
}

/**
 * The overlap between a Reveal recipe and Adobe Camera Raw's XMP settings (`crs:`, process version 2012), ADR 0026
 * "Camera Raw". Reveal's controls were modelled on Lightroom's, so most fields are the same slider with the same range;
 * the pixels still differ (Reveal's operators are its own), so every value is **approximate**: the same intent, not
 * the same rendering. Values are kept as Camera Raw's text (`"+0.70"`, `"25"`, `"True"`); comparisons go through
 * [normalise] so `"25"` and `"+25.0"` are equal.
 *
 * - [values]: what Reveal writes for a recipe (every mapped field, so another app sees Reveal's state, not its own
 *   defaults), plus the crop ([crop]) and [orientation].
 * - [apply]: a recipe with some fields taken from a sidecar, clamped to Reveal's ranges; a field that does not parse is
 *   skipped, never an error.
 */
object CameraRaw {
    /** One mapped Camera Raw property. */
    class Field internal constructor(
        val name: String,
        /** Fields imported together: when one differs, the whole group comes in (an amount and its shaping sliders). */
        val group: String,
        internal val kind: Kind,
        /** Camera Raw's own value for an untouched photo (null = not written by default). */
        internal val lightroomDefault: (PhotoFrame) -> String?,
        internal val get: (Recipe, PhotoFrame) -> String?,
        /** Null = written for other apps, never read back (a legacy field derived from another one). */
        internal val set: ((Recipe, String, PhotoFrame) -> Recipe)?,
    ) {
        val importable: Boolean get() = set != null
    }

    /** How a field's text is written. */
    internal enum class Kind { SIGNED_INT, INT, SIGNED_1, SIGNED_2, BOOL, TEXT, CURVE }

    /** `crs:HasSettings`, `crs:ProcessVersion`: written so Lightroom reads the values as process version 2012. */
    const val PROCESS_VERSION = "11.0"

    /** Crop fields ([crop]). */
    val CROP_FIELDS = listOf("HasCrop", "CropTop", "CropLeft", "CropBottom", "CropRight", "CropAngle")

    /** Fields Reveal writes besides [fields] and [CROP_FIELDS] (removed before every write). */
    val HOUSEKEEPING = listOf("HasSettings", "ProcessVersion", "ToneCurveName2012", "PostCropVignetteStyle")

    private const val SEQ_SEPARATOR = ";"
    private const val LINEAR_CURVE = "0, 0;255, 255"
    private const val EIGHT_BIT = 255f

    // ------------------------------------------------------------------ the table

    private fun f(
        name: String,
        kind: Kind,
        default: String?,
        get: (Recipe, PhotoFrame) -> String?,
        set: ((Recipe, String, PhotoFrame) -> Recipe)?,
        group: String = name,
    ) = Field(name, group, kind, { default }, get, set)

    internal fun num(text: String): Float? = text.trim().removePrefix("+").toFloatOrNull()?.takeIf { it.isFinite() }

    private fun signed(v: Float, decimals: Int = 0): String = fmt(v, decimals, signed = true)

    private fun plain(v: Float, decimals: Int = 0): String = fmt(v, decimals, signed = false)

    internal fun fmt(v: Float, decimals: Int, signed: Boolean): String {
        val text = if (decimals == 0) v.roundToInt().toString() else String.format(Locale.ROOT, "%.${decimals}f", v)
        val zero = text.trimStart('-').all { it == '0' || it == '.' }
        val clean = if (zero) text.trimStart('-') else text
        return if (signed && !zero && !clean.startsWith('-')) "+$clean" else clean
    }

    /** A slider: Reveal value [read] / [write], clamped to [min]..[max] on import. */
    @Suppress("LongParameterList")
    private fun slider(
        name: String,
        kind: Kind,
        default: Float,
        min: Float,
        max: Float,
        read: (Recipe) -> Float,
        write: (Recipe, Float) -> Recipe,
        group: String = name,
    ): Field {
        val decimals = when (kind) {
            Kind.SIGNED_1 -> 1
            Kind.SIGNED_2 -> 2
            else -> 0
        }
        val isSigned = kind != Kind.INT
        return Field(
            name,
            group,
            kind,
            { fmt(default, decimals, isSigned) },
            { r, _ -> fmt(read(r), decimals, isSigned) },
            { r, text, _ -> num(text)?.let { write(r, it.coerceIn(min, max)) } ?: r },
        )
    }

    private fun <G> sub(
        name: String,
        kind: Kind,
        range: Triple<Float, Float, Float>,
        group: String,
        of: (Recipe) -> G,
        put: (Recipe, G) -> Recipe,
        read: G.() -> Float,
        write: G.(Float) -> G,
    ) = slider(name, kind, range.first, range.second, range.third, {
        of(it).read()
    }, { r, v -> put(r, of(r).write(v)) }, group)

    /** Range triple: Camera Raw's default, then Reveal's minimum and maximum. */
    private fun r(default: Float, min: Float, max: Float) = Triple(default, min, max)

    private val BIPOLAR = r(0f, -100f, 100f)
    private val PERCENT = r(0f, 0f, 100f)
    private val HALF = r(50f, 0f, 100f)

    private fun light(
        name: String,
        read: Light.() -> Float,
        write: Light.(Float) -> Light,
        kind: Kind = Kind.SIGNED_INT,
        range: Triple<Float, Float, Float> = BIPOLAR,
    ) = sub(name, kind, range, name, { it.light }, { rc, g -> rc.copy(light = g) }, read, write)

    private fun colour(name: String, read: Colour.() -> Float, write: Colour.(Float) -> Colour, group: String = name) =
        sub(name, Kind.SIGNED_INT, BIPOLAR, group, { it.colour }, { rc, g -> rc.copy(colour = g) }, read, write)

    private fun effects(
        name: String,
        kind: Kind,
        range: Triple<Float, Float, Float>,
        group: String,
        read: Effects.() -> Float,
        write: Effects.(Float) -> Effects,
    ) = sub(name, kind, range, group, { it.effects }, { rc, g -> rc.copy(effects = g) }, read, write)

    private fun detail(
        name: String,
        kind: Kind,
        range: Triple<Float, Float, Float>,
        group: String,
        read: Detail.() -> Float,
        write: Detail.(Float) -> Detail,
    ) = sub(name, kind, range, group, { it.detail }, { rc, g -> rc.copy(detail = g) }, read, write)

    private fun optics(
        name: String,
        range: Triple<Float, Float, Float>,
        group: String,
        read: Optics.() -> Float,
        write: Optics.(Float) -> Optics,
    ) = sub(name, Kind.INT, range, group, { it.optics }, { rc, g -> rc.copy(optics = g) }, read, write)

    private fun transform(
        name: String,
        kind: Kind,
        range: Triple<Float, Float, Float>,
        read: Transform.() -> Float,
        write: Transform.(Float) -> Transform,
    ) = sub(name, kind, range, "transform", {
        it.geometry.transform
    }, { rc, g -> rc.copy(geometry = rc.geometry.copy(transform = g)) }, read, write)

    private fun parametric(
        name: String,
        kind: Kind,
        range: Triple<Float, Float, Float>,
        read: ParametricCurve.() -> Float,
        write: ParametricCurve.(Float) -> ParametricCurve,
    ) = sub(name, kind, range, "parametric", {
        it.light.curve.parametric
    }, { rc, g -> rc.copy(light = rc.light.copy(curve = rc.light.curve.copy(parametric = g))) }, read, write)

    private fun bandFields(
        prefix: String,
        group: String,
        of: (Recipe) -> BandValues,
        with: (Recipe, BandValues) -> Recipe,
    ) = ColourBand.entries.map { band ->
        slider("$prefix${bandName(band)}", Kind.SIGNED_INT, 0f, -100f, 100f, {
            of(it)[band]
        }, { rc, v -> with(rc, of(rc).with(band, v)) }, group)
    }

    private fun bandName(band: ColourBand): String = band.name.lowercase().replaceFirstChar { it.uppercase() }

    private enum class Part { HUE, SAT, LUM }

    private fun wheel(name: String, region: GradeRegion, part: Part): Field {
        val (kind, range) = when (part) {
            Part.HUE -> Kind.INT to r(0f, 0f, 360f)
            Part.SAT -> Kind.INT to PERCENT
            Part.LUM -> Kind.SIGNED_INT to BIPOLAR
        }
        return sub(
            name,
            kind,
            range,
            GRADING,
            { it.colour.grading[region] },
            { rc, w -> rc.copy(colour = rc.colour.copy(grading = rc.colour.grading.with(region, w))) },
            {
                when (part) {
                    Part.HUE -> hue
                    Part.SAT -> saturation
                    Part.LUM -> luminance
                }
            },
            {
                when (part) {
                    Part.HUE -> copy(hue = it)
                    Part.SAT -> copy(saturation = it)
                    Part.LUM -> copy(luminance = it)
                }
            },
        )
    }

    private const val GRADING = "grading"

    private val WB_NAMES = mapOf(
        WbMode.AS_SHOT to "As Shot",
        WbMode.AUTO to "Auto",
        WbMode.DAYLIGHT to "Daylight",
        WbMode.CLOUDY to "Cloudy",
        WbMode.SHADE to "Shade",
        WbMode.TUNGSTEN to "Tungsten",
        WbMode.FLUORESCENT to "Fluorescent",
        WbMode.FLASH to "Flash",
        WbMode.CUSTOM to "Custom",
    )

    private val UPRIGHT = listOf(
        UprightMode.OFF,
        UprightMode.AUTO,
        UprightMode.LEVEL,
        UprightMode.VERTICAL,
        UprightMode.FULL,
        UprightMode.GUIDED,
    )

    /** `"x, y"` items of a point curve in 0..255, a straight curve when Reveal stores none. */
    private fun curveText(points: List<CurvePoint>): String = if (points.isEmpty()) {
        LINEAR_CURVE
    } else {
        points.joinToString(SEQ_SEPARATOR) { "${(it.x * EIGHT_BIT).roundToInt()}, ${(it.y * EIGHT_BIT).roundToInt()}" }
    }

    @Suppress("ReturnCount")
    private fun curvePoints(text: String): List<CurvePoint>? {
        val points = text.split(SEQ_SEPARATOR).map { item ->
            val parts = item.split(',').map { it.trim().toFloatOrNull() ?: return null }
            if (parts.size != 2) return null
            CurvePoint((parts[0] / EIGHT_BIT).coerceIn(0f, 1f), (parts[1] / EIGHT_BIT).coerceIn(0f, 1f))
        }
        if (points.size < 2 || points.size > Curves.MAX_POINTS) return null
        if (points.zipWithNext().any { (a, b) -> b.x <= a.x }) return null
        return points
    }

    private fun curveField(
        name: String,
        read: Curves.() -> List<CurvePoint>,
        write: Curves.(List<CurvePoint>) -> Curves,
    ) = f(name, Kind.CURVE, LINEAR_CURVE, { rc, _ -> curveText(rc.light.curve.read()) }, { rc, text, _ ->
        curvePoints(text)?.let { points ->
            val stored = if (points == Curves.LINEAR) emptyList() else points
            runCatching { rc.copy(light = rc.light.copy(curve = rc.light.curve.write(stored))) }.getOrNull()
        } ?: rc
    })

    private fun switch(name: String, group: String, read: (Recipe) -> Boolean, write: (Recipe, Boolean) -> Recipe) =
        f(name, Kind.INT, "0", { rc, _ -> if (read(rc)) "1" else "0" }, { rc, t, _ ->
            num(t)?.let { write(rc, it >= 1f) }
                ?: rc
        }, group)

    private fun whiteBalance(): List<Field> {
        val mode =
            f("WhiteBalance", Kind.TEXT, "As Shot", { rc, _ ->
                WB_NAMES.getValue(rc.colour.whiteBalance.mode)
            }, { rc, text, _ ->
                val mode =
                    WB_NAMES.entries.firstOrNull { it.value.equals(text.trim(), ignoreCase = true) }?.key
                        ?: WbMode.CUSTOM
                val wb = rc.colour.whiteBalance
                val next = if (mode == WbMode.AS_SHOT) WhiteBalance() else wb.copy(mode = mode)
                rc.copy(colour = rc.colour.copy(whiteBalance = next))
            }, "wb")
        // Kelvin and tint for a RAW; Lightroom's relative -100..+100 for a JPEG; nothing for "As Shot".
        val temperature = f("Temperature", Kind.INT, null, { rc, frame ->
            val wb = rc.colour.whiteBalance
            if (frame.raw) wb.temperatureK?.let { plain(it) } else signed(wb.relTemperature)
        }, { rc, text, frame ->
            setWb(rc, text) { wb, v ->
                if (frame.raw) {
                    wb.copy(
                        temperatureK = v.coerceIn(KELVIN_MIN, KELVIN_MAX),
                    )
                } else {
                    wb.copy(relTemperature = v.coerceIn(-100f, 100f))
                }
            }
        }, "wb")
        val tint = f("Tint", Kind.SIGNED_INT, null, { rc, frame ->
            val wb = rc.colour.whiteBalance
            if (frame.raw) wb.tint?.let { signed(it) } else signed(wb.relTint)
        }, { rc, text, frame ->
            setWb(rc, text) { wb, v ->
                if (frame.raw) wb.copy(tint = v.coerceIn(-150f, 150f)) else wb.copy(relTint = v.coerceIn(-100f, 100f))
            }
        }, "wb")
        return listOf(mode, temperature, tint)
    }

    private fun setWb(rc: Recipe, text: String, change: (WhiteBalance, Float) -> WhiteBalance): Recipe {
        val v = num(text) ?: return rc
        val wb = rc.colour.whiteBalance
        if (wb.mode == WbMode.AS_SHOT) return rc
        return rc.copy(colour = rc.colour.copy(whiteBalance = change(wb, v)))
    }

    private const val KELVIN_MIN = 2000f
    private const val KELVIN_MAX = 50000f

    /** Every mapped field, in the order they are written and applied (a group's mode before its values). */
    val fields: List<Field> = buildList {
        // Light (slots 6-8) and presence.
        add(light("Exposure2012", { exposureEv }, { copy(exposureEv = it) }, Kind.SIGNED_2, r(0f, -5f, 5f)))
        add(light("Contrast2012", { contrast }, { copy(contrast = it) }))
        add(light("Highlights2012", { highlights }, { copy(highlights = it) }))
        add(light("Shadows2012", { shadows }, { copy(shadows = it) }))
        add(light("Whites2012", { whites }, { copy(whites = it) }))
        add(light("Blacks2012", { blacks }, { copy(blacks = it) }))
        add(effects("Texture", Kind.SIGNED_INT, BIPOLAR, "Texture", { texture }, { copy(texture = it) }))
        add(effects("Clarity2012", Kind.SIGNED_INT, BIPOLAR, "Clarity2012", { clarity }, { copy(clarity = it) }))
        add(effects("Dehaze", Kind.SIGNED_INT, BIPOLAR, "Dehaze", { dehaze }, { copy(dehaze = it) }))
        add(colour("Vibrance", { vibrance }, { copy(vibrance = it) }))
        add(colour("Saturation", { saturation }, { copy(saturation = it) }))
        addAll(whiteBalance())
        // Detail (slot 11): Lightroom sharpens a RAW by 40 and removes its colour noise by 25 by default.
        add(
            Field("Sharpness", "sharpen", Kind.INT, {
                if (it.raw) "40" else "0"
            }, { rc, _ -> plain(rc.detail.sharpenAmount) }) { rc, t, _ ->
                num(t)?.let { rc.copy(detail = rc.detail.copy(sharpenAmount = it.coerceIn(0f, 150f))) } ?: rc
            },
        )
        add(
            detail("SharpenRadius", Kind.SIGNED_1, r(1f, 0.5f, 3f), "sharpen", {
                sharpenRadius
            }, { copy(sharpenRadius = it) }),
        )
        add(
            detail("SharpenDetail", Kind.INT, r(25f, 0f, 100f), "sharpen", {
                sharpenDetail
            }, { copy(sharpenDetail = it) }),
        )
        add(
            detail("SharpenEdgeMasking", Kind.INT, PERCENT, "sharpen", {
                sharpenMasking
            }, { copy(sharpenMasking = it) }),
        )
        add(detail("LuminanceSmoothing", Kind.INT, PERCENT, "nrLum", { nrLuminance }, { copy(nrLuminance = it) }))
        add(
            detail("LuminanceNoiseReductionDetail", Kind.INT, HALF, "nrLum", {
                nrLuminanceDetail
            }, { copy(nrLuminanceDetail = it) }),
        )
        add(
            detail("LuminanceNoiseReductionContrast", Kind.INT, PERCENT, "nrLum", {
                nrLuminanceContrast
            }, { copy(nrLuminanceContrast = it) }),
        )
        add(
            Field("ColorNoiseReduction", "nrColour", Kind.INT, {
                if (it.raw) "25" else "0"
            }, { rc, _ -> plain(rc.detail.nrColour) }) { rc, t, _ ->
                num(t)?.let { rc.copy(detail = rc.detail.copy(nrColour = it.coerceIn(0f, 100f))) } ?: rc
            },
        )
        add(
            detail("ColorNoiseReductionDetail", Kind.INT, HALF, "nrColour", {
                nrColourDetail
            }, { copy(nrColourDetail = it) }),
        )
        add(
            detail("ColorNoiseReductionSmoothness", Kind.INT, HALF, "nrColour", {
                nrColourSmoothness
            }, { copy(nrColourSmoothness = it) }),
        )
        // Effects: the post-crop vignette (Lightroom's "highlight priority" style) and grain.
        add(
            effects("PostCropVignetteAmount", Kind.SIGNED_INT, BIPOLAR, "vignette", {
                vignetteAmount
            }, { copy(vignetteAmount = it) }),
        )
        add(
            effects("PostCropVignetteMidpoint", Kind.INT, HALF, "vignette", {
                vignetteMidpoint
            }, { copy(vignetteMidpoint = it) }),
        )
        add(
            effects("PostCropVignetteRoundness", Kind.SIGNED_INT, BIPOLAR, "vignette", {
                vignetteRoundness
            }, { copy(vignetteRoundness = it) }),
        )
        add(
            effects("PostCropVignetteFeather", Kind.INT, HALF, "vignette", {
                vignetteFeather
            }, { copy(vignetteFeather = it) }),
        )
        add(
            effects("PostCropVignetteHighlightContrast", Kind.INT, PERCENT, "vignette", {
                vignetteHighlights
            }, { copy(vignetteHighlights = it) }),
        )
        add(effects("GrainAmount", Kind.INT, PERCENT, "grain", { grainAmount }, { copy(grainAmount = it) }))
        add(effects("GrainSize", Kind.INT, r(25f, 0f, 100f), "grain", { grainSize }, { copy(grainSize = it) }))
        add(effects("GrainFrequency", Kind.INT, HALF, "grain", { grainRoughness }, { copy(grainRoughness = it) }))
        // Optics: the profile switch and amounts (each app applies its own profile), CA, defringe.
        add(
            switch("LensProfileEnable", "lens", {
                it.optics.lensCorrections
            }) { rc, on -> rc.copy(optics = rc.optics.copy(lensCorrections = on)) },
        )
        add(
            optics("LensProfileDistortionScale", r(100f, 0f, 200f), "lens", {
                distortionPercent
            }, { copy(distortionPercent = it) }),
        )
        add(
            optics("LensProfileVignettingScale", r(100f, 0f, 200f), "lens", {
                vignettingPercent
            }, { copy(vignettingPercent = it) }),
        )
        add(
            switch("AutoLateralCA", "AutoLateralCA", {
                it.optics.removeCa
            }) { rc, on -> rc.copy(optics = rc.optics.copy(removeCa = on)) },
        )
        add(optics("DefringePurpleAmount", r(0f, 0f, 20f), "purple", { purpleAmount }, { copy(purpleAmount = it) }))
        add(optics("DefringePurpleHueLo", r(30f, 0f, 100f), "purple", { purpleHueLow }, { copy(purpleHueLow = it) }))
        add(optics("DefringePurpleHueHi", r(70f, 0f, 100f), "purple", { purpleHueHigh }, { copy(purpleHueHigh = it) }))
        add(optics("DefringeGreenAmount", r(0f, 0f, 20f), "green", { greenAmount }, { copy(greenAmount = it) }))
        add(optics("DefringeGreenHueLo", r(40f, 0f, 100f), "green", { greenHueLow }, { copy(greenHueLow = it) }))
        add(optics("DefringeGreenHueHi", r(60f, 0f, 100f), "green", { greenHueHigh }, { copy(greenHueHigh = it) }))
        // Transform (before the crop in both apps).
        add(transform("PerspectiveVertical", Kind.SIGNED_INT, BIPOLAR, { vertical }, { copy(vertical = it) }))
        add(transform("PerspectiveHorizontal", Kind.SIGNED_INT, BIPOLAR, { horizontal }, { copy(horizontal = it) }))
        add(transform("PerspectiveRotate", Kind.SIGNED_1, r(0f, -10f, 10f), { rotate }, { copy(rotate = it) }))
        add(transform("PerspectiveScale", Kind.INT, r(100f, 50f, 150f), { scale }, { copy(scale = it) }))
        add(transform("PerspectiveAspect", Kind.SIGNED_INT, BIPOLAR, { aspect }, { copy(aspect = it) }))
        add(transform("PerspectiveX", Kind.SIGNED_1, BIPOLAR, { offsetX }, { copy(offsetX = it) }))
        add(transform("PerspectiveY", Kind.SIGNED_1, BIPOLAR, { offsetY }, { copy(offsetY = it) }))
        add(
            f("PerspectiveUpright", Kind.INT, "0", { rc, _ ->
                UPRIGHT.indexOf(rc.geometry.transform.upright).toString()
            }, { rc, t, _ ->
                val mode = num(t)?.roundToInt()?.let { UPRIGHT.getOrNull(it) }
                if (mode ==
                    null
                ) {
                    rc
                } else {
                    rc.copy(geometry = rc.geometry.copy(transform = rc.geometry.transform.copy(upright = mode)))
                }
            }, "transform"),
        )
        // Tone curve: the parametric regions and splits, then the point curves (0..255 pairs).
        add(parametric("ParametricShadows", Kind.SIGNED_INT, BIPOLAR, { shadows }, { copy(shadows = it) }))
        add(parametric("ParametricDarks", Kind.SIGNED_INT, BIPOLAR, { darks }, { copy(darks = it) }))
        add(parametric("ParametricLights", Kind.SIGNED_INT, BIPOLAR, { lights }, { copy(lights = it) }))
        add(parametric("ParametricHighlights", Kind.SIGNED_INT, BIPOLAR, { highlights }, { copy(highlights = it) }))
        add(
            parametric("ParametricShadowSplit", Kind.INT, r(25f, 5f, 95f), {
                splitShadows
            }, { copy(splitShadows = it) }),
        )
        add(
            parametric("ParametricMidtoneSplit", Kind.INT, r(50f, 5f, 95f), {
                splitMidtones
            }, { copy(splitMidtones = it) }),
        )
        add(
            parametric("ParametricHighlightSplit", Kind.INT, r(75f, 5f, 95f), {
                splitHighlights
            }, { copy(splitHighlights = it) }),
        )
        add(curveField("ToneCurvePV2012", { rgb }, { copy(rgb = it) }))
        add(curveField("ToneCurvePV2012Red", { red }, { copy(red = it) }))
        add(curveField("ToneCurvePV2012Green", { green }, { copy(green = it) }))
        add(curveField("ToneCurvePV2012Blue", { blue }, { copy(blue = it) }))
        // Colour mixer (HSL), then black & white: the treatment and the grey mixer.
        addAll(bandFields("HueAdjustment", "hsl", { it.colour.mixer.hue }) { rc, b -> rc.mixer { copy(hue = b) } })
        addAll(
            bandFields("SaturationAdjustment", "hsl", {
                it.colour.mixer.saturation
            }) { rc, b -> rc.mixer { copy(saturation = b) } },
        )
        addAll(
            bandFields("LuminanceAdjustment", "hsl", {
                it.colour.mixer.luminance
            }) { rc, b -> rc.mixer { copy(luminance = b) } },
        )
        add(
            f("ConvertToGrayscale", Kind.BOOL, "False", { rc, _ ->
                if (rc.profile.treatment ==
                    Treatment.BLACK_WHITE
                ) {
                    "True"
                } else {
                    "False"
                }
            }, { rc, t, _ ->
                val bw = t.trim().equals("True", ignoreCase = true)
                rc.copy(profile = rc.profile.copy(treatment = if (bw) Treatment.BLACK_WHITE else Treatment.COLOUR))
            }, "bw"),
        )
        addAll(
            bandFields("GrayMixer", "bw", {
                it.colour.greyMixer
            }) { rc, b -> rc.copy(colour = rc.colour.copy(greyMixer = b)) },
        )
        // Colour grading: Lightroom keeps the shadow and highlight hue/saturation in its split-toning fields.
        add(wheel("SplitToningShadowHue", GradeRegion.SHADOWS, Part.HUE))
        add(wheel("SplitToningShadowSaturation", GradeRegion.SHADOWS, Part.SAT))
        add(wheel("ColorGradeShadowLum", GradeRegion.SHADOWS, Part.LUM))
        add(wheel("ColorGradeMidtoneHue", GradeRegion.MIDTONES, Part.HUE))
        add(wheel("ColorGradeMidtoneSat", GradeRegion.MIDTONES, Part.SAT))
        add(wheel("ColorGradeMidtoneLum", GradeRegion.MIDTONES, Part.LUM))
        add(wheel("SplitToningHighlightHue", GradeRegion.HIGHLIGHTS, Part.HUE))
        add(wheel("SplitToningHighlightSaturation", GradeRegion.HIGHLIGHTS, Part.SAT))
        add(wheel("ColorGradeHighlightLum", GradeRegion.HIGHLIGHTS, Part.LUM))
        add(wheel("ColorGradeGlobalHue", GradeRegion.GLOBAL, Part.HUE))
        add(wheel("ColorGradeGlobalSat", GradeRegion.GLOBAL, Part.SAT))
        add(wheel("ColorGradeGlobalLum", GradeRegion.GLOBAL, Part.LUM))
        add(grading("ColorGradeBlending", Kind.INT, HALF, { blending }, { copy(blending = it) }))
        add(grading("SplitToningBalance", Kind.SIGNED_INT, BIPOLAR, { balance }, { copy(balance = it) }))
    }

    private fun grading(
        name: String,
        kind: Kind,
        range: Triple<Float, Float, Float>,
        read: ColourGrading.() -> Float,
        write: ColourGrading.(Float) -> ColourGrading,
    ) = sub(name, kind, range, GRADING, {
        it.colour.grading
    }, { rc, g -> rc.copy(colour = rc.colour.copy(grading = g)) }, read, write)

    private fun Recipe.mixer(block: ColourMixer.() -> ColourMixer) =
        copy(colour = colour.copy(mixer = colour.mixer.block()))

    private val byName: Map<String, Field> = fields.associateBy { it.name }

    /** The field called [name], or null. */
    fun field(name: String): Field? = byName[name]

    /** Names of everything this mapping manages in the `crs:` namespace (removed before a write). */
    val managedNames: Set<String> = (fields.map { it.name } + CROP_FIELDS + HOUSEKEEPING).toSet()

    // ------------------------------------------------------------------ values

    /** Whether a field's value is a list (`rdf:Seq`). */
    fun isList(name: String): Boolean = byName[name]?.kind == Kind.CURVE

    /** List values are kept as one text, items joined by this separator. */
    fun joinList(items: List<String>): String = items.joinToString(SEQ_SEPARATOR) { it.trim() }

    fun splitList(text: String): List<String> = text.split(SEQ_SEPARATOR)

    /** The comparable form of [text] for field [name] (numbers re-formatted, case of booleans ignored). */
    fun normalise(name: String, text: String): String? {
        val field = byName[name] ?: return text.trim()
        return when (field.kind) {
            Kind.SIGNED_INT -> num(text)?.let { signed(it) }
            Kind.INT -> num(text)?.let { plain(it) }
            Kind.SIGNED_1 -> num(text)?.let { signed(it, 1) }
            Kind.SIGNED_2 -> num(text)?.let { signed(it, 2) }
            Kind.BOOL -> if (text.trim().equals("True", ignoreCase = true)) "True" else "False"
            Kind.TEXT -> text.trim().lowercase(Locale.ROOT)
            Kind.CURVE -> curvePoints(text)?.let(::curveText)
        }
    }

    /** True when [a] and [b] mean the same value of [name]. */
    fun same(name: String, a: String?, b: String?): Boolean =
        (a?.let { normalise(name, it) }) == (b?.let { normalise(name, it) })

    /** Every mapped field's text for [recipe] (fields with nothing to say, an as-shot temperature, are left out). */
    fun values(recipe: Recipe, frame: PhotoFrame): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in fields) field.get(recipe, frame)?.let { out[field.name] = it }
        return out
    }

    /** Camera Raw's own value of [name] for an untouched photo. */
    fun lightroomDefault(name: String, frame: PhotoFrame): String? = byName[name]?.lightroomDefault?.invoke(frame)

    /**
     * [base] with every importable field of [groups] taken from [values] (`crs:` local name -> text), in table order;
     * fields that are absent or do not parse leave the recipe as it was.
     */
    fun apply(base: Recipe, values: Map<String, String>, groups: Set<String>, frame: PhotoFrame): Recipe {
        var recipe = base
        for (field in fields.filter { it.importable && it.group in groups }) {
            val text = values[field.name] ?: continue
            recipe = runCatching { field.set!!(recipe, text, frame) }.getOrDefault(recipe)
        }
        return recipe
    }
}

/**
 * EXIF/TIFF orientations 1..8 as the 2 x 2 matrices of the dihedral group, acting on centred coordinates (y down) from
 * the stored image to the picture as seen: `[a, b, c, d]` maps `(x, y)` to `(a x + b y, c x + d y)`.
 */
internal object Orientation {
    private val MATRICES = mapOf(
        1 to intArrayOf(1, 0, 0, 1),
        2 to intArrayOf(-1, 0, 0, 1),
        3 to intArrayOf(-1, 0, 0, -1),
        4 to intArrayOf(1, 0, 0, -1),
        5 to intArrayOf(0, 1, 1, 0),
        6 to intArrayOf(0, -1, 1, 0),
        7 to intArrayOf(0, -1, -1, 0),
        8 to intArrayOf(0, 1, -1, 0),
    )
    private val QUARTER = intArrayOf(0, -1, 1, 0)
    private val FLIP_H = intArrayOf(-1, 0, 0, 1)
    private val FLIP_V = intArrayOf(1, 0, 0, -1)
    private val IDENTITY = intArrayOf(1, 0, 0, 1)

    fun matrix(orientation: Int): IntArray = MATRICES[orientation] ?: IDENTITY

    fun code(m: IntArray): Int = MATRICES.entries.first { it.value.contentEquals(m) }.key

    fun times(p: IntArray, q: IntArray) = intArrayOf(
        p[0] * q[0] + p[1] * q[2],
        p[0] * q[1] + p[1] * q[3],
        p[2] * q[0] + p[3] * q[2],
        p[2] * q[1] + p[3] * q[3],
    )

    /** The inverse (orthogonal: the transpose). */
    fun inverse(m: IntArray) = intArrayOf(m[0], m[2], m[1], m[3])

    fun det(m: IntArray): Int = m[0] * m[3] - m[1] * m[2]

    /** A normalised point (0..1) through [m] about the centre. */
    fun applyNormalised(m: IntArray, x: Double, y: Double): Pair<Double, Double> {
        val u = x - 0.5
        val v = y - 0.5
        return (m[0] * u + m[1] * v + 0.5) to (m[2] * u + m[3] * v + 0.5)
    }

    /** EXIF orientation, then [rotate] clockwise quarter turns, then the flips (`GeometryMapping`'s `F * Q^rotate`). */
    fun compose(exif: Int, rotate: Int, flipH: Boolean, flipV: Boolean): Int {
        var m = matrix(exif)
        repeat(rotate.mod(4)) { m = times(QUARTER, m) }
        if (flipH) m = times(FLIP_H, m)
        if (flipV) m = times(FLIP_V, m)
        return code(m)
    }

    /** The quarter turns and horizontal flip that take [exif] to [target] (a vertical flip is a turn plus a flip). */
    fun residual(exif: Int, target: Int): Pair<Int, Boolean>? {
        if (target !in 1..8) return null
        for (flip in listOf(false, true)) {
            for (rotate in 0..3) if (compose(exif, rotate, flip, false) == target) return rotate to flip
        }
        return null
    }
}
