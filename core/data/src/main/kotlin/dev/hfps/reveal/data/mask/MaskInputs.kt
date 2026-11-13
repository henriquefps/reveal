@file:Suppress("MagicNumber")

package dev.hfps.reveal.data.mask

import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.service.MaskService
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.security.MessageDigest
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The image the mask models see (docs/adr/0021 "Model input", docs/adr/0022 "M4/B (editor)"): the photo's upright
 * input area-averaged to [MaskService.MAX_INPUT] px on its long side, rendered by the CPU reference with the default
 * recipe (as-shot white balance, the Standard look, no geometry; the identity for a JPEG) and encoded as sRGB. A
 * function of the photo only, so a mask never changes when a slider moves and the masks cache per photo. [VERSION]
 * goes into the cache key: change it whenever this rendering changes.
 */
object MaskInputs {
    const val VERSION: Int = 1

    /** The model input's size for an upright image of [width] x [height]. */
    fun size(width: Int, height: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= MaskService.MAX_INPUT) return width to height
        val k = MaskService.MAX_INPUT.toDouble() / long
        return max(1, (width * k).roundToInt()) to max(1, (height * k).roundToInt())
    }

    /**
     * [input] area-averaged to the model input's size (still linear, the recipe not applied): what the editor keeps
     * after the proxy is dropped, to [render] when a mask is first needed.
     */
    fun downscale(input: PipelineInput): PipelineInput {
        val (w, h) = size(input.width, input.height)
        return resample(input, w, h)
    }

    /** The model input of [input] (the upright linear proxy, or any copy of the photo). */
    fun render(input: PipelineInput): MlImage {
        val small = downscale(input)
        val w = small.width
        val h = small.height
        val rgb = CpuDevelop.renderLinear(small, Recipe())
        val m = ColorSpaces.SRGB.fromXyz * ColorSpaces.REC2020.toXyz
        val k = DoubleArray(9) { m[it / 3, it % 3] }
        val out = FloatArray(rgb.size)
        IntStream.range(0, h).parallel().forEach { y ->
            for (i in y * w until (y + 1) * w) {
                val o = i * 3
                val r = rgb[o].toDouble()
                val g = rgb[o + 1].toDouble()
                val b = rgb[o + 2].toDouble()
                // Quantised to 8 bits like a decoded picture, so the content key is stable.
                out[o] = quantise(encode((k[0] * r + k[1] * g + k[2] * b).coerceIn(0.0, 1.0)))
                out[o + 1] = quantise(encode((k[3] * r + k[4] * g + k[5] * b).coerceIn(0.0, 1.0)))
                out[o + 2] = quantise(encode((k[6] * r + k[7] * g + k[8] * b).coerceIn(0.0, 1.0)))
            }
        }
        return MlImage(w, h, out)
    }

    /**
     * The model input of the photo in [image] (an export's, which has no proxy at hand): a [PROXY_EDGE] px proxy
     * decoded like the editor's, then [render]. `null` when it cannot be decoded.
     */
    fun fromImage(image: ImageInput): MlImage? = runCatching {
        val orientation = if (image.type.isRaw) {
            null
        } else {
            runCatching {
                image.inspection.metadata.orientation
            }.getOrNull()
        }
        val proxy = ProxyBuilder.decodeProxy(image, PROXY_EDGE)
        val metadata = runCatching { image.inspection.metadata }.getOrNull()
        render(PipelineInputMapper.map(proxy.image, proxy.info, orientation ?: proxy.info.orientation, metadata))
    }.getOrNull()

    /** The proxy an export decodes for the model input (the editor's is 2400-3000 px). */
    const val PROXY_EDGE: Int = 2400

    /**
     * The photo's key for the masks cache when it has no file fingerprint: `img:` and the first 16 bytes of the
     * SHA-256 of the model input's 8-bit pixels (the same photo gives the same key on any device).
     */
    fun contentKey(image: MlImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(image.rgb.size)
        for (i in bytes.indices) bytes[i] = (image.rgb[i] * 255f).roundToInt().toByte()
        digest.update(byteArrayOf((image.width shr 8).toByte(), image.width.toByte(), (image.height shr 8).toByte()))
        digest.update(bytes)
        return "img:" + digest.digest().take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** Area average of [input] to exactly [w] x [h] (whole-pixel blocks). */
    internal fun resample(input: PipelineInput, w: Int, h: Int): PipelineInput {
        if (w == input.width && h == input.height) return input
        val out = FloatArray(w * h * 3)
        val src = input.linearRgb
        IntStream.range(0, h).parallel().forEach { y ->
            val y0 = (y.toLong() * input.height / h).toInt()
            val y1 = max(y0 + 1, ((y + 1L) * input.height / h).toInt())
            for (x in 0 until w) {
                val x0 = (x.toLong() * input.width / w).toInt()
                val x1 = max(x0 + 1, ((x + 1L) * input.width / w).toInt())
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (yy in y0 until y1) {
                    for (xx in x0 until x1) {
                        val i = (yy * input.width + xx) * 3
                        r += src[i]
                        g += src[i + 1]
                        b += src[i + 2]
                    }
                }
                val n = 1.0 / ((y1 - y0) * (x1 - x0))
                val o = (y * w + x) * 3
                out[o] = (r * n).toFloat()
                out[o + 1] = (g * n).toFloat()
                out[o + 2] = (b * n).toFloat()
            }
        }
        return PipelineInput(
            input.kind,
            w,
            h,
            out,
            input.cameraToXyz,
            input.asShotMultipliers?.copyOf(),
            input.saturation,
            input.noise,
            input.lens,
        )
    }

    private fun encode(v: Double): Double =
        if (v <= 0.0031308) v * 12.92 else 1.055 * StrictMath.pow(v, 1 / 2.4) - 0.055

    private fun quantise(v: Double): Float = (v * 255.0).roundToInt() / 255f

    private const val KEY_BYTES = 16
}
