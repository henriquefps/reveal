@file:Suppress("NestedBlockDepth", "MagicNumber")

package dev.hfps.reveal.ml.tensor

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Separable resampling of interleaved planes with explicit weights (no platform scaler, so the JVM tests and the
 * device give the same tensors). Shrinking averages the exact source footprint of each output pixel (area);
 * enlarging is bilinear with pixel centres aligned (`(i + 0.5) * scale - 0.5`, edges clamped).
 */
object Resample {
    /** One output pixel's taps along an axis: source indices and their weights (summing to 1). */
    internal class Taps(val index: IntArray, val weight: FloatArray, val start: IntArray)

    internal fun taps(src: Int, dst: Int): Taps {
        val idx = ArrayList<Int>()
        val wts = ArrayList<Float>()
        val start = IntArray(dst + 1)
        val scale = src.toDouble() / dst
        for (i in 0 until dst) {
            start[i] = idx.size
            if (dst < src) {
                val a = i * scale
                val b = (i + 1) * scale
                var j = floor(a).toInt()
                while (j < b && j < src) {
                    val cover = min(b, j + 1.0) - max(a, j.toDouble())
                    if (cover > 0) {
                        idx += j
                        wts += (cover / scale).toFloat()
                    }
                    j++
                }
            } else {
                val x = ((i + 0.5) * scale - 0.5).coerceIn(0.0, src - 1.0)
                val x0 = floor(x).toInt()
                val x1 = min(x0 + 1, src - 1)
                val f = (x - x0).toFloat()
                idx += x0
                wts += 1f - f
                if (x1 != x0) {
                    idx += x1
                    wts += f
                }
            }
        }
        start[dst] = idx.size
        return Taps(idx.toIntArray(), wts.toFloatArray(), start)
    }

    /** [src] (w x h, [channels] interleaved) resampled to [dw] x [dh]. */
    fun interleaved(src: FloatArray, w: Int, h: Int, channels: Int, dw: Int, dh: Int): FloatArray {
        require(src.size == w * h * channels) { "size mismatch" }
        if (w == dw && h == dh) return src.copyOf()
        val tx = taps(w, dw)
        val ty = taps(h, dh)
        // Horizontal pass: h rows of dw pixels.
        val mid = FloatArray(dw * h * channels)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until dw) {
                val o = (y * dw + x) * channels
                for (k in tx.start[x] until tx.start[x + 1]) {
                    val s = (row + tx.index[k]) * channels
                    val wt = tx.weight[k]
                    for (c in 0 until channels) mid[o + c] += src[s + c] * wt
                }
            }
        }
        val out = FloatArray(dw * dh * channels)
        for (y in 0 until dh) {
            for (k in ty.start[y] until ty.start[y + 1]) {
                val s = ty.index[k] * dw * channels
                val wt = ty.weight[k]
                val o = y * dw * channels
                for (i in 0 until dw * channels) out[o + i] += mid[s + i] * wt
            }
        }
        return out
    }

    /** A single-channel plane resampled to [dw] x [dh]. */
    fun plane(src: FloatArray, w: Int, h: Int, dw: Int, dh: Int): FloatArray = interleaved(src, w, h, 1, dw, dh)
}
