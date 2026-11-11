package dev.hfps.reveal.pipeline.gl

import kotlin.math.roundToInt

/**
 * A 256-bin histogram of the displayed (display-encoded, 8-bit) photo: red, green, blue and luminance, plus the
 * clipped fractions the clipping warnings would mark. Luminance is Rec.709 luma of the encoded values
 * (`0.2126 R + 0.7152 G + 0.0722 B`, rounded), the usual "L" of photo editors.
 */
class Histogram(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val luma: IntArray,
    /** Pixels counted. */
    val total: Int,
    /** Share of pixels with every channel at 0. */
    val shadowClipped: Float,
    /** Share of pixels with at least one channel at 255. */
    val highlightClipped: Float,
) {
    /** The largest bin over all four channels, ignoring the two end bins (so clipping spikes do not flatten it). */
    val peak: Int
        get() {
            var m = 1
            for (c in listOf(red, green, blue, luma)) for (i in 1 until BINS - 1) m = maxOf(m, c[i])
            return m
        }

    companion object {
        const val BINS: Int = 256

        private const val KR = 0.2126f
        private const val KG = 0.7152f
        private const val KB = 0.0722f
        private const val MASK = 0xFF
        private const val RGBA = 4

        /** Bins [rgba] (RGBA8, any row order); only the first [pixels] pixels are read. */
        fun fromRgba8(rgba: ByteArray, pixels: Int = rgba.size / RGBA): Histogram {
            val r = IntArray(BINS)
            val g = IntArray(BINS)
            val b = IntArray(BINS)
            val l = IntArray(BINS)
            var shadows = 0
            var highlights = 0
            for (i in 0 until pixels) {
                val o = i * RGBA
                val pr = rgba[o].toInt() and MASK
                val pg = rgba[o + 1].toInt() and MASK
                val pb = rgba[o + 2].toInt() and MASK
                r[pr]++
                g[pg]++
                b[pb]++
                l[(KR * pr + KG * pg + KB * pb).roundToInt().coerceIn(0, MASK)]++
                if (pr == MASK || pg == MASK || pb == MASK) {
                    highlights++
                } else if (pr == 0 && pg == 0 && pb == 0) {
                    shadows++
                }
            }
            val n = pixels.coerceAtLeast(1).toFloat()
            return Histogram(r, g, b, l, pixels, shadows / n, highlights / n)
        }
    }
}
