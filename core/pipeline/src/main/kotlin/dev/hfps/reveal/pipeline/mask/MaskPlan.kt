@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.mask

import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The masks' shapes packed for an image of [imageWidth] x [imageHeight] pixels (the image being rendered: the proxy,
 * or the full-resolution image of the zoom tiles and the export), shared by the CPU reference ([MaskRaster]) and the
 * GPU (`gl/MaskPasses`): [masks] in coverage-slot order, each with its [components] packed into numbers in that image's
 * pixels, the brush components' dabs ([brushes], one raster layer each) and the AI components ([ai], one texture layer
 * each). docs/adr/0022, "Coverage".
 */
class MaskPlan private constructor(
    val imageWidth: Int,
    val imageHeight: Int,
    val masks: List<Mask>,
    val components: List<List<PackedComponent>>,
    val brushes: List<Dabs>,
    val ai: List<MaskComponent.Ai>,
) {
    /** True when some component reads the image before local adjustments (a range, or an auto-mask brush). */
    val needsPre: Boolean
        get() = components.any { list -> list.any { it.type == TYPE_COLOUR || it.type == TYPE_LUMA } } ||
            brushes.any { it.autoMask }

    /** Coverage layers (four masks each). */
    val layers: Int get() = (masks.size + SLOTS - 1) / SLOTS

    /**
     * A component as numbers: [type] (`TYPE_`), [op] (0 add, 1 subtract, 2 intersect), [invert], [layer] (its brush or
     * AI layer), [data] ([DATA] floats, meaning per type: see [MaskRaster]).
     */
    class PackedComponent(val type: Int, val op: Int, val invert: Boolean, val layer: Int, val data: FloatArray)

    companion object {
        const val SLOTS: Int = 4
        const val DATA: Int = 20

        /** Most components of one mask the GPU evaluates (the editor keeps masks at or below it). */
        const val MAX_COMPONENTS: Int = 16

        const val TYPE_SKIP = 0
        const val TYPE_LINEAR = 1
        const val TYPE_RADIAL = 2
        const val TYPE_BRUSH = 3
        const val TYPE_COLOUR = 4
        const val TYPE_LUMA = 5
        const val TYPE_AI = 6

        private const val PERCENT = 100f
        private const val MIN_RADIUS = 1e-6f

        /**
         * True when [mask] has a colour or luminance range component or an auto-mask brush stroke (it reads the image
         * before local adjustments).
         */
        fun readsPre(mask: Mask): Boolean = mask.components.take(MAX_COMPONENTS).any { c ->
            c is MaskComponent.ColourRange || c is MaskComponent.LuminanceRange ||
                (c is MaskComponent.Brush && c.strokes.any { it.autoMask })
        }

        /**
         * Packs [masks] (coverage slots in order) for an [imageWidth] x [imageHeight] image; [dabsOf] makes a brush's
         * dabs (the preview keeps them across frames: a stroke in progress then recomputes only its own).
         */
        fun of(
            masks: List<Mask>,
            imageWidth: Int,
            imageHeight: Int,
            dabsOf: (List<BrushStroke>) -> Dabs = { Dabs.of(it, imageWidth, imageHeight) },
        ): MaskPlan {
            val brushes = ArrayList<Dabs>()
            val ai = ArrayList<MaskComponent.Ai>()
            val components = masks.map { mask ->
                mask.components.take(MAX_COMPONENTS).map { c ->
                    pack(c, imageWidth, imageHeight, brushes, ai, dabsOf)
                }
            }
            return MaskPlan(imageWidth, imageHeight, masks, components, brushes, ai)
        }

        private fun opOf(op: MaskOp): Int = when (op) {
            MaskOp.ADD -> 0
            MaskOp.SUBTRACT -> 1
            MaskOp.INTERSECT -> 2
        }

        @Suppress("LongMethod", "CyclomaticComplexMethod")
        private fun pack(
            c: MaskComponent,
            w: Int,
            h: Int,
            brushes: MutableList<Dabs>,
            ai: MutableList<MaskComponent.Ai>,
            dabsOf: (List<BrushStroke>) -> Dabs,
        ): PackedComponent {
            val d = FloatArray(DATA)
            val long = max(w, h).toFloat()
            val op = opOf(c.op)
            return when (c) {
                is MaskComponent.Linear -> {
                    // t = (q - p0) . k with k = (p1 - p0) / |p1 - p0|^2, in pixels.
                    val x0 = c.x0 * w
                    val y0 = c.y0 * h
                    val dx = c.x1 * w - x0
                    val dy = c.y1 * h - y0
                    val len2 = dx * dx + dy * dy
                    val f = (c.feather / PERCENT).coerceIn(0f, 1f)
                    d[0] = x0
                    d[1] = y0
                    d[2] = if (len2 > 0f) dx / len2 else 0f
                    d[3] = if (len2 > 0f) dy / len2 else 0f
                    d[4] = 0.5f - f * 0.5f
                    d[5] = 0.5f + f * 0.5f
                    PackedComponent(if (len2 > 0f) TYPE_LINEAR else TYPE_SKIP, op, c.invert, 0, d)
                }

                is MaskComponent.Radial -> {
                    val theta = Math.toRadians(c.rotation.toDouble())
                    d[0] = c.cx * w
                    d[1] = c.cy * h
                    d[2] = cos(theta).toFloat()
                    d[3] = sin(theta).toFloat()
                    d[4] = 1f / max(c.rx * long, MIN_RADIUS)
                    d[5] = 1f / max(c.ry * long, MIN_RADIUS)
                    d[6] = 1f - (c.feather / PERCENT).coerceIn(0f, 1f)
                    d[7] = if (c.inside) 1f else 0f
                    PackedComponent(TYPE_RADIAL, op, c.invert, 0, d)
                }

                is MaskComponent.Brush -> {
                    brushes += dabsOf(c.strokes)
                    PackedComponent(TYPE_BRUSH, op, c.invert, brushes.lastIndex, d)
                }

                is MaskComponent.ColourRange -> {
                    val r = COLOUR_MIN + (COLOUR_MAX - COLOUR_MIN) * (c.range / PERCENT).coerceIn(0f, 1f)
                    val s = (c.smoothness / PERCENT).coerceIn(0f, 1f)
                    d[0] = c.samples.size.toFloat()
                    d[1] = r * (1f - s)
                    d[2] = r
                    for ((i, sample) in c.samples.take(MaskComponent.ColourRange.MAX_SAMPLES).withIndex()) {
                        d[3 + 3 * i] = sample.l
                        d[4 + 3 * i] = sample.a
                        d[5 + 3 * i] = sample.b
                    }
                    PackedComponent(if (c.samples.isEmpty()) TYPE_SKIP else TYPE_COLOUR, op, c.invert, 0, d)
                }

                is MaskComponent.LuminanceRange -> {
                    val lo = (minOf(c.low, c.high) / PERCENT).coerceIn(0f, 1f)
                    val hi = (maxOf(c.low, c.high) / PERCENT).coerceIn(0f, 1f)
                    val f = (c.falloff / PERCENT).coerceIn(0f, 1f)
                    d[0] = lo - f
                    d[1] = lo
                    d[2] = hi
                    d[3] = hi + f
                    // No cut at the ends of the range (x is clamped to 0..1): L = 1 stays in a band up to 100.
                    d[4] = if (lo <= 0f) 0f else 1f
                    d[5] = if (hi >= 1f) 0f else 1f
                    PackedComponent(TYPE_LUMA, op, c.invert, 0, d)
                }

                is MaskComponent.Ai -> {
                    ai += c
                    PackedComponent(TYPE_AI, op, c.invert, ai.lastIndex, d)
                }

                is MaskComponent.Unknown -> PackedComponent(TYPE_SKIP, op, c.invert, 0, d)
            }
        }

        /** Colour range distance (OkLab) at range 0 and 100. */
        const val COLOUR_MIN: Float = 0.01f
        const val COLOUR_MAX: Float = 0.2f

        /** Weight of the lightness difference in the colour distance (squared: 0.25 = half). */
        const val COLOUR_L_WEIGHT: Float = 0.25f
    }
}
