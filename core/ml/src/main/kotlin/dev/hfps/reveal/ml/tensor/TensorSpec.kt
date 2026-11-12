@file:Suppress("MagicNumber", "NestedBlockDepth", "CyclomaticComplexMethod")

package dev.hfps.reveal.ml.tensor

import dev.hfps.reveal.ml.MlImage
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.max

/** Memory order of an image tensor. */
enum class Layout {
    /** `[1, H, W, C]` (TensorFlow, MediaPipe). */
    NHWC,

    /** `[1, C, H, W]` (models converted from PyTorch). */
    NCHW,
}

/** How sRGB 0..1 values become the model's input values. */
enum class Normalisation {
    /** As is, 0..1. */
    ZERO_ONE,

    /** `2v - 1`, -1..1. */
    MINUS_ONE_ONE,

    /** `(v - mean) / std` with the ImageNet statistics. */
    IMAGENET,

    /** `v - 0.5` (DIS / IS-Net). */
    CENTRED,

    /** Divided by the image's maximum, then ImageNet (U²-Net's reference preprocessing). */
    MAX_THEN_IMAGENET,
    ;

    internal fun apply(rgb: FloatArray) {
        when (this) {
            ZERO_ONE -> Unit
            MINUS_ONE_ONE -> for (i in rgb.indices) rgb[i] = rgb[i] * 2f - 1f
            CENTRED -> for (i in rgb.indices) rgb[i] = rgb[i] - 0.5f
            IMAGENET -> imagenet(rgb, 1f)
            MAX_THEN_IMAGENET -> imagenet(rgb, 1f / (rgb.maxOrNull()?.takeIf { it > 1e-6f } ?: 1f))
        }
    }

    private fun imagenet(rgb: FloatArray, scale: Float) {
        for (i in rgb.indices) {
            val c = i % 3
            rgb[i] = (rgb[i] * scale - MEAN[c]) / STD[c]
        }
    }

    private companion object {
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/** The image input of a model: size, layout, normalisation, and an optional extra (prompt) channel after RGB. */
data class InputSpec(
    val width: Int,
    val height: Int,
    val layout: Layout,
    val normalisation: Normalisation,
    val extraChannel: Boolean = false,
) {
    val channels: Int get() = if (extraChannel) 4 else 3
    val size: Int get() = width * height * channels

    /**
     * [image] stretched to the input size (area/bilinear, [Resample]), normalised, laid out; [extra] (width x height,
     * or null for zeros) fills the fourth channel when the model has one.
     */
    fun encode(image: MlImage, extra: FloatArray? = null): FloatArray {
        val rgb = Resample.interleaved(image.rgb, image.width, image.height, 3, width, height)
        normalisation.apply(rgb)
        val n = width * height
        require(extra == null || (extraChannel && extra.size == n)) { "extra channel does not match the input" }
        val out = FloatArray(size)
        val ch = channels
        when (layout) {
            Layout.NHWC -> for (i in 0 until n) {
                out[i * ch] = rgb[i * 3]
                out[i * ch + 1] = rgb[i * 3 + 1]
                out[i * ch + 2] = rgb[i * 3 + 2]
                if (extraChannel) out[i * ch + 3] = extra?.get(i) ?: 0f
            }

            Layout.NCHW -> for (i in 0 until n) {
                out[i] = rgb[i * 3]
                out[n + i] = rgb[i * 3 + 1]
                out[2 * n + i] = rgb[i * 3 + 2]
                if (extraChannel) out[3 * n + i] = extra?.get(i) ?: 0f
            }
        }
        return out
    }
}

/** How the output tensor's values become per-class probabilities. */
enum class Activation {
    /** Already probabilities (0..1). */
    NONE,

    /** Logistic per value (single-channel logits). */
    SIGMOID,

    /** Softmax across the channels of each pixel (class logits). */
    SOFTMAX,

    /** Stretched so the map's minimum is 0 and maximum 1 (U²-Net's reference postprocessing). */
    MIN_MAX,
}

/** The segmentation output of a model: size, layout, channel count and activation. */
data class OutputSpec(
    val width: Int,
    val height: Int,
    val channels: Int,
    val layout: Layout,
    val activation: Activation,
) {
    val size: Int get() = width * height * channels

    /** The raw tensor as [channels] planes of width x height probabilities. */
    fun decode(raw: FloatArray): Array<FloatArray> {
        val all = decode(raw, (0 until channels).toSet())
        return Array(channels) { all.getValue(it) }
    }

    /**
     * Only the planes of [wanted] channels (a softmax still sums over every channel): a 151-class ADE20K output needs
     * one plane, not 151. Rows run in parallel.
     */
    fun decode(raw: FloatArray, wanted: Set<Int>): Map<Int, FloatArray> {
        require(raw.size == size) { "output has ${raw.size} values, expected $size" }
        require(wanted.all { it in 0 until channels }) { "channel out of range" }
        val n = width * height
        val keys = wanted.sorted().toIntArray()
        val planes = Array(keys.size) { FloatArray(n) }
        val cStride = if (layout == Layout.NHWC) 1 else n
        val pStride = if (layout == Layout.NHWC) channels else 1
        IntStream.range(0, height).parallel().forEach { y ->
            val e = DoubleArray(if (activation == Activation.SOFTMAX) channels else 0)
            for (i in y * width until (y + 1) * width) {
                val base = i * pStride
                if (activation == Activation.SOFTMAX) {
                    var m = Float.NEGATIVE_INFINITY
                    for (c in 0 until channels) m = max(m, raw[base + c * cStride])
                    var sum = 0.0
                    for (c in 0 until channels) {
                        e[c] = exp((raw[base + c * cStride] - m).toDouble())
                        sum += e[c]
                    }
                    for (k in keys.indices) planes[k][i] = (e[keys[k]] / sum).toFloat()
                } else {
                    for (k in keys.indices) {
                        val v = raw[base + keys[k] * cStride]
                        planes[k][i] = when (activation) {
                            Activation.SIGMOID -> sigmoid(v)
                            Activation.NONE -> v.coerceIn(0f, 1f)
                            else -> v
                        }
                    }
                }
            }
        }
        if (activation == Activation.MIN_MAX) planes.forEach(::stretch)
        return keys.indices.associate { keys[it] to planes[it] }
    }

    private fun sigmoid(v: Float): Float = (1.0 / (1.0 + exp(-v.toDouble()))).toFloat()

    private fun stretch(p: FloatArray) {
        val lo = p.min()
        val span = (p.max() - lo).takeIf { it > 1e-6f } ?: 1f
        for (i in p.indices) p[i] = (p[i] - lo) / span
    }
}
