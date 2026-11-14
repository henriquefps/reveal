@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.mask.Dabs
import dev.hfps.reveal.pipeline.mask.MaskMath
import dev.hfps.reveal.pipeline.mask.MaskRaster
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.StrokePressure
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where a spot repairs, in the pixels of an image of one size (docs/adr/0023, "Shapes"): the box ([x0], [y0],
 * [width] x [height]) and its alpha in 0..1 (0 outside the shape). A circle's alpha is `1 - sstep(R (1 - f), R, d)` at
 * pixel centres (`R` = radius x long edge, `f` = feather / 100, `d` the distance to the centre); a stroke's is the
 * mask brush's raster of dabs of radius `R k(p)` (pen pressure scales the size) with that feather and full flow.
 * The region the repair solves for is `alpha > 0`.
 */
class Footprint(val x0: Int, val y0: Int, val width: Int, val height: Int, val alpha: FloatArray) {
    /** Alpha at image pixel ([x], [y]); 0 outside the box. */
    fun at(x: Int, y: Int): Float {
        val i = x - x0
        val j = y - y0
        return if (i < 0 || j < 0 || i >= width || j >= height) 0f else alpha[j * width + i]
    }

    /** The box as (x0, y0, x1, y1), x1 and y1 exclusive. */
    val rect: IntArray get() = intArrayOf(x0, y0, x0 + width, y0 + height)

    companion object {
        private const val HALF = 0.5f
        private const val PERCENT = 100f

        /**
         * The footprint of [heal] in an image of [imageWidth] x [imageHeight] pixels, clipped to the image, or null
         * when it covers no pixel centre. [feather] overrides the spot's (percent).
         */
        fun of(heal: Heal, imageWidth: Int, imageHeight: Int, feather: Float = heal.feather): Footprint? {
            if (!heal.renders) return null
            val long = max(imageWidth, imageHeight).toFloat()
            val f = (feather / PERCENT).coerceIn(0f, 1f)
            return if (heal.isStroke) {
                stroke(heal, imageWidth, imageHeight, long, feather)
            } else {
                circle(heal.x * imageWidth, heal.y * imageHeight, heal.radius * long, f, imageWidth, imageHeight)
            }
        }

        /** A circle of radius [r] pixels centred at ([cx], [cy]) with soft edge share [f]. */
        @Suppress("LongParameterList")
        fun circle(cx: Float, cy: Float, r: Float, f: Float, imageWidth: Int, imageHeight: Int): Footprint? {
            if (r <= 0f) return null
            val x0 = max(ceil(cx - r - HALF).toInt(), 0)
            val y0 = max(ceil(cy - r - HALF).toInt(), 0)
            val x1 = min(floor(cx + r - HALF).toInt(), imageWidth - 1)
            val y1 = min(floor(cy + r - HALF).toInt(), imageHeight - 1)
            if (x0 > x1 || y0 > y1) return null
            val w = x1 - x0 + 1
            val h = y1 - y0 + 1
            val alpha = FloatArray(w * h)
            val inner = r * (1f - f)
            var any = false
            for (j in 0 until h) {
                val dy = y0 + j + HALF - cy
                for (i in 0 until w) {
                    val dx = x0 + i + HALF - cx
                    val d = sqrt(dx * dx + dy * dy)
                    val a = if (d >= r) 0f else 1f - MaskMath.sstep(inner, r, d)
                    alpha[j * w + i] = a
                    if (a > 0f) any = true
                }
            }
            return if (any) Footprint(x0, y0, w, h, alpha) else null
        }

        private fun stroke(heal: Heal, imageWidth: Int, imageHeight: Int, long: Float, feather: Float): Footprint? {
            val stroke = BrushStroke(
                points = heal.stroke,
                size = heal.radius * 2f,
                feather = feather,
                pressure = StrokePressure.SIZE,
            )
            val dabs = Dabs.of(listOf(stroke), imageWidth, imageHeight)
            if (dabs.size == 0 || long <= 0f) return null
            val box = IntArray(4)
            var x0 = Int.MAX_VALUE
            var y0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE
            var y1 = Int.MIN_VALUE
            for (k in 0 until dabs.size) {
                dabs.box(k, box)
                x0 = min(x0, box[0])
                y0 = min(y0, box[1])
                x1 = max(x1, box[2])
                y1 = max(y1, box[3])
            }
            x0 = max(x0, 0)
            y0 = max(y0, 0)
            x1 = min(x1, imageWidth - 1)
            y1 = min(y1, imageHeight - 1)
            if (x0 > x1 || y0 > y1) return null
            val w = x1 - x0 + 1
            val h = y1 - y0 + 1
            val alpha = FloatArray(w * h)
            val d = dabs.data
            for (k in 0 until dabs.size) {
                dabs.box(k, box)
                val o = k * Dabs.DAB_FLOATS
                for (y in max(box[1], y0)..min(box[3], y1)) {
                    for (x in max(box[0], x0)..min(box[2], x1)) {
                        val a = MaskRaster.dabAlpha(d, o, x, y)
                        if (a <= 0f) continue
                        val i = (y - y0) * w + (x - x0)
                        alpha[i] += (1f - alpha[i]) * a
                    }
                }
            }
            return if (alpha.any { it > 0f }) Footprint(x0, y0, w, h, alpha) else null
        }
    }
}
