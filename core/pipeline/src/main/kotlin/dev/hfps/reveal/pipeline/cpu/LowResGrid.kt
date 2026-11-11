package dev.hfps.reveal.pipeline.cpu

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The low-resolution grid the Light group's neighbourhood operators work on (highlight reconstruction's chroma
 * pyramid, the local tone guide; docs/adr/0007). The image is cut into square cells of [cell] pixels so that the grid
 * has about [TARGET_CELLS] cells along the long edge, whatever the image size: a 2400 px proxy and a 6000 px export
 * get grids of nearly the same shape, so the operators' spatial scale is a fraction of the image, not a pixel count.
 *
 * The only full-resolution neighbourhood a Light operator reads is its own cell (a gather of `cell x cell` pixels);
 * everything wider happens on this grid, and the full-resolution apply step is pointwise given the grid. That is the
 * tile seam: tiles aligned to [cell] can build the grid in a pre-pass, and need no overlap afterwards.
 *
 * @property width full-resolution width in pixels.
 * @property height full-resolution height in pixels.
 */
class LowResGrid(val width: Int, val height: Int) {
    /** Cell edge in full-resolution pixels (at least 1). */
    val cell: Int = max(1, (max(width, height) + TARGET_CELLS - 1) / TARGET_CELLS)

    /** Grid width in cells (the last column may be partial). */
    val gridWidth: Int = (width + cell - 1) / cell

    /** Grid height in cells. */
    val gridHeight: Int = (height + cell - 1) / cell

    /**
     * Per-cell mean of what [pixel] accumulates, `gridWidth*gridHeight*comps` floats. [pixel] receives the pixel
     * index (`y * width + x`) and a row accumulator to add into. The order of the sums is the GPU's two passes: each
     * row segment of a cell summed left to right, then the segments summed top to bottom, divided by the pixel count.
     */
    @Suppress("NestedBlockDepth")
    inline fun reduceCells(comps: Int, pixel: (index: Int, acc: FloatArray) -> Unit): FloatArray {
        val out = FloatArray(gridWidth * gridHeight * comps)
        val row = FloatArray(comps)
        val cellSum = FloatArray(comps)
        for (gy in 0 until gridHeight) {
            val y0 = gy * cell
            val y1 = min(y0 + cell, height)
            for (gx in 0 until gridWidth) {
                val x0 = gx * cell
                val x1 = min(x0 + cell, width)
                cellSum.fill(0f)
                for (y in y0 until y1) {
                    row.fill(0f)
                    for (x in x0 until x1) pixel(y * width + x, row)
                    for (c in 0 until comps) cellSum[c] += row[c]
                }
                val n = ((x1 - x0) * (y1 - y0)).toFloat()
                for (c in 0 until comps) out[(gy * gridWidth + gx) * comps + c] = cellSum[c] / n
            }
        }
        return out
    }

    /** Grid coordinate (texel-centre convention, cell `i` has centre `i`) of full-resolution pixel coordinate [x]. */
    fun toGrid(x: Int): Float = (x + HALF) / cell - HALF

    companion object {
        /** Cells along the long edge. */
        const val TARGET_CELLS: Int = 256

        private const val HALF = 0.5f

        /**
         * Bilinear sample of a [w] x [h] grid of [comps] floats per cell at grid coordinate ([u], [v]) (cell centres
         * at integers), clamped to the edge cells; written to [out]. Explicit weights so the GPU can mirror it with
         * four `texelFetch` and fp32 maths (hardware bilinear weights are only 8-bit on some GPUs).
         */
        @Suppress("LongParameterList")
        fun bilinear(src: FloatArray, w: Int, h: Int, comps: Int, u: Float, v: Float, out: FloatArray) {
            val uc = min(max(u, 0f), (w - 1).toFloat())
            val vc = min(max(v, 0f), (h - 1).toFloat())
            val x0 = floor(uc).toInt()
            val y0 = floor(vc).toInt()
            val x1 = min(x0 + 1, w - 1)
            val y1 = min(y0 + 1, h - 1)
            val fx = uc - x0
            val fy = vc - y0
            for (c in 0 until comps) {
                val a = src[(y0 * w + x0) * comps + c]
                val b = src[(y0 * w + x1) * comps + c]
                val d = src[(y1 * w + x0) * comps + c]
                val e = src[(y1 * w + x1) * comps + c]
                val top = a + (b - a) * fx
                val bottom = d + (e - d) * fx
                out[c] = top + (bottom - top) * fy
            }
        }

        /** `smoothstep(a, b, x)` written out (`t = clamp((x-a)/(b-a))`, `t*t*(3-2t)`), identical on the GPU. */
        fun smoothstep(a: Float, b: Float, x: Float): Float {
            val t = min(max((x - a) / (b - a), 0f), 1f)
            return t * t * (THREE - 2f * t)
        }

        private const val THREE = 3f
    }
}
