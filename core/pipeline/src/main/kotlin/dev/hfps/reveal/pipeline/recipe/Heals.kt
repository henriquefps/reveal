@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.recipe

import dev.hfps.reveal.pipeline.mask.StrokeCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One healing spot (docs/adr/0023): a [kind] of repair on a target shape in upright image coordinates before crop and
 * transform (like masks: positions are fractions of the image's width and height, lengths of its long edge), with the
 * pixels it takes coming from the same shape moved by ([dx], [dy]).
 *
 * The target is a circle at ([x], [y]) of [radius], or, when [stroke] is not empty, a brush stroke: its points in
 * [StrokeCodec]'s form, painted with dabs of [radius] (scaled by the pen pressure, `k(p)` of the mask brush).
 * [radius] 0 renders nothing, so the `{"id": "…"}` placeholder written before M5 reads as a spot that changes no pixel.
 *
 * Every field is additive; a [kind] or [method] this version does not know is kept as written and renders nothing
 * (kind) or with [METHOD_PATCH] (method).
 */
@Serializable
data class Heal(
    /** Stable identifier within the recipe (a uuid): history and the editor find a spot by it. */
    @SerialName("id") val id: String = "",
    /** [KIND_HEAL], [KIND_CLONE], [KIND_REMOVE] or [KIND_RED_EYE] ([type] reads it). */
    @SerialName("kind") val kind: String = KIND_HEAL,
    /** Circle centre (fraction of the width); for a stroke, unused (the points say where it is). */
    @SerialName("x") val x: Float = 0.5f,
    /** Circle centre (fraction of the height). */
    @SerialName("y") val y: Float = 0.5f,
    /** Radius of the circle, or of a stroke's dabs, as a fraction of the long edge. */
    @SerialName("radius") val radius: Float = 0f,
    /** The target as a brush stroke ([StrokeCodec]); empty = the circle. */
    @SerialName("stroke") val stroke: String = "",
    /** Source offset, fraction of the width: the pixels at `target + (dx, dy)` repair the target. */
    @SerialName("dx") val dx: Float = 0f,
    /** Source offset, fraction of the height. */
    @SerialName("dy") val dy: Float = 0f,
    /** True while the source is the automatic pick (the editor's patch search); dragging it sets false. */
    @SerialName("autoSource") val autoSource: Boolean = true,
    /** Soft part of the edge, 0..100 (percent of the radius). */
    @SerialName("feather") val feather: Float = DEFAULT_FEATHER,
    /** Strength, 0..100. */
    @SerialName("opacity") val opacity: Float = FULL,
    /**
     * How [KIND_REMOVE] fills its region: [METHOD_PATCH] (patch-based, docs/adr/0023 "Remove"), or
     * `model:<id>@<version>` for an on-device inpainting model (M5/R). Ignored by the other kinds.
     */
    @SerialName("method") val method: String = METHOD_PATCH,
    /** [KIND_RED_EYE]: how much the pupil is darkened, 0..100. */
    @SerialName("darken") val darken: Float = DEFAULT_DARKEN,
) {
    init {
        require(listOf(x, y, radius, dx, dy, feather, opacity, darken).all { it.isFinite() }) {
            "heal values must be finite"
        }
        require(radius >= 0f) { "heal radius must not be negative" }
    }

    @Transient
    private val points: Lazy<List<StrokePoint>> = lazy { StrokeCodec.decode(stroke) }

    /** The kind, [HealKind.UNKNOWN] for a kind this version does not know. */
    val type: HealKind get() = HealKind.of(kind)

    /** True when the target is a brush stroke. */
    val isStroke: Boolean get() = stroke.isNotEmpty()

    /** The stroke's points (decoded once); empty for a circle. */
    val strokePoints: List<StrokePoint> get() = points.value

    /** True when the spot changes pixels: a known kind, a size, an opacity, and points when it is a stroke. */
    val renders: Boolean
        get() = type != HealKind.UNKNOWN && radius > 0f && opacity > 0f && (!isStroke || strokePoints.isNotEmpty())

    /** True when the kind reads pixels from a source ([dx], [dy] matter). */
    val hasSource: Boolean get() = type == HealKind.HEAL || type == HealKind.CLONE

    companion object {
        const val KIND_HEAL: String = "heal"
        const val KIND_CLONE: String = "clone"
        const val KIND_REMOVE: String = "remove"
        const val KIND_RED_EYE: String = "redEye"

        /** The patch-based fill of [KIND_REMOVE] (docs/adr/0023 "Remove"). */
        const val METHOD_PATCH: String = "patch"

        /** Prefix of an inpainting model's method: `model:<id>@<version>` (M5/R). */
        const val METHOD_MODEL_PREFIX: String = "model:"

        const val DEFAULT_FEATHER: Float = 50f
        const val DEFAULT_DARKEN: Float = 50f
        private const val FULL = 100f

        /** At most this many spots per recipe (the editor's limit; the engine renders any number). */
        const val MAX_HEALS: Int = 500
    }
}

/** The kinds of healing spot (docs/adr/0023). */
enum class HealKind(val key: String) {
    /** Seamless repair: the source's texture blended into the target's surroundings (gradient domain). */
    HEAL(Heal.KIND_HEAL),

    /** A copy of the source with a feathered edge. */
    CLONE(Heal.KIND_CLONE),

    /** Content-aware fill of the region from the rest of the picture (patch-based, or a model). */
    REMOVE(Heal.KIND_REMOVE),

    /** The red pupil inside the circle is found, desaturated and darkened. */
    RED_EYE(Heal.KIND_RED_EYE),

    /** A kind from a newer app: kept, renders nothing. */
    UNKNOWN(""),
    ;

    companion object {
        /** The kind of [key], [UNKNOWN] when this version does not know it. */
        fun of(key: String): HealKind = entries.firstOrNull { it != UNKNOWN && it.key == key } ?: UNKNOWN
    }
}
