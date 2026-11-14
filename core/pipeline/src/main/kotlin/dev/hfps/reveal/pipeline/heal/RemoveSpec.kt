@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Heal
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The context crop of a Remove spot (docs/adr/0023, "Remove: the contract"), defined on the **original** image
 * ([originalWidth] x [originalHeight], the full-resolution photo) so that every renderer asks for the same fill: the
 * original is box-downscaled by the integer [factor] (a "virtual" image of [virtualWidth] x [virtualHeight]) and the
 * crop is the virtual pixels ([x0], [y0]) .. ([x1], [y1]), exclusive. The crop is the region's box grown by
 * [CONTEXT] times its larger side on every side, clipped to the image; [factor] keeps its longer side at most
 * [MAX_SIDE].
 */
data class RemoveSpec(
    val originalWidth: Int,
    val originalHeight: Int,
    val factor: Int,
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
) {
    val virtualWidth: Int get() = originalWidth / factor
    val virtualHeight: Int get() = originalHeight / factor
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0

    /**
     * The crop of [heal] read from [original]: each crop pixel the mean of its [factor] x [factor] block of the
     * original, encoded for display with [colour] (camera matrices of the photo) and the crop's own exposure gain; the
     * hole is the spot's shape on the virtual image, any coverage counting, dilated by [DILATE] pixels.
     */
    fun read(heal: Heal, original: PixelReader, colour: PipelineInput, seed: Long): RemoveCrop {
        val w = width
        val h = height
        val f = factor
        val cam = FloatArray(w * h * RGB)
        val row = FloatArray(w * f * RGB)
        val inv = 1f / (f * f)
        for (j in 0 until h) {
            val acc = FloatArray(w * RGB)
            for (k in 0 until f) {
                original.read(x0 * f, (y0 + j) * f + k, w * f, 1, row)
                for (i in 0 until w) {
                    for (dx in 0 until f) {
                        val s = (i * f + dx) * RGB
                        acc[i * RGB] += row[s]
                        acc[i * RGB + 1] += row[s + 1]
                        acc[i * RGB + 2] += row[s + 2]
                    }
                }
            }
            for (i in 0 until w * RGB) cam[j * w * RGB + i] = acc[i] * inv
        }
        val base = RemoveColour.of(colour)
        val lum = FloatArray(w * h) { base.luminance(cam, it * RGB) }
        val encoding = base.withGain(RemoveColour.gainFor(lum))
        val rgb = FloatArray(w * h * RGB)
        for (i in 0 until w * h) encoding.toDisplay(cam, i * RGB, rgb, i * RGB)
        return RemoveCrop(RemoveInput(w, h, rgb, hole(heal), seed), encoding)
    }

    /** The region to fill in crop pixels (1 or 0), dilated by [DILATE]. */
    fun hole(heal: Heal): FloatArray {
        val w = width
        val h = height
        val mask = FloatArray(w * h)
        val fp = Footprint.of(heal, virtualWidth, virtualHeight, feather = 0f)
        if (fp != null) {
            for (j in 0 until fp.height) {
                for (i in 0 until fp.width) {
                    if (fp.alpha[j * fp.width + i] <= 0f) continue
                    val x = fp.x0 + i - x0
                    val y = fp.y0 + j - y0
                    if (x in 0 until w && y in 0 until h) mask[y * w + x] = 1f
                }
            }
        }
        return dilate(mask, w, h, DILATE)
    }

    /** The crop as a rectangle of an image of [imageWidth] x [imageHeight] (fractions scaled): x0, y0, x1, y1. */
    fun rectIn(imageWidth: Int, imageHeight: Int): IntArray {
        val sx = imageWidth.toDouble() / virtualWidth
        val sy = imageHeight.toDouble() / virtualHeight
        return intArrayOf(
            floor(x0 * sx).toInt().coerceIn(0, imageWidth - 1),
            floor(y0 * sy).toInt().coerceIn(0, imageHeight - 1),
            ceil(x1 * sx).toInt().coerceIn(1, imageWidth),
            ceil(y1 * sy).toInt().coerceIn(1, imageHeight),
        )
    }

    companion object {
        /** Longest side of a crop in pixels. */
        const val MAX_SIDE: Int = 512

        /** Context around the region, as a multiple of its larger side, on each side. */
        const val CONTEXT: Float = 1f

        /** Pixels the hole grows by in the crop (the method fills a little past the edge). */
        const val DILATE: Int = 2

        private const val RGB = 3
        private const val MIN_CONTEXT_PX = 24

        /** The crop of [heal] on an original of [width] x [height], or null when the spot renders nothing. */
        fun of(heal: Heal, width: Int, height: Int): RemoveSpec? {
            if (!heal.renders) return null
            val long = max(width, height).toFloat()
            val r = heal.radius * long
            var bx0: Float
            var by0: Float
            var bx1: Float
            var by1: Float
            if (heal.isStroke) {
                val pts = heal.strokePoints
                bx0 = pts.minOf { it.x } * width
                by0 = pts.minOf { it.y } * height
                bx1 = pts.maxOf { it.x } * width
                by1 = pts.maxOf { it.y } * height
            } else {
                bx0 = heal.x * width
                by0 = heal.y * height
                bx1 = bx0
                by1 = by0
            }
            bx0 -= r
            by0 -= r
            bx1 += r
            by1 += r
            val margin = max(max(bx1 - bx0, by1 - by0) * CONTEXT, MIN_CONTEXT_PX.toFloat())
            val cx0 = max(bx0 - margin, 0f)
            val cy0 = max(by0 - margin, 0f)
            val cx1 = min(bx1 + margin, width.toFloat())
            val cy1 = min(by1 + margin, height.toFloat())
            if (cx1 <= cx0 || cy1 <= cy0) return null
            val side = max(cx1 - cx0, cy1 - cy0)
            val factor = max(1, ceil(side / MAX_SIDE).toInt())
            val vw = width / factor
            val vh = height / factor
            val x0 = floor(cx0 / factor).toInt().coerceIn(0, vw - 1)
            val y0 = floor(cy0 / factor).toInt().coerceIn(0, vh - 1)
            val x1 = ceil(cx1 / factor).toInt().coerceIn(x0 + 1, vw)
            val y1 = ceil(cy1 / factor).toInt().coerceIn(y0 + 1, vh)
            return RemoveSpec(width, height, factor, x0, y0, x1, y1)
        }

        /** The key of a fill: the original's [identity], the crop, the spot's shape and the method that runs. */
        fun key(identity: String, spec: RemoveSpec, heal: Heal, method: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val text = listOf(
                identity,
                spec.toString(),
                heal.x,
                heal.y,
                heal.radius,
                heal.stroke,
                method,
                VERSION,
            ).joinToString("|")
            digest.update(text.toByteArray())
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** A deterministic seed from a key. */
        fun seed(key: String): Long = key.take(SEED_HEX).toULong(HEX).toLong()

        /**
         * The identity of an original image: its size and a fixed sample of its pixels ([SAMPLES] x [SAMPLES] points),
         * hashed. The same decode of the same file gives the same identity in the editor and in an export.
         */
        fun identity(original: PixelReader): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("${original.width}x${original.height}".toByteArray())
            val px = FloatArray(RGB)
            val bytes = java.nio.ByteBuffer.allocate(RGB * Float.SIZE_BYTES)
            for (j in 0 until SAMPLES) {
                for (i in 0 until SAMPLES) {
                    val x = ((i + HALF) * original.width / SAMPLES).toInt().coerceIn(0, original.width - 1)
                    val y = ((j + HALF) * original.height / SAMPLES).toInt().coerceIn(0, original.height - 1)
                    original.read(x, y, 1, 1, px)
                    bytes.clear()
                    px.forEach { bytes.putFloat(it) }
                    digest.update(bytes.array())
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Bump when the crop, the encoding or the patch method change their output. */
        const val VERSION: Int = 1
        private const val SAMPLES = 16
        private const val HALF = 0.5
        private const val SEED_HEX = 15
        private const val HEX = 16

        /** [mask] grown by [radius] pixels (square neighbourhood). */
        internal fun dilate(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
            if (radius <= 0) return mask
            val rows = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var v = 0f
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx in 0 until w && mask[y * w + xx] > v) v = mask[y * w + xx]
                    }
                    rows[y * w + x] = v
                }
            }
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var v = 0f
                    for (dy in -radius..radius) {
                        val yy = y + dy
                        if (yy in 0 until h && rows[yy * w + x] > v) v = rows[yy * w + x]
                    }
                    out[y * w + x] = v
                }
            }
            return out
        }
    }
}
