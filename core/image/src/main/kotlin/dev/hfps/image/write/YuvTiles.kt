package dev.hfps.image.write

import kotlin.math.roundToInt

/** One tile in planar Y'CbCr 4:2:0: [y] is `width * height`, [u] and [v] `width / 2 * height / 2`. */
class YuvTile(val width: Int, val height: Int, val depth: Int) {
    val y = ShortArray(width * height)
    val u = ShortArray(width / 2 * (height / 2))
    val v = ShortArray(width / 2 * (height / 2))
}

/**
 * R'G'B' (already encoded with the output's transfer function) to Y'CbCr for the HEIF/AVIF encoders (docs/adr/0009
 * "HEIF and AVIF"): BT.709 coefficients (`Kr 0.2126, Kb 0.0722`, H.273 matrix 1), **full range** (H.273:
 * `Y = round((2^D - 1) E'y)`, `Cb = round((2^D - 1) E'pb + 2^(D - 1))`), 4:2:0 with each chroma sample the mean of
 * its 2 x 2 pixels' `E'pb` / `E'pr` (centred siting). Pixels beyond the image repeat its last row and column (the
 * grid's edge tiles; the container crops them away). Pure Kotlin, JVM-tested; the container's `nclx` says exactly
 * this.
 */
object YuvTiles {
    const val KR = 0.2126
    const val KB = 0.0722
    private const val KG = 1.0 - KR - KB
    private const val PB_SCALE = 2.0 * (1.0 - KB)
    private const val PR_SCALE = 2.0 * (1.0 - KR)

    /** Converts the [tile] at ([x0], [y0]) of [rows] (any bit depth); [tile]'s size must be even. */
    fun convert(rows: RgbRows, x0: Int, y0: Int, tile: YuvTile) {
        require(tile.width % 2 == 0 && tile.height % 2 == 0) { "4:2:0 tiles have even sizes" }
        val inMax = ((1 shl rows.bits) - 1).toDouble()
        val outMax = ((1 shl tile.depth) - 1).toDouble()
        val half = (1 shl (tile.depth - 1)).toDouble()
        val w = minOf(tile.width, rows.width - x0)
        val samples = IntArray(maxOf(w, 1) * 3)
        val pb = DoubleArray(tile.width * 2)
        val pr = DoubleArray(tile.width * 2)
        val cw = tile.width / 2
        for (ty in 0 until tile.height) {
            val sy = minOf(y0 + ty, rows.height - 1)
            rows.row(sy, x0, w, samples)
            val pair = (ty and 1) * tile.width
            for (tx in 0 until tile.width) {
                val sx = minOf(tx, w - 1)
                val r = samples[sx * 3] / inMax
                val g = samples[sx * 3 + 1] / inMax
                val b = samples[sx * 3 + 2] / inMax
                val luma = KR * r + KG * g + KB * b
                tile.y[ty * tile.width + tx] = (outMax * luma).roundToInt().coerceIn(0, outMax.toInt()).toShort()
                pb[pair + tx] = (b - luma) / PB_SCALE
                pr[pair + tx] = (r - luma) / PR_SCALE
            }
            if (ty and 1 == 1) {
                val cy = ty / 2
                for (cx in 0 until cw) {
                    val i = 2 * cx
                    val mb = (pb[i] + pb[i + 1] + pb[tile.width + i] + pb[tile.width + i + 1]) / 4
                    val mr = (pr[i] + pr[i + 1] + pr[tile.width + i] + pr[tile.width + i + 1]) / 4
                    tile.u[cy * cw + cx] = (outMax * mb + half).roundToInt().coerceIn(0, outMax.toInt()).toShort()
                    tile.v[cy * cw + cx] = (outMax * mr + half).roundToInt().coerceIn(0, outMax.toInt()).toShort()
                }
            }
        }
    }

    /** The inverse for one pixel (tests): full-range Y'CbCr at [depth] to R'G'B' in [0, 1]. */
    fun toRgb(y: Int, cb: Int, cr: Int, depth: Int): DoubleArray {
        val max = ((1 shl depth) - 1).toDouble()
        val half = (1 shl (depth - 1)).toDouble()
        val luma = y / max
        val pb = (cb - half) / max
        val pr = (cr - half) / max
        val r = luma + PR_SCALE * pr
        val b = luma + PB_SCALE * pb
        val g = (luma - KR * r - KB * b) / KG
        return doubleArrayOf(r, g, b)
    }
}
