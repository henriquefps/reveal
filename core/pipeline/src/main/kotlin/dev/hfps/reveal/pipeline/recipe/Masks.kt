@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.recipe

import dev.hfps.reveal.pipeline.mask.StrokeCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A local adjustment mask (docs/adr/0022): [components] combined in order into a coverage in 0..1 per pixel, in upright
 * image coordinates before crop and transform, and the [adjustments] applied there, scaled by [amount].
 *
 * The placeholder written before M4 (`{"id": ""}`) reads as an empty, enabled mask without adjustments.
 */
@Serializable
data class Mask(
    /** Stable identifier of the mask within the recipe (a uuid); history and the overlay find a mask by it. */
    @SerialName("id") val id: String = "",
    /** The user's name for the mask (the history labels show it). */
    @SerialName("name") val name: String = "",
    /** The "visible" eye: false renders as if the mask were absent and keeps its definition. */
    @SerialName("enabled") val enabled: Boolean = true,
    /** Overall strength in percent, 0..100: scales every local adjustment of the mask (its opacity). */
    @SerialName("amount") val amount: Float = FULL,
    /** What the mask changes; every default is "no change". */
    @SerialName("adjustments") val adjustments: LocalAdjustments = LocalAdjustments(),
    /** The shape: combined in order, each with its [MaskOp] and invert. */
    @SerialName("components") val components: List<MaskComponent> = emptyList(),
) {
    init {
        require(amount.isFinite()) { "mask amount must be finite" }
    }

    companion object {
        private const val FULL = 100f
    }
}

/** How a component combines with the coverage of the components before it (docs/adr/0022). */
@Serializable
enum class MaskOp {
    /** Union: `max(coverage, v)`. */
    @SerialName("add")
    ADD,

    /** `coverage (1 - v)`. */
    @SerialName("subtract")
    SUBTRACT,

    /** `coverage v` (Lightroom's "intersect with"). */
    @SerialName("intersect")
    INTERSECT,
}

/**
 * One part of a mask's shape. Positions are fractions of the upright image's width and height (y down), lengths are
 * fractions of its long edge (docs/adr/0022, "The recipe"). Every component has an [op] and an [invert] flag; the JSON
 * carries a `type` discriminator, and a type this version does not know is kept verbatim ([Unknown]) and contributes
 * nothing, so a recipe from a newer app round-trips.
 */
@Serializable(with = MaskComponentSerializer::class)
sealed class MaskComponent {
    abstract val op: MaskOp
    abstract val invert: Boolean

    /** The JSON `type` of this component. */
    abstract val type: String

    /** A linear gradient: full effect at ([x0], [y0]), none at ([x1], [y1]); [feather] 0..100 (transition width). */
    @Serializable
    data class Linear(
        @SerialName("type") override val type: String = TYPE,
        @SerialName("op") override val op: MaskOp = MaskOp.ADD,
        @SerialName("invert") override val invert: Boolean = false,
        @SerialName("x0") val x0: Float = HALF,
        @SerialName("y0") val y0: Float = 0f,
        @SerialName("x1") val x1: Float = HALF,
        @SerialName("y1") val y1: Float = HALF,
        @SerialName("feather") val feather: Float = FULL,
    ) : MaskComponent() {
        init {
            requireFinite(x0, y0, x1, y1, feather)
        }

        companion object {
            const val TYPE: String = "linear"
        }
    }

    /**
     * An ellipse centred at ([cx], [cy]) with radii [rx], [ry] (long-edge fractions) turned [rotation] degrees
     * clockwise; [feather] 0..100 is the transition inside the edge; [inside] = the effect inside.
     */
    @Serializable
    data class Radial(
        @SerialName("type") override val type: String = TYPE,
        @SerialName("op") override val op: MaskOp = MaskOp.ADD,
        @SerialName("invert") override val invert: Boolean = false,
        @SerialName("cx") val cx: Float = HALF,
        @SerialName("cy") val cy: Float = HALF,
        @SerialName("rx") val rx: Float = QUARTER,
        @SerialName("ry") val ry: Float = QUARTER,
        @SerialName("rotation") val rotation: Float = 0f,
        @SerialName("feather") val feather: Float = HALF * 100f,
        @SerialName("inside") val inside: Boolean = true,
    ) : MaskComponent() {
        init {
            requireFinite(cx, cy, rx, ry, rotation, feather)
        }

        companion object {
            const val TYPE: String = "radial"
        }
    }

    /** Brush strokes, painted and erased in order (vector strokes, never pixels). */
    @Serializable
    data class Brush(
        @SerialName("type") override val type: String = TYPE,
        @SerialName("op") override val op: MaskOp = MaskOp.ADD,
        @SerialName("invert") override val invert: Boolean = false,
        @SerialName("strokes") val strokes: List<BrushStroke> = emptyList(),
    ) : MaskComponent() {
        companion object {
            const val TYPE: String = "brush"
        }
    }

    /**
     * The colours near [samples] (OkLab, picked on the image before local adjustments); [range] 0..100 widens the
     * selection, [smoothness] 0..100 softens its edge.
     */
    @Serializable
    data class ColourRange(
        @SerialName("type") override val type: String = TYPE,
        @SerialName("op") override val op: MaskOp = MaskOp.ADD,
        @SerialName("invert") override val invert: Boolean = false,
        @SerialName("samples") val samples: List<LabColour> = emptyList(),
        @SerialName("range") val range: Float = HALF * 100f,
        @SerialName("smoothness") val smoothness: Float = HALF * 100f,
    ) : MaskComponent() {
        init {
            requireFinite(range, smoothness)
            require(samples.size <= MAX_SAMPLES) { "at most $MAX_SAMPLES colour samples" }
        }

        companion object {
            const val TYPE: String = "colourRange"
            const val MAX_SAMPLES: Int = 5
        }
    }

    /** The lightness band [low]..[high] (OkLab L in percent) with a soft [falloff] (percent) on both sides. */
    @Serializable
    data class LuminanceRange(
        @SerialName("type") override val type: String = TYPE,
        @SerialName("op") override val op: MaskOp = MaskOp.ADD,
        @SerialName("invert") override val invert: Boolean = false,
        @SerialName("low") val low: Float = HALF * 100f,
        @SerialName("high") val high: Float = FULL,
        @SerialName("falloff") val falloff: Float = DEFAULT_FALLOFF,
    ) : MaskComponent() {
        init {
            requireFinite(low, high, falloff)
        }

        companion object {
            const val TYPE: String = "luminanceRange"
            private const val DEFAULT_FALLOFF = 10f
        }
    }

    /**
     * An on-device model's mask (docs/adr/0021 "Masks in the recipe"): [model] = `<id>@<version>`, [kind] the model's
     * kind name (`SKY`, `HAIR`, …), [prompts] the taps of interactive models, [refine] the edge refinement. The engine
     * never runs the model: it asks an `AiMaskSource` (docs/adr/0022).
     */
    @Serializable
    data class Ai(
        @SerialName("type") override val type: String = TYPE,
        @SerialName("op") override val op: MaskOp = MaskOp.ADD,
        @SerialName("invert") override val invert: Boolean = false,
        @SerialName("model") val model: String = "",
        @SerialName("kind") val kind: String = "",
        @SerialName("prompts") val prompts: List<MaskPoint> = emptyList(),
        @SerialName("refine") val refine: AiRefine? = null,
        /**
         * Interactive models (the subject): whether the automatic seeds (faces, body, saliency peaks) are used with
         * the [prompts] (true) or the taps alone (false: a "subtract this object" component). M4/B, additive.
         */
        @SerialName("autoSeeds") val autoSeeds: Boolean = true,
    ) : MaskComponent() {
        companion object {
            const val TYPE: String = "ai"
        }
    }

    /** A component of a type this version does not know: kept as written, contributes nothing (coverage 0). */
    @Serializable(with = UnknownComponentSerializer::class)
    data class Unknown(val json: JsonObject) : MaskComponent() {
        override val type: String get() = (json["type"] as? JsonPrimitive)?.content.orEmpty()
        override val op: MaskOp get() = MaskOp.ADD
        override val invert: Boolean get() = false
    }

    private companion object {
        const val HALF = 0.5f
        const val QUARTER = 0.25f
        const val FULL = 100f

        fun requireFinite(vararg values: Float) {
            require(values.all { it.isFinite() }) { "mask component values must be finite" }
        }
    }
}

/** An OkLab colour (L in 0..1). */
@Serializable
data class LabColour(@SerialName("l") val l: Float, @SerialName("a") val a: Float, @SerialName("b") val b: Float) {
    init {
        require(l.isFinite() && a.isFinite() && b.isFinite()) { "colour must be finite" }
    }
}

/** A point in the upright image (fractions of width and height, y down). */
@Serializable
data class MaskPoint(@SerialName("x") val x: Float, @SerialName("y") val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "point must be finite" }
    }
}

/** Edge refinement of an AI mask (docs/adr/0021, "Edge refinement"): guided filter radius (pixels) and epsilon. */
@Serializable
data class AiRefine(@SerialName("radius") val radius: Float = 8f, @SerialName("eps") val eps: Float = 0.001f)

/**
 * One brush stroke: [points] in the compact encoding of [StrokeCodec] (decode with [decoded]), [size] the dab diameter
 * as a fraction of the image's long edge, [feather] 0..100 the soft part of a dab's radius, [flow] 0..100 how much one
 * dab adds, [erase] removes coverage instead (docs/adr/0022, "Brush raster").
 *
 * M4/B (docs/adr/0022 "M4/B (editor)") added [density] (0..100, the most coverage the stroke paints towards: a painting
 * dab moves the coverage towards `density / 100` instead of 1, an erasing dab removes at most that share; 100 = the
 * M4/A behaviour) and [autoMask] (the brush's auto-mask: each dab is weighted by the colour similarity to the dab's
 * centre on the pre-local image, like the range masks) and [pressure] (what pen pressure drives: [StrokePressure];
 * the editor writes [StrokePressure.FLOW] or [StrokePressure.SIZE], strokes without the field keep M4/A's both). All
 * three are additive: older strokes read 100, false and [StrokePressure.BOTH].
 */
@Serializable
data class BrushStroke(
    @SerialName("points") val points: String = "",
    @SerialName("size") val size: Float = DEFAULT_SIZE,
    @SerialName("feather") val feather: Float = DEFAULT_FEATHER,
    @SerialName("flow") val flow: Float = FULL,
    @SerialName("erase") val erase: Boolean = false,
    @SerialName("density") val density: Float = FULL,
    @SerialName("autoMask") val autoMask: Boolean = false,
    @SerialName("pressure") val pressure: StrokePressure = StrokePressure.BOTH,
) {
    init {
        require(size.isFinite() && feather.isFinite() && flow.isFinite() && density.isFinite()) {
            "stroke values must be finite"
        }
    }

    @Transient
    private val cache: Lazy<List<StrokePoint>> = lazy { StrokeCodec.decode(points) }

    /** The stroke's points (decoded once). */
    val decoded: List<StrokePoint> get() = cache.value

    companion object {
        private const val DEFAULT_SIZE = 0.05f
        private const val DEFAULT_FEATHER = 50f
        private const val FULL = 100f

        /**
         * A finished stroke from raw [points]: simplified ([StrokeCodec.simplify], tolerance relative to the dab
         * radius) and encoded. While a stroke is being drawn use [raw] instead, so the dabs only grow.
         */
        @Suppress("LongParameterList")
        fun of(
            points: List<StrokePoint>,
            size: Float = DEFAULT_SIZE,
            feather: Float = DEFAULT_FEATHER,
            flow: Float = FULL,
            erase: Boolean = false,
            aspect: Float = 1f,
            density: Float = FULL,
            autoMask: Boolean = false,
            pressure: StrokePressure = StrokePressure.BOTH,
        ): BrushStroke = BrushStroke(
            StrokeCodec.encode(StrokeCodec.simplify(points, size * 0.5f, aspect)),
            size,
            feather,
            flow,
            erase,
            density,
            autoMask,
            pressure,
        )

        /** A stroke from [points] as they are (quantised, not simplified): the stroke in progress. */
        @Suppress("LongParameterList")
        fun raw(
            points: List<StrokePoint>,
            size: Float = DEFAULT_SIZE,
            feather: Float = DEFAULT_FEATHER,
            flow: Float = FULL,
            erase: Boolean = false,
            density: Float = FULL,
            autoMask: Boolean = false,
            pressure: StrokePressure = StrokePressure.BOTH,
        ): BrushStroke =
            BrushStroke(StrokeCodec.encode(points), size, feather, flow, erase, density, autoMask, pressure)
    }
}

/**
 * What a stroke's pen pressure `p` scales, through `k(p) = 0.25 + 0.75 p` (docs/adr/0022 "Brush raster"): the dab
 * radius and flow ([BOTH], M4/A), the flow only ([FLOW], the editor's default) or the radius only ([SIZE]). A finger or
 * a mouse sends `p = 1`, so `k = 1` and the choice changes nothing.
 */
@Serializable
enum class StrokePressure {
    @SerialName("both")
    BOTH,

    @SerialName("flow")
    FLOW,

    @SerialName("size")
    SIZE,
}

/** A stroke point: position (fractions of width and height) and pen pressure 0..1 (1 for a finger or mouse). */
data class StrokePoint(val x: Float, val y: Float, val pressure: Float = 1f)

/**
 * A mask's local adjustments (docs/adr/0022, "Local adjustments"): each is the global operator it mirrors at a local
 * amount, added to the global value where the mask covers. Every default is 0 (no change). Ranges in
 * [LocalAdjustment].
 */
@Serializable
data class LocalAdjustments(
    /** Relative temperature, −100..+100 (positive = warmer), like the display-referred white balance. */
    @SerialName("temperature") val temperature: Float = 0f,
    /** Relative tint, −100..+100 (positive = magenta). */
    @SerialName("tint") val tint: Float = 0f,
    /** Exposure in stops, −4..+4. */
    @SerialName("exposure") val exposure: Float = 0f,
    @SerialName("contrast") val contrast: Float = 0f,
    @SerialName("highlights") val highlights: Float = 0f,
    @SerialName("shadows") val shadows: Float = 0f,
    @SerialName("whites") val whites: Float = 0f,
    @SerialName("blacks") val blacks: Float = 0f,
    @SerialName("texture") val texture: Float = 0f,
    @SerialName("clarity") val clarity: Float = 0f,
    @SerialName("dehaze") val dehaze: Float = 0f,
    /** Hue shift in degrees, −180..+180. */
    @SerialName("hue") val hue: Float = 0f,
    @SerialName("saturation") val saturation: Float = 0f,
    /** Sharpness, −100..+100: positive sharpens with the global radius/detail/masking, negative softens. */
    @SerialName("sharpness") val sharpness: Float = 0f,
    /** Luminance noise reduction, −100..+100, added to the global amount. */
    @SerialName("noise") val noise: Float = 0f,
    /** The colour tint swatch: hue in degrees 0..360 … */
    @SerialName("colourHue") val colourHue: Float = 0f,
    /** … and its strength 0..100 (0 = no tint). */
    @SerialName("colourSaturation") val colourSaturation: Float = 0f,
) {
    init {
        require(LocalAdjustment.entries.all { get(it).isFinite() }) { "local adjustments must be finite" }
    }

    /** The value of [adjustment]. */
    @Suppress("CyclomaticComplexMethod")
    operator fun get(adjustment: LocalAdjustment): Float = when (adjustment) {
        LocalAdjustment.TEMPERATURE -> temperature
        LocalAdjustment.TINT -> tint
        LocalAdjustment.EXPOSURE -> exposure
        LocalAdjustment.CONTRAST -> contrast
        LocalAdjustment.HIGHLIGHTS -> highlights
        LocalAdjustment.SHADOWS -> shadows
        LocalAdjustment.WHITES -> whites
        LocalAdjustment.BLACKS -> blacks
        LocalAdjustment.TEXTURE -> texture
        LocalAdjustment.CLARITY -> clarity
        LocalAdjustment.DEHAZE -> dehaze
        LocalAdjustment.HUE -> hue
        LocalAdjustment.SATURATION -> saturation
        LocalAdjustment.SHARPNESS -> sharpness
        LocalAdjustment.NOISE -> noise
        LocalAdjustment.COLOUR_HUE -> colourHue
        LocalAdjustment.COLOUR_SATURATION -> colourSaturation
    }

    /** A copy with [adjustment] set to [value] (not clamped: the operators clamp). */
    @Suppress("CyclomaticComplexMethod")
    fun with(adjustment: LocalAdjustment, value: Float): LocalAdjustments = when (adjustment) {
        LocalAdjustment.TEMPERATURE -> copy(temperature = value)
        LocalAdjustment.TINT -> copy(tint = value)
        LocalAdjustment.EXPOSURE -> copy(exposure = value)
        LocalAdjustment.CONTRAST -> copy(contrast = value)
        LocalAdjustment.HIGHLIGHTS -> copy(highlights = value)
        LocalAdjustment.SHADOWS -> copy(shadows = value)
        LocalAdjustment.WHITES -> copy(whites = value)
        LocalAdjustment.BLACKS -> copy(blacks = value)
        LocalAdjustment.TEXTURE -> copy(texture = value)
        LocalAdjustment.CLARITY -> copy(clarity = value)
        LocalAdjustment.DEHAZE -> copy(dehaze = value)
        LocalAdjustment.HUE -> copy(hue = value)
        LocalAdjustment.SATURATION -> copy(saturation = value)
        LocalAdjustment.SHARPNESS -> copy(sharpness = value)
        LocalAdjustment.NOISE -> copy(noise = value)
        LocalAdjustment.COLOUR_HUE -> copy(colourHue = value)
        LocalAdjustment.COLOUR_SATURATION -> copy(colourSaturation = value)
    }

    /** True when nothing changes (the colour tint counts only with a saturation). */
    val isNeutral: Boolean
        get() = LocalAdjustment.entries.all { it == LocalAdjustment.COLOUR_HUE || get(it) == 0f }
}

/** The local adjustments with their slider range (the editor's sliders, the operators' clamp). */
enum class LocalAdjustment(val min: Float, val max: Float) {
    TEMPERATURE(-100f, 100f),
    TINT(-100f, 100f),
    EXPOSURE(-4f, 4f),
    CONTRAST(-100f, 100f),
    HIGHLIGHTS(-100f, 100f),
    SHADOWS(-100f, 100f),
    WHITES(-100f, 100f),
    BLACKS(-100f, 100f),
    TEXTURE(-100f, 100f),
    CLARITY(-100f, 100f),
    DEHAZE(-100f, 100f),
    HUE(-180f, 180f),
    SATURATION(-100f, 100f),
    SHARPNESS(-100f, 100f),
    NOISE(-100f, 100f),
    COLOUR_HUE(0f, 360f),
    COLOUR_SATURATION(0f, 100f),
    ;

    /** [value] clamped to the range. */
    fun clamp(value: Float): Float = value.coerceIn(min, max)
}

/** Picks a component's serializer by its `type`; unknown types become [MaskComponent.Unknown]. */
internal object MaskComponentSerializer : JsonContentPolymorphicSerializer<MaskComponent>(MaskComponent::class) {
    override fun selectDeserializer(element: JsonElement) =
        when ((element.jsonObject["type"] as? JsonPrimitive)?.content) {
            MaskComponent.Linear.TYPE -> MaskComponent.Linear.serializer()
            MaskComponent.Radial.TYPE -> MaskComponent.Radial.serializer()
            MaskComponent.Brush.TYPE -> MaskComponent.Brush.serializer()
            MaskComponent.ColourRange.TYPE -> MaskComponent.ColourRange.serializer()
            MaskComponent.LuminanceRange.TYPE -> MaskComponent.LuminanceRange.serializer()
            MaskComponent.Ai.TYPE -> MaskComponent.Ai.serializer()
            else -> UnknownComponentSerializer
        }
}

/** [MaskComponent.Unknown] as its JSON object, unchanged. */
internal object UnknownComponentSerializer : KSerializer<MaskComponent.Unknown> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): MaskComponent.Unknown =
        MaskComponent.Unknown((decoder as JsonDecoder).decodeJsonElement().jsonObject)

    override fun serialize(encoder: Encoder, value: MaskComponent.Unknown) {
        (encoder as JsonEncoder).encodeJsonElement(value.json)
    }
}
