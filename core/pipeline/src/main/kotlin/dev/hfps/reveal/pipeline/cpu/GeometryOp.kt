@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import kotlin.math.floor

/**
 * Slot 13, geometry (docs/adr/0002): crop, straighten, quarter turns and flips as **one** resampling of the
 * display-referred linear working-space image, just before the output transform. Mirrored by the output pass
 * (`gl/Shaders.kt`, `OUTPUT_FRAG`) line for line.
 *
 * For output pixel `(x, y)` the source point is `ip = toImage * (x + 0.5, y + 0.5)` ([GeometryMapping], float32
 * coefficients so the CPU and the GPU start from identical numbers), then:
 *
 * - `ip` outside the image `[0, W] x [0, H]` (an empty corner when the crop is not constrained): black, `0`;
 * - no straightening ([GeometryMapping.axisAligned]): the texel `floor(ip)` (every output centre lands on a texel
 *   centre, so this is an exact copy: crop, quarter turns and flips never blur);
 * - otherwise **Catmull-Rom** bicubic (Keys, a = -0.5) in linear light over the 4x4 texels around `ip - 0.5`, the
 *   taps clamped to the image edge:
 *   ```
 *   w0 = ((-0.5 t + 1) t - 0.5) t      w1 = (1.5 t - 2.5) t^2 + 1
 *   w2 = ((-1.5 t + 2) t + 0.5) t      w3 = (0.5 t - 0.5) t^2
 *   ```
 *   Interpolating (passes through the samples), sharper than bilinear without Lanczos' 36 taps; its small overshoot
 *   on hard edges stays in float and is clamped only by the output transform.
 */
object GeometryOp {
    /** Renders the whole output of [mapping] from [rgb] (`W*H*3`, the mapping's image); `outW*outH*3`. */
    fun apply(rgb: FloatArray, mapping: GeometryMapping): FloatArray =
        apply(rgb, mapping, 0, 0, mapping.outputWidth, mapping.outputHeight)

    /**
     * Renders the output tile `[x0, x0 + w) x [y0, y0 + h)` of [mapping] from [rgb] (`W*H*3`). The export's tiled path
     * calls this per tile on the source region [GeometryMapping.sourceRegion] gives (or the whole image).
     */
    @Suppress("LongParameterList")
    fun apply(rgb: FloatArray, mapping: GeometryMapping, x0: Int, y0: Int, w: Int, h: Int): FloatArray {
        val width = mapping.imageWidth
        val height = mapping.imageHeight
        require(rgb.size == width * height * CHANNELS) { "rgb must be the mapping's image" }
        val m = mapping.toImageFloats()
        val out = FloatArray(w * h * CHANNELS)
        val px = FloatArray(CHANNELS)
        for (y in 0 until h) {
            val oy = (y0 + y) + 0.5f
            for (x in 0 until w) {
                val ox = (x0 + x) + 0.5f
                val ix = m[0] * ox + m[1] * oy + m[2]
                val iy = m[3] * ox + m[4] * oy + m[5]
                val o = (y * w + x) * CHANNELS
                if (!inside(ix, iy, width, height)) continue
                if (mapping.axisAligned) {
                    val i = (texel(iy, height) * width + texel(ix, width)) * CHANNELS
                    out[o] = rgb[i]
                    out[o + 1] = rgb[i + 1]
                    out[o + 2] = rgb[i + 2]
                } else {
                    catmullRom(rgb, width, height, ix, iy, px)
                    out[o] = px[0]
                    out[o + 1] = px[1]
                    out[o + 2] = px[2]
                }
            }
        }
        return out
    }

    /** Catmull-Rom sample of [rgb] at the continuous pixel position ([x], [y]) into [out] (RGB). */
    fun catmullRom(rgb: FloatArray, width: Int, height: Int, x: Float, y: Float, out: FloatArray) {
        val sx = x - 0.5f
        val sy = y - 0.5f
        val fx = floor(sx)
        val fy = floor(sy)
        val tx = sx - fx
        val ty = sy - fy
        val wx = weights(tx)
        val wy = weights(ty)
        val bx = fx.toInt() - 1
        val by = fy.toInt() - 1
        var r = 0f
        var g = 0f
        var b = 0f
        for (j in 0 until TAPS) {
            val row = (by + j).coerceIn(0, height - 1) * width
            var rr = 0f
            var gg = 0f
            var bb = 0f
            for (i in 0 until TAPS) {
                val k = (row + (bx + i).coerceIn(0, width - 1)) * CHANNELS
                rr += wx[i] * rgb[k]
                gg += wx[i] * rgb[k + 1]
                bb += wx[i] * rgb[k + 2]
            }
            r += wy[j] * rr
            g += wy[j] * gg
            b += wy[j] * bb
        }
        out[0] = r
        out[1] = g
        out[2] = b
    }

    /** The four Catmull-Rom weights for the fraction [t] in [0, 1); they sum to 1. */
    fun weights(t: Float): FloatArray = floatArrayOf(
        ((-0.5f * t + 1f) * t - 0.5f) * t,
        (1.5f * t - 2.5f) * t * t + 1f,
        ((-1.5f * t + 2f) * t + 0.5f) * t,
        (0.5f * t - 0.5f) * t * t,
    )

    private fun inside(x: Float, y: Float, width: Int, height: Int): Boolean =
        x >= 0f && y >= 0f && x <= width && y <= height

    private fun texel(v: Float, size: Int): Int = floor(v).toInt().coerceIn(0, size - 1)

    private const val TAPS = 4
    private const val CHANNELS = 3
}
