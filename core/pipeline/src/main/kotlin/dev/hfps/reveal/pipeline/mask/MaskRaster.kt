@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.mask

import dev.hfps.reveal.pipeline.mask.MaskPlan.Companion.COLOUR_L_WEIGHT
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The CPU reference of the masks' coverage (docs/adr/0022, "Coverage"), mirrored by `gl/MaskShaders.kt`. Works on a
 * region of the image of a [MaskPlan] (origin [Region.x], [Region.y] in that image's pixels), at pixel centres.
 *
 * Component values, from the [MaskPlan.PackedComponent] numbers (`q` = the pixel centre in image pixels):
 * - **linear**: `t = (q - p0) . k`, `v = 1 - sstep(e0, e1, t)` (`p0 = d0,d1`, `k = d2,d3`, `e = d4,d5`);
 * - **radial**: `u = (q - c) . (cos, sin)`, `w = (q - c) . (-sin, cos)`, `r = |(u / rx, w / ry)|`,
 *   `v = 1 - sstep(1 - feather, 1, r)`, `1 - v` outside (`c = d0,d1`, `cos, sin = d2,d3`, `1/rx, 1/ry = d4,d5`,
 *   `1 - feather = d6`, inside = d7);
 * - **brush**: the stroke raster ([brush]);
 * - **colour range**: on the pre-local OkLab pixel, `dist = min_s sqrt(0.25 dL^2 + da^2 + db^2)`,
 *   `v = 1 - sstep(r0, r1, dist)` (samples in d3..d17, count d0, `r0, r1 = d1,d2`);
 * - **luminance range**: `x = clamp(L, 0, 1)`, `v = sstep(d0, d1, x) (1 - sstep(d2, d3, x))`, a cut skipped when d4 / d5
 *   is 0;
 * - **AI**: the source's mask, bilinear with explicit weights; skipped while not ready.
 *
 * Then `v` is clamped to 0..1, inverted when asked, and combined from `cov = 0`: add `max(cov, v)`, subtract
 * `cov (1 - v)`, intersect `cov v`; the result is stored as float16 ([MaskMath.store]).
 */
object MaskRaster {
    /** A region of the plan's image: origin and size in its pixels. */
    data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The brush raster of [dabs] over [region] (float16 as stored): per pixel, in dab order, painting
     * `cov += (1 - cov) a`, erasing `cov *= 1 - a`, `a = flow (1 - sstep(inner, 1, d / r))`.
     */
    fun brush(dabs: Dabs, region: Region): FloatArray {
        val w = region.width
        val out = FloatArray(w * region.height)
        val box = IntArray(4)
        val d = dabs.data
        for (k in 0 until dabs.size) {
            dabs.box(k, box)
            val x0 = max(box[0], region.x)
            val y0 = max(box[1], region.y)
            val x1 = min(box[2], region.x + w - 1)
            val y1 = min(box[3], region.y + region.height - 1)
            val o = k * Dabs.DAB_FLOATS
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val a = dabAlpha(d, o, x, y)
                    if (a <= 0f) continue
                    val i = (y - region.y) * w + (x - region.x)
                    out[i] = if (d[o + 5] != 0f) out[i] * (1f - a) else out[i] + (1f - out[i]) * a
                }
            }
        }
        for (i in out.indices) out[i] = MaskMath.store(out[i])
        return out
    }

    /** One dab's alpha at pixel ([x], [y]) (GLSL `dabAlpha`). */
    fun dabAlpha(d: FloatArray, o: Int, x: Int, y: Int): Float {
        val dx = x + 0.5f - d[o]
        val dy = y + 0.5f - d[o + 1]
        val r = sqrt(dx * dx + dy * dy) / d[o + 2]
        return d[o + 4] * (1f - MaskMath.sstep(d[o + 3], 1f, r))
    }

    /**
     * The coverage of every mask of [plan] over [region] (one array per coverage slot, float16 as stored). [pre] is the
     * image before local adjustments in OkLab (`(L, a, b, 0)` per pixel of the region, float16, what the develop pass
     * writes), needed when [MaskPlan.needsPre]; [brushes] the rasters of [plan]'s brush layers over [region] (computed
     * when null); [ai] the AI layers' masks (null = not ready: skipped).
     */
    fun coverage(
        plan: MaskPlan,
        region: Region,
        pre: FloatArray?,
        ai: List<AiMask?> = plan.ai.map { null },
        brushes: List<FloatArray> = plan.brushes.map { brush(it, region) },
    ): List<FloatArray> {
        require(!plan.needsPre || pre != null) { "a range mask needs the pre-local image" }
        val n = region.width * region.height
        return plan.components.map { components ->
            val out = FloatArray(n)
            for (i in 0 until n) {
                val x = region.x + i % region.width
                val y = region.y + i / region.width
                var cov = 0f
                for (c in components) {
                    val v = value(c, plan, x, y, i, pre, ai, brushes) ?: continue
                    cov = combine(cov, if (c.invert) 1f - v else v, c.op)
                }
                out[i] = MaskMath.store(cov)
            }
            out
        }
    }

    /** The combination of the coverage so far with a component's value (GLSL `combine`). */
    fun combine(cov: Float, v: Float, op: Int): Float = when (op) {
        0 -> max(cov, v)
        1 -> cov * (1f - v)
        else -> cov * v
    }

    /** One component's value at image pixel ([x], [y]) (region index [i]), clamped to 0..1; null = skipped. */
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount")
    fun value(
        c: MaskPlan.PackedComponent,
        plan: MaskPlan,
        x: Int,
        y: Int,
        i: Int,
        pre: FloatArray?,
        ai: List<AiMask?>,
        brushes: List<FloatArray>,
    ): Float? {
        val d = c.data
        val qx = x + 0.5f
        val qy = y + 0.5f
        val v = when (c.type) {
            MaskPlan.TYPE_LINEAR -> 1f - MaskMath.sstep(d[4], d[5], (qx - d[0]) * d[2] + (qy - d[1]) * d[3])

            MaskPlan.TYPE_RADIAL -> {
                val dx = qx - d[0]
                val dy = qy - d[1]
                val u = (dx * d[2] + dy * d[3]) * d[4]
                val w = (-dx * d[3] + dy * d[2]) * d[5]
                val inside = 1f - MaskMath.sstep(d[6], 1f, sqrt(u * u + w * w))
                if (d[7] != 0f) inside else 1f - inside
            }

            MaskPlan.TYPE_BRUSH -> brushes[c.layer][i]

            MaskPlan.TYPE_COLOUR -> {
                val p = checkNotNull(pre)
                val l = p[i * 4]
                val a = p[i * 4 + 1]
                val b = p[i * 4 + 2]
                var best = Float.MAX_VALUE
                for (s in 0 until d[0].toInt()) {
                    val dl = l - d[3 + 3 * s]
                    val da = a - d[4 + 3 * s]
                    val db = b - d[5 + 3 * s]
                    best = min(best, COLOUR_L_WEIGHT * dl * dl + da * da + db * db)
                }
                1f - MaskMath.sstep(d[1], d[2], sqrt(best))
            }

            MaskPlan.TYPE_LUMA -> {
                val l = checkNotNull(pre)[i * 4].coerceIn(0f, 1f)
                val low = if (d[4] != 0f) MaskMath.sstep(d[0], d[1], l) else 1f
                val high = if (d[5] != 0f) 1f - MaskMath.sstep(d[2], d[3], l) else 1f
                low * high
            }

            MaskPlan.TYPE_AI -> sampleAi(ai[c.layer] ?: return null, plan.imageWidth, plan.imageHeight, x, y)

            else -> return null
        }
        return v.coerceIn(0f, 1f)
    }

    /** An AI mask at image pixel ([x], [y]) of a [w] x [h] image: bilinear with explicit weights, taps clamped. */
    fun sampleAi(mask: AiMask, w: Int, h: Int, x: Int, y: Int): Float {
        val u = (x + 0.5f) * (mask.width.toFloat() / w) - 0.5f
        val v = (y + 0.5f) * (mask.height.toFloat() / h) - 0.5f
        val fu = floor(u)
        val fv = floor(v)
        val tx = u - fu
        val ty = v - fv
        val x0 = fu.toInt()
        val y0 = fv.toInt()
        fun at(px: Int, py: Int): Float =
            mask.values[py.coerceIn(0, mask.height - 1) * mask.width + px.coerceIn(0, mask.width - 1)]
        val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
        val bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
        return top + (bottom - top) * ty
    }
}
