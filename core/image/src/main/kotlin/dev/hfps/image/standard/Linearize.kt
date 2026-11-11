// Pixel loops over RGBA bytes.
@file:Suppress("MagicNumber")

package dev.hfps.image.standard

import dev.hfps.image.LinearImage
import kotlin.math.roundToInt

/** Pure conversion of 8-bit encoded RGB(A) pixels to the 16-bit linear working encoding. */
object Linearize {
    private const val LEVELS = 256
    private const val MAX = 65535.0

    /** 256-entry table: encoded 8-bit value to linear 0..65535 through `eotf` (encoded 0..1 to linear 0..1). */
    fun lut(eotf: (Double) -> Double): ShortArray = ShortArray(LEVELS) { v ->
        (eotf(v / (LEVELS - 1.0)).coerceIn(0.0, 1.0) * MAX).roundToInt().toShort()
    }

    /**
     * Converts [width] x [height] RGBA_8888 pixels in [rgba] (bytes R, G, B, A per pixel, rows [rowBytes] apart) to a
     * [LinearImage] through [lutR], [lutG] and [lutB] (the same table for ordinary spaces). Alpha is ignored.
     */
    fun rgbaToLinear(
        rgba: ByteArray,
        width: Int,
        height: Int,
        rowBytes: Int,
        lutR: ShortArray,
        lutG: ShortArray = lutR,
        lutB: ShortArray = lutR,
    ): LinearImage {
        val out = ShortArray(width * height * LinearImage.CHANNELS)
        var o = 0
        for (y in 0 until height) {
            var i = y * rowBytes
            repeat(width) {
                out[o++] = lutR[rgba[i].toInt() and 0xFF]
                out[o++] = lutG[rgba[i + 1].toInt() and 0xFF]
                out[o++] = lutB[rgba[i + 2].toInt() and 0xFF]
                i += 4
            }
        }
        return LinearImage(width, height, out)
    }
}
