@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import kotlin.math.max
import kotlin.math.min

/**
 * What highlight reconstruction needs for one (input, white balance) pair.
 *
 * @property clip balanced level at which each channel saturated: `saturation * mul[c]` (R, G, B).
 * @property maxClip the largest of [clip]: the level of the neutral fallback for fully clipped pixels.
 */
data class HighlightParams(val clipR: Float, val clipG: Float, val clipB: Float) {
    val maxClip: Float get() = max(clipR, max(clipG, clipB))

    companion object {
        /** Clip levels of input saturating at [saturation] (raw units) once balanced by [mul]. */
        fun from(mul: FloatArray, saturation: Float): HighlightParams =
            HighlightParams(saturation * mul[0], saturation * mul[1], saturation * mul[2])
    }
}

/**
 * Highlight reconstruction (ADR 0002 slot 5, method in docs/adr/0007), on balanced camera RGB, right after the raw
 * white balance: clipping happens per camera channel, so this is the only place that knows which channel clipped.
 * Exposure is a scalar gain that commutes with it, so running before exposure is the same as running after.
 *
 * Per channel, how clipped a value is: `t = clamp((b/clip - T0) / (T1 - T0), 0, 1)` (0 below 90 % of the
 * saturation point, 1 from 99 %), a ramp instead of a threshold so nothing jumps at the clip boundary.
 *
 * 1. **Chroma of the neighbourhood** ([chroma]): on the [LowResGrid], each cell averages the chromaticity
 *    `b / (b_r + b_g + b_b)` of its unclipped pixels, weighted by `(1 - max t) * y^2` (`y` = the pixel's brightness
 *    relative to saturation: bright unclipped pixels describe the highlight's colour best, dark ones barely count).
 *    A push-pull pyramid (Gortler et al. 1996, "The Lumigraph", pull-push hole filling) spreads it into cells that
 *    have none: push averages 2x2 cells up to 1x1; pull goes back down, each cell keeping its own chroma in
 *    proportion to its confidence `min(1, weight / W0)` and taking the rest from the bilinearly upsampled coarser
 *    level. Above the top level is the neutral chroma (1/3, 1/3, 1/3): with no unclipped pixel anywhere the result
 *    is neutral, never a guess.
 * 2. **Reconstruction** ([apply]): with the cell chroma `C` (bilinear at the pixel) and the unclipped weights
 *    `u = 1 - t`, the brightness that fits the surviving channels is `k = sum(u b) / sum(u C)`; each clipped channel
 *    becomes `max(b, min(k C, RMAX clip))` in proportion to `t` (never darker than the clipped value, at most two
 *    stops over its clip). Where every channel clipped (`f = min t` = 1) the colour is unknown: the pixel blends to
 *    neutral, at the level of the brightest channel of `full * C`, `full = max(clip / C)` (the darkest light of
 *    the neighbourhood's chroma with every channel at least at its clip; `maxClip` when the chroma is neutral).
 *    That level is the limit of the reconstruction as the last channel reaches its clip, so a blown core is never
 *    darker than its partly clipped rim, and it is never magenta.
 *
 * Limits: one chroma per cell neighbourhood (a clipped highlight that crosses two differently coloured objects
 * takes their blend near the boundary); fully clipped areas are flat neutral (no texture can be invented); the
 * reconstructed channel follows the unclipped one, so detail survives only where at least one channel did.
 */
object HighlightReconstructionOp {
    /** Clip ramp start, fraction of the saturation point. */
    const val T0: Float = 0.90f

    /** Clip ramp end. */
    const val T1: Float = 0.99f

    /** Largest reconstructed value, in multiples of the channel's clip level. */
    const val RMAX: Float = 4f

    /** Weight density (per pixel of a cell) at which a cell trusts its own chroma fully. */
    const val CONFIDENCE: Float = 0.05f

    /** Neutral chromaticity. */
    const val NEUTRAL: Float = 1f / 3f

    private const val MIN_SUM = 1e-6f
    private const val COMPS = 4

    /** How clipped the balanced value [b] is, 0..1, for a channel clipping at [clip]. */
    fun clipAmount(b: Float, clip: Float): Float = min(max((b / clip - T0) / (T1 - T0), 0f), 1f)

    /** True when some pixel of the un-balanced [raw] input reaches the clip ramp; otherwise [apply] is the identity. */
    fun needed(raw: FloatArray, saturation: Float): Boolean {
        val threshold = T0 * saturation
        return raw.any { it >= threshold }
    }

    /** Cell statistics: per cell `(sum w*chroma.rgb, sum w) / pixels`, `gridWidth*gridHeight*4` floats. */
    fun stats(rgb: FloatArray, grid: LowResGrid, p: HighlightParams): FloatArray =
        grid.reduceCells(COMPS) { index, acc ->
            val i = index * CHANNELS
            statsPixel(rgb[i], rgb[i + 1], rgb[i + 2], p, acc)
        }

    private fun statsPixel(r: Float, g: Float, b: Float, p: HighlightParams, acc: FloatArray) {
        val tr = clipAmount(r, p.clipR)
        val tg = clipAmount(g, p.clipG)
        val tb = clipAmount(b, p.clipB)
        val y = max(r / p.clipR, max(g / p.clipG, b / p.clipB))
        val rp = max(r, 0f)
        val gp = max(g, 0f)
        val bp = max(b, 0f)
        val sum = rp + gp + bp
        if (sum <= MIN_SUM) return
        val w = (1f - max(tr, max(tg, tb))) * y * y
        acc[0] += w * (rp / sum)
        acc[1] += w * (gp / sum)
        acc[2] += w * (bp / sum)
        acc[3] += w
    }

    /** One push level: each parent cell is the mean of its (up to four) children. Returns the parent level. */
    @Suppress("NestedBlockDepth")
    fun push(level: FloatArray, w: Int, h: Int): FloatArray {
        val pw = (w + 1) / 2
        val ph = (h + 1) / 2
        val out = FloatArray(pw * ph * COMPS)
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                val o = (y * pw + x) * COMPS
                var n = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val cx = 2 * x + dx
                        val cy = 2 * y + dy
                        if (cx < w && cy < h) {
                            val i = (cy * w + cx) * COMPS
                            for (c in 0 until COMPS) out[o + c] += level[i + c]
                            n++
                        }
                    }
                }
                for (c in 0 until COMPS) out[o + c] /= n
            }
        }
        return out
    }

    /**
     * One pull level: the filled chroma of a [w] x [h] level from its pushed statistics [level] and the filled
     * parent [parent] (`null` above the top: neutral). Returns `(chroma.rgb, 1)` per cell.
     */
    @Suppress("NestedBlockDepth")
    fun pull(level: FloatArray, w: Int, h: Int, parent: FloatArray?): FloatArray {
        val pw = (w + 1) / 2
        val ph = (h + 1) / 2
        val out = FloatArray(w * h * COMPS)
        val up = FloatArray(COMPS)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * COMPS
                if (parent == null) {
                    up[0] = NEUTRAL
                    up[1] = NEUTRAL
                    up[2] = NEUTRAL
                } else {
                    LowResGrid.bilinear(parent, pw, ph, COMPS, (x + HALF) / 2f - HALF, (y + HALF) / 2f - HALF, up)
                }
                val weight = level[i + 3]
                val lambda = min(weight / CONFIDENCE, 1f)
                for (c in 0 until CHANNELS) {
                    val own = if (weight > 0f) level[i + c] / weight else NEUTRAL
                    out[i + c] = up[c] + (own - up[c]) * lambda
                }
                out[i + 3] = 1f
            }
        }
        return out
    }

    /** The filled chroma grid (`gridWidth*gridHeight*4`, rgb = chromaticity) of balanced [rgb]: stats + push-pull. */
    fun chroma(rgb: FloatArray, grid: LowResGrid, p: HighlightParams): FloatArray {
        val levels = mutableListOf(stats(rgb, grid, p))
        val sizes = mutableListOf(grid.gridWidth to grid.gridHeight)
        while (sizes.last().first > 1 || sizes.last().second > 1) {
            val (w, h) = sizes.last()
            levels += push(levels.last(), w, h)
            sizes += (w + 1) / 2 to (h + 1) / 2
        }
        var filled: FloatArray? = null
        for (l in levels.indices.reversed()) {
            val (w, h) = sizes[l]
            filled = pull(levels[l], w, h, filled)
        }
        return checkNotNull(filled)
    }

    /** Reconstructs balanced [rgb] (`width*height*3`, in place) with the filled [chroma] grid of [grid]. */
    fun apply(rgb: FloatArray, grid: LowResGrid, p: HighlightParams, chroma: FloatArray) {
        val c = FloatArray(COMPS)
        val clip = floatArrayOf(p.clipR, p.clipG, p.clipB)
        val t = FloatArray(CHANNELS)
        for (y in 0 until grid.height) {
            val v = grid.toGrid(y)
            for (x in 0 until grid.width) {
                val i = (y * grid.width + x) * CHANNELS
                for (k in 0 until CHANNELS) t[k] = clipAmount(rgb[i + k], clip[k])
                if (t[0] == 0f && t[1] == 0f && t[2] == 0f) continue
                LowResGrid.bilinear(chroma, grid.gridWidth, grid.gridHeight, COMPS, grid.toGrid(x), v, c)
                reconstructPixel(rgb, i, t, c, clip)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun reconstructPixel(rgb: FloatArray, i: Int, t: FloatArray, c: FloatArray, clip: FloatArray) {
        var num = 0f
        var den = 0f
        for (k in 0 until CHANNELS) {
            val u = 1f - t[k]
            num += u * max(rgb[i + k], 0f)
            den += u * c[k]
        }
        if (den > MIN_SUM) {
            val scale = num / den
            for (k in 0 until CHANNELS) {
                val r = min(scale * c[k], RMAX * clip[k])
                rgb[i + k] += t[k] * max(r - rgb[i + k], 0f)
            }
        }
        // The darkest light of chroma C with every channel at least at its clip: its brightest channel is the level
        // of the neutral fallback (maxClip when C is neutral), continuous with the reconstruction around it.
        val full = max(clip[0] / max(c[0], MIN_SUM), max(clip[1] / max(c[1], MIN_SUM), clip[2] / max(c[2], MIN_SUM)))
        var n = max(rgb[i], max(rgb[i + 1], rgb[i + 2]))
        for (k in 0 until CHANNELS) n = max(n, min(full * c[k], RMAX * clip[k]))
        val f = min(t[0], min(t[1], t[2]))
        for (k in 0 until CHANNELS) rgb[i + k] += (n - rgb[i + k]) * f
    }

    private const val HALF = 0.5f
    private const val CHANNELS = 3
}
