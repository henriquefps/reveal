package dev.hfps.reveal.pipeline.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Recipe fields of the tone curve (Light), the colour mixer, colour grading and black & white (Colour), docs/adr/0013.
// Every default means "no change"; the fields are additive (no RECIPE_VERSION bump, docs/adr/0003).

/** The profile's treatment: colour, or black & white with its own grey mixer ([Colour.greyMixer]). */
@Serializable
enum class Treatment {
    @SerialName("colour")
    COLOUR,

    @SerialName("bw")
    BLACK_WHITE,
}

/** The 8 hue bands of the colour mixer and the grey mixer, in hue order. */
@Serializable
enum class ColourBand {
    @SerialName("red")
    RED,

    @SerialName("orange")
    ORANGE,

    @SerialName("yellow")
    YELLOW,

    @SerialName("green")
    GREEN,

    @SerialName("aqua")
    AQUA,

    @SerialName("blue")
    BLUE,

    @SerialName("purple")
    PURPLE,

    @SerialName("magenta")
    MAGENTA,
}

/** One value per [ColourBand], -100..+100 (0 = no change). */
@Serializable
data class BandValues(
    @SerialName("red") val red: Float = 0f,
    @SerialName("orange") val orange: Float = 0f,
    @SerialName("yellow") val yellow: Float = 0f,
    @SerialName("green") val green: Float = 0f,
    @SerialName("aqua") val aqua: Float = 0f,
    @SerialName("blue") val blue: Float = 0f,
    @SerialName("purple") val purple: Float = 0f,
    @SerialName("magenta") val magenta: Float = 0f,
) {
    init {
        require(toList().all { it.isFinite() }) { "band values must be finite" }
    }

    /** The value of [band]. */
    operator fun get(band: ColourBand): Float = when (band) {
        ColourBand.RED -> red
        ColourBand.ORANGE -> orange
        ColourBand.YELLOW -> yellow
        ColourBand.GREEN -> green
        ColourBand.AQUA -> aqua
        ColourBand.BLUE -> blue
        ColourBand.PURPLE -> purple
        ColourBand.MAGENTA -> magenta
    }

    /** A copy with [band] set to [value]. */
    fun with(band: ColourBand, value: Float): BandValues = when (band) {
        ColourBand.RED -> copy(red = value)
        ColourBand.ORANGE -> copy(orange = value)
        ColourBand.YELLOW -> copy(yellow = value)
        ColourBand.GREEN -> copy(green = value)
        ColourBand.AQUA -> copy(aqua = value)
        ColourBand.BLUE -> copy(blue = value)
        ColourBand.PURPLE -> copy(purple = value)
        ColourBand.MAGENTA -> copy(magenta = value)
    }

    /** The values in [ColourBand] order. */
    fun toList(): List<Float> = listOf(red, orange, yellow, green, aqua, blue, purple, magenta)

    val isNeutral: Boolean get() = this == NONE

    companion object {
        val NONE: BandValues = BandValues()

        /** Values in [ColourBand] order. */
        @Suppress("MagicNumber")
        fun of(values: List<Float>): BandValues {
            require(values.size == ColourBand.entries.size) { "one value per band" }
            return BandValues(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
        }
    }
}

/** The colour mixer (HSL): hue, saturation and luminance per band, each -100..+100. */
@Serializable
data class ColourMixer(
    @SerialName("hue") val hue: BandValues = BandValues(),
    @SerialName("saturation") val saturation: BandValues = BandValues(),
    @SerialName("luminance") val luminance: BandValues = BandValues(),
) {
    val isNeutral: Boolean get() = hue.isNeutral && saturation.isNeutral && luminance.isNeutral
}

/** The three properties of the colour mixer (its tabs; also what the targeted adjustment changes). */
enum class MixerProperty { HUE, SATURATION, LUMINANCE }

/** One wheel of colour grading: the tint's [hue] (OkLab hue, degrees 0..360), its [saturation] 0..100, [luminance]. */
@Serializable
data class GradeWheel(
    @SerialName("hue") val hue: Float = 0f,
    @SerialName("saturation") val saturation: Float = 0f,
    @SerialName("luminance") val luminance: Float = 0f,
) {
    init {
        require(hue.isFinite() && saturation.isFinite() && luminance.isFinite()) { "grading values must be finite" }
    }

    val isNeutral: Boolean get() = saturation == 0f && luminance == 0f
}

/** The wheels of colour grading. */
enum class GradeRegion { SHADOWS, MIDTONES, HIGHLIGHTS, GLOBAL }

/**
 * Colour grading: shadows / midtones / highlights / global wheels, [blending] (0..100, how much the three tonal
 * regions overlap) and [balance] (-100..+100, positive = more of the image counts as highlights).
 */
@Serializable
data class ColourGrading(
    @SerialName("shadows") val shadows: GradeWheel = GradeWheel(),
    @SerialName("midtones") val midtones: GradeWheel = GradeWheel(),
    @SerialName("highlights") val highlights: GradeWheel = GradeWheel(),
    @SerialName("global") val global: GradeWheel = GradeWheel(),
    @SerialName("blending") val blending: Float = DEFAULT_BLENDING,
    @SerialName("balance") val balance: Float = 0f,
) {
    init {
        require(blending.isFinite() && balance.isFinite()) { "grading values must be finite" }
    }

    /** The wheel of [region]. */
    operator fun get(region: GradeRegion): GradeWheel = when (region) {
        GradeRegion.SHADOWS -> shadows
        GradeRegion.MIDTONES -> midtones
        GradeRegion.HIGHLIGHTS -> highlights
        GradeRegion.GLOBAL -> global
    }

    /** A copy with the wheel of [region] replaced. */
    fun with(region: GradeRegion, wheel: GradeWheel): ColourGrading = when (region) {
        GradeRegion.SHADOWS -> copy(shadows = wheel)
        GradeRegion.MIDTONES -> copy(midtones = wheel)
        GradeRegion.HIGHLIGHTS -> copy(highlights = wheel)
        GradeRegion.GLOBAL -> copy(global = wheel)
    }

    /** True when no wheel tints or changes luminance (blending and balance alone change nothing). */
    val isNeutral: Boolean get() = GradeRegion.entries.all { get(it).isNeutral }

    companion object {
        const val DEFAULT_BLENDING: Float = 50f
    }
}

/** A point of a point curve, both coordinates 0..1 in the curve's encoded (sRGB-transfer) space. */
@Serializable
data class CurvePoint(@SerialName("x") val x: Float, @SerialName("y") val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "curve points lie in [0, 1]" }
    }
}

/** The channels of the point curve. */
enum class CurveChannel { RGB, RED, GREEN, BLUE }

/**
 * The parametric curve: four region sliders (-100..+100) and the three movable splits between the regions
 * (percent of the encoded range, kept in order with a minimum gap by [splits]).
 */
@Serializable
data class ParametricCurve(
    @SerialName("highlights") val highlights: Float = 0f,
    @SerialName("lights") val lights: Float = 0f,
    @SerialName("darks") val darks: Float = 0f,
    @SerialName("shadows") val shadows: Float = 0f,
    @SerialName("splitShadows") val splitShadows: Float = DEFAULT_SPLIT_SHADOWS,
    @SerialName("splitMidtones") val splitMidtones: Float = DEFAULT_SPLIT_MIDTONES,
    @SerialName("splitHighlights") val splitHighlights: Float = DEFAULT_SPLIT_HIGHLIGHTS,
) {
    init {
        val all = listOf(highlights, lights, darks, shadows, splitShadows, splitMidtones, splitHighlights)
        require(all.all { it.isFinite() }) { "parametric curve values must be finite" }
    }

    /** The region sliders are all 0 (the splits alone change nothing). */
    val isNeutral: Boolean get() = highlights == 0f && lights == 0f && darks == 0f && shadows == 0f

    companion object {
        const val DEFAULT_SPLIT_SHADOWS: Float = 25f
        const val DEFAULT_SPLIT_MIDTONES: Float = 50f
        const val DEFAULT_SPLIT_HIGHLIGHTS: Float = 75f
    }
}

/**
 * The tone curve: the [parametric] curve, then the point curves ([rgb] on all channels, then [red], [green], [blue]).
 * An empty point list is the straight line; a stored list holds 2..[MAX_POINTS] points with strictly increasing x.
 */
@Serializable
data class Curves(
    @SerialName("parametric") val parametric: ParametricCurve = ParametricCurve(),
    @SerialName("rgb") val rgb: List<CurvePoint> = emptyList(),
    @SerialName("red") val red: List<CurvePoint> = emptyList(),
    @SerialName("green") val green: List<CurvePoint> = emptyList(),
    @SerialName("blue") val blue: List<CurvePoint> = emptyList(),
) {
    init {
        listOf(rgb, red, green, blue).forEach { points ->
            require(points.isEmpty() || points.size in 2..MAX_POINTS) { "a point curve has 2..$MAX_POINTS points" }
            require(points.zipWithNext().all { (a, b) -> a.x < b.x }) { "curve points must have increasing x" }
        }
    }

    /** The points of [channel]. */
    operator fun get(channel: CurveChannel): List<CurvePoint> = when (channel) {
        CurveChannel.RGB -> rgb
        CurveChannel.RED -> red
        CurveChannel.GREEN -> green
        CurveChannel.BLUE -> blue
    }

    /** A copy with [channel]'s points replaced (a straight line is stored as the empty list). */
    fun with(channel: CurveChannel, points: List<CurvePoint>): Curves {
        val stored = if (isLinear(points)) emptyList() else points
        return when (channel) {
            CurveChannel.RGB -> copy(rgb = stored)
            CurveChannel.RED -> copy(red = stored)
            CurveChannel.GREEN -> copy(green = stored)
            CurveChannel.BLUE -> copy(blue = stored)
        }
    }

    /** True when every curve is the identity. */
    val isNeutral: Boolean
        get() = parametric.isNeutral && CurveChannel.entries.all { isLinear(get(it)) }

    companion object {
        /** Most points one point curve holds (the shader's loop bound). */
        const val MAX_POINTS: Int = 16

        /** The straight line, as the editor shows it. */
        val LINEAR: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

        /** Point curve preset "Medium contrast" (a gentle S, 8-bit coordinates /255 like the common raw converters). */
        val MEDIUM_CONTRAST: List<CurvePoint> = points(0 to 0, 32 to 22, 64 to 56, 128 to 128, 192 to 196, 255 to 255)

        /** Point curve preset "Strong contrast". */
        val STRONG_CONTRAST: List<CurvePoint> = points(0 to 0, 32 to 16, 64 to 50, 128 to 128, 192 to 202, 255 to 255)

        private fun points(vararg p: Pair<Int, Int>): List<CurvePoint> =
            p.map { (x, y) -> CurvePoint(x / EIGHT_BIT, y / EIGHT_BIT) }

        private const val EIGHT_BIT = 255f

        /** A point list that draws the identity: empty, or exactly the two corners. */
        fun isLinear(points: List<CurvePoint>): Boolean = points.isEmpty() || points == LINEAR
    }
}
