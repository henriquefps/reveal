@file:Suppress("MagicNumber", "NestedBlockDepth")

package dev.hfps.reveal.pipeline.auto

import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.OkLab
import dev.hfps.reveal.pipeline.faces.DetectionImage
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the subject probably is when there is no face (docs/adr/0010, "Subject"): a small map, 0..1, over the frame
 * (normalised coordinates, bilinear in between). A function of the photo only, computed once from the face
 * detector's image.
 *
 * On a copy at [SIDE] px (OkLab, from the sRGB detection image), three cues, each normalised to its maximum:
 * 1. **Colour distinctiveness** (frequency-tuned saliency, Achanta et al. 2009): the distance of the slightly blurred
 *    colour from the frame's mean colour, chroma counted [CHROMA_WEIGHT] times (a red coat on grass, a face against
 *    a wall).
 * 2. **Detail**: local contrast `|L - blur(L)|`, blurred: the subject is usually in focus, the background often soft.
 * 3. **Centre prior**: `exp(-r^2 / (2 * 0.3^2))`, `r` from the centre in frame units.
 *
 * `map = centre * (0.5 * colour + 0.5 * detail)`, smoothed and normalised to a maximum of 1. Deliberately simple:
 * the map only *weights* the histogram Auto measures the key on (a wrong guess degrades towards the plain
 * centre-weighted key, never to a broken exposure).
 */
class SaliencyMap(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(values.size == width * height) { "values must have width*height entries" }
    }

    /** The map at normalised ([u], [v]), bilinear. */
    fun at(u: Float, v: Float): Float {
        val x = (u * width - 0.5f).coerceIn(0f, width - 1f)
        val y = (v * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = values[y0 * width + x0] * (1 - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1 - fx) + values[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    companion object {
        /** Long side of the map. */
        const val SIDE: Int = 64

        private const val CHROMA_WEIGHT = 2f
        private const val CENTRE_SIGMA = 0.3f
        private const val COLOUR_BLUR = 1.0f
        private const val DETAIL_BLUR = 3.0f
        private const val SMOOTH = 2.0f

        /** A uniform map (no subject cue). */
        fun uniform(): SaliencyMap = SaliencyMap(1, 1, floatArrayOf(1f))

        /** The map of [image]. */
        fun of(image: DetectionImage): SaliencyMap {
            val scale = max(image.width, image.height).toFloat() / SIDE
            val w = max(1, (image.width / scale).toInt())
            val h = max(1, (image.height / scale).toInt())
            val lab = lab(image, w, h)
            val l = FloatArray(w * h) { lab[it * 3] }
            val a = FloatArray(w * h) { lab[it * 3 + 1] }
            val b = FloatArray(w * h) { lab[it * 3 + 2] }
            val lb = blur(l, w, h, COLOUR_BLUR)
            val ab = blur(a, w, h, COLOUR_BLUR)
            val bb = blur(b, w, h, COLOUR_BLUR)
            val ml = l.average().toFloat()
            val ma = a.average().toFloat()
            val mb = b.average().toFloat()
            val colour = FloatArray(w * h) {
                val dl = lb[it] - ml
                val da = (ab[it] - ma) * CHROMA_WEIGHT
                val db = (bb[it] - mb) * CHROMA_WEIGHT
                sqrt(dl * dl + da * da + db * db)
            }
            val soft = blur(l, w, h, DETAIL_BLUR)
            val detail = blur(FloatArray(w * h) { kotlin.math.abs(l[it] - soft[it]) }, w, h, DETAIL_BLUR)
            normalise(colour)
            normalise(detail)
            val map = FloatArray(w * h)
            for (y in 0 until h) {
                val dy = (y + 0.5f) / h - 0.5f
                for (x in 0 until w) {
                    val dx = (x + 0.5f) / w - 0.5f
                    val centre = exp(-(dx * dx + dy * dy) / (2 * CENTRE_SIGMA * CENTRE_SIGMA))
                    map[y * w + x] = centre * (0.5f * colour[y * w + x] + 0.5f * detail[y * w + x])
                }
            }
            val out = blur(map, w, h, SMOOTH)
            normalise(out)
            return SaliencyMap(w, h, out)
        }

        /** Area average of [image] to [w] x [h], linearised and converted to OkLab. */
        private fun lab(image: DetectionImage, w: Int, h: Int): FloatArray {
            val toRec2020 = ColorSpaces.REC2020.fromXyz * ColorSpaces.SRGB.toXyz
            val out = FloatArray(w * h * 3)
            val sum = DoubleArray(3)
            val lab = FloatArray(3)
            for (y in 0 until h) {
                val y0 = y * image.height / h
                val y1 = max(y0 + 1, (y + 1) * image.height / h)
                for (x in 0 until w) {
                    val x0 = x * image.width / w
                    val x1 = max(x0 + 1, (x + 1) * image.width / w)
                    sum.fill(0.0)
                    for (yy in y0 until y1) {
                        for (xx in x0 until x1) {
                            val i = (yy * image.width + xx) * 3
                            for (c in 0..2) sum[c] += decode(image.rgb[i + c])
                        }
                    }
                    val n = ((y1 - y0) * (x1 - x0)).toDouble()
                    val rec = toRec2020.times(doubleArrayOf(sum[0] / n, sum[1] / n, sum[2] / n))
                    OkLab.fromRgb(rec[0].toFloat(), rec[1].toFloat(), rec[2].toFloat(), lab)
                    lab.copyInto(out, (y * w + x) * 3)
                }
            }
            return out
        }

        private fun decode(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else StrictMath.pow((d + 0.055) / 1.055, 2.4)
        }

        /** Separable Gaussian blur, edges clamped. */
        fun blur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
            val r = (sigma * 3).toInt().coerceAtLeast(1)
            val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
            val ks = k.sum()
            for (i in k.indices) k[i] /= ks
            val tmp = FloatArray(src.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var acc = 0f
                    for (j in -r..r) acc += k[j + r] * src[y * w + (x + j).coerceIn(0, w - 1)]
                    tmp[y * w + x] = acc
                }
            }
            val out = FloatArray(src.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var acc = 0f
                    for (j in -r..r) acc += k[j + r] * tmp[(y + j).coerceIn(0, h - 1) * w + x]
                    out[y * w + x] = acc
                }
            }
            return out
        }

        private fun normalise(v: FloatArray) {
            val m = v.maxOrNull() ?: return
            if (m > 0f) for (i in v.indices) v[i] /= m
        }
    }
}
