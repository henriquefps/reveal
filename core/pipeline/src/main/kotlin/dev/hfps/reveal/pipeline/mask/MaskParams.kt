@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.mask

import dev.hfps.reveal.pipeline.cpu.GradingParams
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges

/**
 * The local adjustments of one recipe, resolved once on the CPU and shared by the CPU reference and the GPU (docs/adr/
 * 0022, "Local adjustments"). Independent of the render size (the shapes are packed per size by [MaskPlan]).
 *
 * @property masks the rendered masks in order: enabled, a non-zero amount and at least one adjustment; at most
 *   [MAX_MASKS]. Mask `i` has coverage slot `i`.
 * @property adjust per mask [VECTOR] floats: the adjustments clamped to their ranges times `amount / 100`, laid out as
 *   the shader's `vec4[5]` (see the `V_` indices).
 * @property global the global slider values the totals add to (see the `G_` indices), clamped to their ranges.
 * @property needs `NEED_` bits: which operators a mask changes somewhere.
 */
class MaskParams(val masks: List<Mask>, val adjust: FloatArray, val global: FloatArray, val needs: Int) {
    /** True when any mask renders (the masked paths run). */
    val active: Boolean get() = masks.isNotEmpty()

    /** True when [need] (a `NEED_` bit) is set. */
    fun needs(need: Int): Boolean = needs and need != 0

    /**
     * These parameters without what slots 11 and 12 read (texture, clarity, dehaze, sharpness, noise): what the develop
     * pass depends on, so a drag of those keeps the develop output (as a global Detail or Effects drag does).
     */
    fun withoutDetail(): MaskParams {
        if (!active) return this
        val a = adjust.copyOf()
        for (m in masks.indices) for (v in DETAIL_VECTOR) a[m * VECTOR + v] = 0f
        val g = global.copyOf()
        for (i in DETAIL_GLOBALS) g[i] = 0f
        // The masks themselves without those adjustments too: the develop output does not depend on them (a key).
        val developMasks = masks.map {
            it.copy(
                adjustments = it.adjustments.copy(texture = 0f, clarity = 0f, dehaze = 0f, sharpness = 0f, noise = 0f),
            )
        }
        return MaskParams(developMasks, a, g, needs and DETAIL_NEEDS.inv())
    }

    /** Every rendered mask's noise reduction value and the global amount it adds to (Detail's luminance key). */
    fun noiseColumn(): List<Float> = masks.indices.map { adjust[it * VECTOR + V_NOISE] } + global[G_NOISE]

    /** True when a mask's shape reads the image before local adjustments (a colour or luminance range). */
    val needsPre: Boolean get() = masks.any { MaskPlan.readsPre(it) }

    override fun equals(other: Any?): Boolean = other is MaskParams && masks == other.masks &&
        adjust.contentEquals(other.adjust) && global.contentEquals(other.global) && needs == other.needs

    override fun hashCode(): Int =
        ((masks.hashCode() * HASH + adjust.contentHashCode()) * HASH + global.contentHashCode()) * HASH + needs

    companion object {
        private const val HASH = 31
        private const val PERCENT = 100f

        /** Most masks one render applies (the shader's uniform arrays); the editor keeps the list at or below it. */
        const val MAX_MASKS: Int = 32

        /** Floats of one mask's adjustment vector (five vec4). */
        const val VECTOR: Int = 20

        // Indices of the adjustment vector.
        const val V_TEMPERATURE = 0
        const val V_TINT = 1
        const val V_EXPOSURE = 2
        const val V_CONTRAST = 3
        const val V_HIGHLIGHTS = 4
        const val V_SHADOWS = 5
        const val V_WHITES = 6
        const val V_BLACKS = 7
        const val V_TEXTURE = 8
        const val V_CLARITY = 9
        const val V_DEHAZE = 10
        const val V_SATURATION = 11
        const val V_HUE = 12
        const val V_SHARPNESS = 13
        const val V_NOISE = 14
        const val V_TINT_A = 16
        const val V_TINT_B = 17

        /** Floats of [global] (three vec4). */
        const val GLOBALS: Int = 12

        // Indices of the global values.
        const val G_CONTRAST = 0
        const val G_HIGHLIGHTS = 1
        const val G_SHADOWS = 2
        const val G_WHITES = 3
        const val G_BLACKS = 4
        const val G_TEXTURE = 5
        const val G_CLARITY = 6
        const val G_DEHAZE = 7
        const val G_SHARPEN = 8
        const val G_NOISE = 9

        // What the masks change somewhere.
        const val NEED_WB = 1
        const val NEED_EXPOSURE = 2
        const val NEED_TONE_LOCAL = 4
        const val NEED_TONE_GLOBAL = 8
        const val NEED_COLOUR = 16
        const val NEED_TEXTURE = 32
        const val NEED_CLARITY = 64
        const val NEED_DEHAZE = 128
        const val NEED_SHARPEN = 256
        const val NEED_NOISE = 512

        private val DETAIL_VECTOR = intArrayOf(V_TEXTURE, V_CLARITY, V_DEHAZE, V_SHARPNESS, V_NOISE)
        private val DETAIL_GLOBALS = intArrayOf(G_TEXTURE, G_CLARITY, G_DEHAZE, G_SHARPEN, G_NOISE)
        private const val DETAIL_NEEDS = NEED_TEXTURE or NEED_CLARITY or NEED_DEHAZE or NEED_SHARPEN or NEED_NOISE

        /** No mask renders. */
        val NONE: MaskParams = MaskParams(emptyList(), FloatArray(0), FloatArray(GLOBALS), 0)

        /** True when [mask] renders: enabled, a non-zero amount, at least one adjustment, at least one component. */
        fun renders(mask: Mask): Boolean =
            mask.enabled && mask.amount > 0f && !mask.adjustments.isNeutral && mask.components.isNotEmpty()

        /** Resolves [recipe]'s masks. */
        fun from(recipe: Recipe): MaskParams {
            val masks = recipe.masks.filter(::renders).take(MAX_MASKS)
            if (masks.isEmpty()) return NONE
            val adjust = FloatArray(masks.size * VECTOR)
            var needs = 0
            for ((i, mask) in masks.withIndex()) {
                val v = vector(mask)
                v.copyInto(adjust, i * VECTOR)
                needs = needs or needsOf(v)
            }
            return MaskParams(masks, adjust, globals(recipe), needs)
        }

        /** One mask's adjustment vector: each adjustment clamped to its range, times `amount / 100`. */
        fun vector(mask: Mask): FloatArray {
            val a = mask.adjustments
            val k = (mask.amount / PERCENT).coerceIn(0f, 1f)
            fun v(adjustment: LocalAdjustment) = adjustment.clamp(a[adjustment]) * k
            val out = FloatArray(VECTOR)
            out[V_TEMPERATURE] = v(LocalAdjustment.TEMPERATURE)
            out[V_TINT] = v(LocalAdjustment.TINT)
            out[V_EXPOSURE] = v(LocalAdjustment.EXPOSURE)
            out[V_CONTRAST] = v(LocalAdjustment.CONTRAST)
            out[V_HIGHLIGHTS] = v(LocalAdjustment.HIGHLIGHTS)
            out[V_SHADOWS] = v(LocalAdjustment.SHADOWS)
            out[V_WHITES] = v(LocalAdjustment.WHITES)
            out[V_BLACKS] = v(LocalAdjustment.BLACKS)
            out[V_TEXTURE] = v(LocalAdjustment.TEXTURE)
            out[V_CLARITY] = v(LocalAdjustment.CLARITY)
            out[V_DEHAZE] = v(LocalAdjustment.DEHAZE)
            out[V_SATURATION] = v(LocalAdjustment.SATURATION)
            out[V_HUE] = v(LocalAdjustment.HUE)
            out[V_SHARPNESS] = v(LocalAdjustment.SHARPNESS)
            out[V_NOISE] = v(LocalAdjustment.NOISE)
            // The colour tint as colour grading's global wheel: TINT sat (cos hue, sin hue), computed in double.
            val sat = LocalAdjustment.COLOUR_SATURATION.clamp(a.colourSaturation) / PERCENT * GradingParams.TINT
            val hue = Math.toRadians(a.colourHue.toDouble())
            out[V_TINT_A] = (sat * StrictMath.cos(hue)).toFloat() * k
            out[V_TINT_B] = (sat * StrictMath.sin(hue)).toFloat() * k
            return out
        }

        private fun needsOf(v: FloatArray): Int {
            var n = 0
            fun on(vararg indices: Int, need: Int) {
                if (indices.any { v[it] != 0f }) n = n or need
            }
            on(V_TEMPERATURE, V_TINT, need = NEED_WB)
            on(V_EXPOSURE, need = NEED_EXPOSURE)
            on(V_HIGHLIGHTS, V_SHADOWS, need = NEED_TONE_LOCAL)
            on(V_CONTRAST, V_WHITES, V_BLACKS, need = NEED_TONE_GLOBAL)
            on(V_SATURATION, V_HUE, V_TINT_A, V_TINT_B, need = NEED_COLOUR)
            on(V_TEXTURE, need = NEED_TEXTURE)
            on(V_CLARITY, need = NEED_CLARITY)
            on(V_DEHAZE, need = NEED_DEHAZE)
            on(V_SHARPNESS, need = NEED_SHARPEN)
            on(V_NOISE, need = NEED_NOISE)
            return n
        }

        /** The global slider values the totals add to, clamped to their ranges. */
        private fun globals(recipe: Recipe): FloatArray {
            val r = RecipeRanges
            val g = FloatArray(GLOBALS)
            g[G_CONTRAST] = r.contrast.clamp(recipe.light.contrast)
            g[G_HIGHLIGHTS] = r.highlights.clamp(recipe.light.highlights)
            g[G_SHADOWS] = r.shadows.clamp(recipe.light.shadows)
            g[G_WHITES] = r.whites.clamp(recipe.light.whites)
            g[G_BLACKS] = r.blacks.clamp(recipe.light.blacks)
            g[G_TEXTURE] = r.texture.clamp(recipe.effects.texture)
            g[G_CLARITY] = r.clarity.clamp(recipe.effects.clarity)
            g[G_DEHAZE] = r.dehaze.clamp(recipe.effects.dehaze)
            g[G_SHARPEN] = r.sharpenAmount.clamp(recipe.detail.sharpenAmount)
            g[G_NOISE] = r.nrLuminance.clamp(recipe.detail.nrLuminance)
            return g
        }
    }
}

/** Small maths shared by the CPU reference and the GLSL of the masks (mirrored in `gl/MaskShaders.kt`). */
object MaskMath {
    /** Smallest normal float16: coverage below it is stored as 0 (a float16 texture flushes it there too). */
    const val MIN_STORED: Float = 6.1035156e-5f

    /** GLSL `smoothstep`, with `e0 >= e1` read as a step at [e1] (feather 0: a hard edge). */
    fun sstep(e0: Float, e1: Float, x: Float): Float {
        if (e0 >= e1) return if (x < e1) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A coverage as stored: clamped to 0..1, float16-rounded, values below [MIN_STORED] flushed to 0. */
    fun store(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c < MIN_STORED) 0f else dev.hfps.reveal.pipeline.cpu.Half.round(c)
    }
}
