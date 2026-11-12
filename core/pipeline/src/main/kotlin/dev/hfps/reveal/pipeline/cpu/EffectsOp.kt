@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.mask.MaskParams
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Effects group's numbers (docs/adr/0014), resolved once on the CPU and shared by the CPU reference and the GPU.
 *
 * Slot 12, on the working image in OkLab after Detail ([DetailOp] runs them in its final pass):
 * - **Texture** `L += T * t * c^2 / (c^2 + t^2)` with the band `t = L - B_sigma(L)`, `B` a separable cross-bilateral
 *   Gaussian (`sigma` = [TEXTURE_SIGMA] full-resolution pixels, scaled like Detail's blur; range sigma `c`): small and
 *   medium amplitudes (skin, foliage) are boosted or smoothed, the blur does not average across a strong edge and the
 *   shaping leaves strong details (`|t| >> c`) alone, so no halo. Negative values smooth the band.
 * - **Clarity** `L += C * w(B) * d * c^2 / (c^2 + d^2)`, `d = L - B`, with the edge-aware base `B` of [EffectsGrid]
 *   (a guided filter on the low-resolution grid): the local contrast of the midtones (`w(B) = clamp(4 B (1 - B))`)
 *   at about 1-3 % of the picture, with the strong edges in `B` (no halo).
 * - **Dehaze** in linear light, after the colour is back to RGB, from the dark channel prior (He, Sun, Tang 2009,
 *   "Single Image Haze Removal Using Dark Channel Prior", CVPR; 2011 TPAMI): haze model `I = J t + A (1 - t)`, the
 *   haze share `h` refined by a guided filter on the grid ([EffectsGrid]); positive
 *   `J = (I - A) / max(1 - w h, t0) + A`, negative adds haze `I + (A - I) * b (0.4 + 0.6 h)`.
 *
 * After the geometry operator, on the picture as exported ([post], [PostCropOp]): the vignette and the grain.
 */
data class EffectsParams(
    val textureGain: Float = 0f,
    val textureEdge2: Float = TEXTURE_EDGE * TEXTURE_EDGE,
    val textureBlur: List<Float> = emptyList(),
    val textureRange: Float = 1f / (2f * TEXTURE_EDGE * TEXTURE_EDGE),
    val clarityGain: Float = 0f,
    val dehazeOmega: Float = 0f,
    val dehazeBeta: Float = 0f,
    val post: PostParams = PostParams.NONE,
    /** The texture blur's attenuation at this render's scale (a mask's local texture uses it). */
    val textureAtt: Float = 1f,
    /** Masks change texture, clarity or dehaze somewhere (docs/adr/0022): their passes run. */
    val localTexture: Boolean = false,
    val localClarity: Boolean = false,
    val localDehaze: Boolean = false,
) {
    val textureActive: Boolean get() = textureGain != 0f || localTexture
    val clarityActive: Boolean get() = clarityGain != 0f || localClarity
    val dehazeActive: Boolean get() = dehazeOmega != 0f || dehazeBeta != 0f || localDehaze

    /** Clarity or dehaze: the low-resolution grid is needed. */
    val gridActive: Boolean get() = clarityActive || dehazeActive

    /** Slot 12 runs (texture, clarity or dehaze). */
    val localActive: Boolean get() = textureActive || gridActive

    /** The texture blur's reach in rendered pixels (0 when texture is off). */
    val textureReach: Int get() = if (textureActive) textureBlur.size - 1 else 0

    /** The texture change for the band [t] (GLSL `textureDelta`), with the gain [gain] (a mask's per-pixel one). */
    fun textureDelta(t: Float, gain: Float = textureGain): Float = gain * t * (textureEdge2 / (textureEdge2 + t * t))

    /** Clarity on the lightness [l] with the edge-aware base [b] (GLSL `clarity`). */
    fun clarity(l: Float, b: Float, gain: Float = clarityGain): Float {
        val w = min(max(4f * b * (1f - b), 0f), 1f)
        val d = l - b
        return l + gain * w * d * (CLARITY_EDGE2 / (CLARITY_EDGE2 + d * d))
    }

    /** Dehaze of the linear [rgb] in place with the haze share [hRaw] and the atmospheric light [air] (GLSL). */
    fun dehaze(
        rgb: FloatArray,
        hRaw: Float,
        air: FloatArray,
        omega: Float = dehazeOmega,
        beta: Float = dehazeBeta,
    ) {
        val h = min(max(hRaw, 0f), 1f)
        if (omega > 0f) {
            val t = max(1f - omega * h, DEHAZE_T0)
            for (c in 0 until 3) rgb[c] = (rgb[c] - air[c]) / t + air[c]
        } else {
            val f = beta * (HAZE_BASE + (1f - HAZE_BASE) * h)
            for (c in 0 until 3) rgb[c] = rgb[c] + (air[c] - rgb[c]) * f
        }
    }

    companion object {
        /** Texture's band: full-resolution Gaussian sigma. */
        const val TEXTURE_SIGMA: Float = 2.5f

        /** Texture's edge amplitude (OkLab L): bands much stronger than this are edges and are not changed. */
        const val TEXTURE_EDGE: Float = 0.05f

        /** Texture gain at +100 (boost) and -100 (smoothing: the band is removed). */
        const val TEXTURE_BOOST: Float = 1.5f
        const val TEXTURE_SMOOTH: Float = 1f

        /** Clarity gain at +-100 (local contrast doubled at +100, flattened at -100). */
        const val CLARITY_GAIN: Float = 1f

        /**
         * Clarity's detail rolloff `c^2` (OkLab L, c = 0.06): a detail much larger than this is the side of an edge the
         * base could not follow, and is not amplified (no halo).
         */
        const val CLARITY_EDGE2: Float = 0.0049f

        /** Dehaze at +100: the haze share removed (He et al.'s omega, 0.95). */
        const val DEHAZE_OMEGA: Float = 0.95f

        /**
         * Lowest transmission (He et al. use 0.1): at most a 4x stretch around the haze colour, so grey areas and noise
         * are not amplified without bound.
         */
        const val DEHAZE_T0: Float = 0.25f

        /** Haze added at -100 where the haze share is 1 (and [HAZE_BASE] of it where it is 0). */
        const val DEHAZE_ADD: Float = 0.6f
        const val HAZE_BASE: Float = 0.4f

        private const val PERCENT = 100f

        val NONE: EffectsParams = EffectsParams()

        /** Resolves [effects] for a render where one pixel covers [pixelScale] full-resolution pixels. */
        fun from(effects: Effects, pixelScale: Float, needs: Int = 0): EffectsParams {
            val r = RecipeRanges
            val ps = max(1f, pixelScale)
            val texture = r.texture.clamp(effects.texture) / PERCENT
            val (blur, attenuation) = DetailParams.gaussian(TEXTURE_SIGMA / ps)
            val dehaze = r.dehaze.clamp(effects.dehaze) / PERCENT
            val localTexture = needs and MaskParams.NEED_TEXTURE != 0
            return EffectsParams(
                textureGain = texture * (if (texture > 0f) TEXTURE_BOOST else TEXTURE_SMOOTH) * attenuation,
                textureBlur = if (texture != 0f || localTexture) blur else emptyList(),
                clarityGain = r.clarity.clamp(effects.clarity) / PERCENT * CLARITY_GAIN,
                dehazeOmega = if (dehaze > 0f) dehaze * DEHAZE_OMEGA else 0f,
                dehazeBeta = if (dehaze < 0f) -dehaze * DEHAZE_ADD else 0f,
                post = PostParams.from(effects),
                textureAtt = attenuation,
                localTexture = localTexture,
                localClarity = needs and MaskParams.NEED_CLARITY != 0,
                localDehaze = needs and MaskParams.NEED_DEHAZE != 0,
            )
        }
    }
}

/**
 * The low-resolution grid of clarity and dehaze (docs/adr/0014), built from the develop output **before Detail** (in
 * OkLab, float16 as stored) on the [LowResGrid] cells, in the order of the GPU passes (`gl/EffectsPasses`):
 *
 * 1. row segments of each cell (sums of `L`, `L^2`, `a`, `b`; minima of linear `r, g, b`), then cells (means, minima);
 * 2. the dark channel's 3x3 erosion of the cell minima (He et al.'s patch: a lone bright object is not haze);
 * 3. the atmospheric light `A`: the mean colour of the cells whose eroded dark channel is within [AIR_DELTA] of the
 *    largest (He et al. take the brightest of the top 0.1 %; a mean is stable against near ties), reduced in blocks of
 *    16 x 16 cells in row order (the GPU sums the same blocks in the same order);
 * 4. per cell the guide `I = mean L` with `mean L^2`, and the haze share `h = min_c(min_c / A_c)` clamped to [0, 1];
 * 5. one guided filter (He, Sun, Tang 2013, TPAMI) of both on the grid: box means of radius [RADIUS] cells,
 *    clarity `a = var / (var + eps_c)`, `b = mean I (1 - a)` (a self-guided edge-aware base), haze
 *    `a = cov(I, h) / (var + eps_h)`, `b = mean h - a mean I`, box means of the coefficients.
 *
 * Per pixel ([sample]) the four coefficients are upsampled from the four nearest cells, weighted bilinearly times a
 * range weight on each cell's mean `L` (joint bilateral upsampling as in ADR 0007), so a cell next to an edge does not
 * lend the other side its base. None of it depends on the Effects amounts: a slider drag only re-runs the final pass.
 */
class EffectsGrid private constructor(
    private val grid: LowResGrid,
    /** Cell means `(L, L^2, a, b)`. */
    val cells: FloatArray,
    /** Per cell `(aClarity, bClarity, aHaze, bHaze)`. */
    val coef: FloatArray,
    /** Atmospheric light, linear Rec.2020. */
    val air: FloatArray,
) {
    /** The four coefficients at pixel ([x], [y]) for the lightness [l] (GLSL `effectsCoef`), into [out]. */
    fun sample(x: Int, y: Int, l: Float, out: FloatArray) {
        val gw = grid.gridWidth
        val gh = grid.gridHeight
        val u = min(max(grid.toGrid(x), 0f), (gw - 1).toFloat())
        val v = min(max(grid.toGrid(y), 0f), (gh - 1).toFloat())
        val x0 = floor(u).toInt()
        val y0 = floor(v).toInt()
        val x1 = min(x0 + 1, gw - 1)
        val y1 = min(y0 + 1, gh - 1)
        val fx = u - x0
        val fy = v - y0
        var sw = 0f
        out.fill(0f)
        for (k in 0 until 4) {
            val right = k == 1 || k == 3
            val down = k >= 2
            val qx = if (right) x1 else x0
            val qy = if (down) y1 else y0
            val bx = if (right) fx else 1f - fx
            val by = if (down) fy else 1f - fy
            val q = qy * gw + qx
            val d = l - cells[q * 4]
            val range = max(StrictMath.exp((-(d * d) * RANGE_SCALE).toDouble()).toFloat(), RANGE_FLOOR)
            val w = bx * by * range
            sw += w
            for (c in 0 until COEFS) out[c] += w * coef[q * COEFS + c]
        }
        for (c in 0 until COEFS) out[c] /= sw
    }

    companion object {
        const val COEFS: Int = 4

        /** Box radius of the guided filter, in cells (about 1.4 % of the long edge each side). */
        const val RADIUS: Int = 3

        /** Clarity's guided-filter epsilon (OkLab L^2): a cell window with a larger L variance is an edge. */
        const val EPS_CLARITY: Float = 0.003f

        /** Dehaze's guided-filter epsilon. */
        const val EPS_HAZE: Float = 0.005f

        /** `1 / (2 sigma^2)` of the upsampling's range weight, sigma = 0.1 L. */
        const val RANGE_SCALE: Float = 50f
        const val RANGE_FLOOR: Float = 1e-3f

        /** Dark channel erosion radius in cells. */
        const val ERODE: Int = 1

        /** Reduction block of the atmospheric light search, cells per side. */
        const val AIR_BLOCK: Int = 16

        /** Cells whose eroded dark channel is this close to the largest give the atmospheric light. */
        const val AIR_DELTA: Float = 0.02f

        /** Smallest atmospheric light per channel (avoids a division by a black haze colour). */
        const val AIR_MIN: Float = 0.05f

        private const val LAB = 4

        /**
         * Builds the grid of [lab] (`(L, a, b, -)` per pixel, the develop output before Detail as float16) on [grid]
         * (the image's [LowResGrid]).
         */
        @Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
        fun build(lab: FloatArray, grid: LowResGrid): EffectsGrid {
            val w = grid.width
            val h = grid.height
            val gw = grid.gridWidth
            val gh = grid.gridHeight
            val cell = grid.cell
            val cells = FloatArray(gw * gh * 4)
            val mins = FloatArray(gw * gh * 4)
            val rgb = FloatArray(3)
            for (gy in 0 until gh) {
                val y0 = gy * cell
                val y1 = min(y0 + cell, h)
                for (gx in 0 until gw) {
                    val x0 = gx * cell
                    val x1 = min(x0 + cell, w)
                    val sum = FloatArray(4)
                    val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
                    for (y in y0 until y1) {
                        // One row segment (the GPU's rows pass), then added into the cell (its cells pass).
                        var s0 = 0f
                        var s1 = 0f
                        var s2 = 0f
                        var s3 = 0f
                        var m0 = Float.MAX_VALUE
                        var m1 = Float.MAX_VALUE
                        var m2 = Float.MAX_VALUE
                        for (x in x0 until x1) {
                            val q = (y * w + x) * LAB
                            val l = lab[q]
                            s0 += l
                            s1 += l * l
                            s2 += lab[q + 1]
                            s3 += lab[q + 2]
                            OkLab.toRgb(l, lab[q + 1], lab[q + 2], rgb)
                            m0 = min(m0, rgb[0])
                            m1 = min(m1, rgb[1])
                            m2 = min(m2, rgb[2])
                        }
                        sum[0] += s0
                        sum[1] += s1
                        sum[2] += s2
                        sum[3] += s3
                        mn[0] = min(mn[0], m0)
                        mn[1] = min(mn[1], m1)
                        mn[2] = min(mn[2], m2)
                    }
                    val n = ((x1 - x0) * (y1 - y0)).toFloat()
                    val o = (gy * gw + gx) * 4
                    for (c in 0 until 4) cells[o + c] = sum[c] / n
                    mins[o] = mn[0]
                    mins[o + 1] = mn[1]
                    mins[o + 2] = mn[2]
                    mins[o + 3] = min(mn[0], min(mn[1], mn[2]))
                }
            }
            // 2. Erosion (3x3 minimum, clamped to the grid).
            val eroded = FloatArray(gw * gh * 4) { Float.MAX_VALUE }
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val o = (gy * gw + gx) * 4
                    for (dy in -ERODE..ERODE) {
                        for (dx in -ERODE..ERODE) {
                            val q = ((gy + dy).coerceIn(0, gh - 1) * gw + (gx + dx).coerceIn(0, gw - 1)) * 4
                            for (c in 0 until 4) eroded[o + c] = min(eroded[o + c], mins[q + c])
                        }
                    }
                }
            }
            // 3. Atmospheric light: the largest eroded dark channel (16 x 16 block maxima down to one), then the mean
            // colour of every cell within AIR_DELTA of it (block sums of (L, a, b, 1), row order).
            var level = FloatArray(gw * gh) { eroded[it * 4 + 3] }
            var lw = gw
            var lh = gh
            while (lw > 1 || lh > 1) {
                val nw = (lw + AIR_BLOCK - 1) / AIR_BLOCK
                val nh = (lh + AIR_BLOCK - 1) / AIR_BLOCK
                val next = FloatArray(nw * nh)
                for (by in 0 until nh) {
                    for (bx in 0 until nw) {
                        var m = -Float.MAX_VALUE
                        for (y in by * AIR_BLOCK until min((by + 1) * AIR_BLOCK, lh)) {
                            for (x in bx * AIR_BLOCK until min((bx + 1) * AIR_BLOCK, lw)) m = max(m, level[y * lw + x])
                        }
                        next[by * nw + bx] = m
                    }
                }
                level = next
                lw = nw
                lh = nh
            }
            val threshold = level[0] - AIR_DELTA
            var sums = FloatArray(gw * gh * 4)
            for (i in 0 until gw * gh) {
                if (eroded[i * 4 + 3] >= threshold) {
                    sums[i * 4] = cells[i * 4]
                    sums[i * 4 + 1] = cells[i * 4 + 2]
                    sums[i * 4 + 2] = cells[i * 4 + 3]
                    sums[i * 4 + 3] = 1f
                }
            }
            lw = gw
            lh = gh
            while (lw > 1 || lh > 1) {
                val nw = (lw + AIR_BLOCK - 1) / AIR_BLOCK
                val nh = (lh + AIR_BLOCK - 1) / AIR_BLOCK
                val next = FloatArray(nw * nh * 4)
                for (by in 0 until nh) {
                    for (bx in 0 until nw) {
                        val o = (by * nw + bx) * 4
                        for (y in by * AIR_BLOCK until min((by + 1) * AIR_BLOCK, lh)) {
                            for (x in bx * AIR_BLOCK until min((bx + 1) * AIR_BLOCK, lw)) {
                                for (c in 0 until 4) next[o + c] += sums[(y * lw + x) * 4 + c]
                            }
                        }
                    }
                }
                sums = next
                lw = nw
                lh = nh
            }
            val air = FloatArray(3)
            OkLab.toRgb(sums[0] / sums[3], sums[1] / sums[3], sums[2] / sums[3], air)
            for (c in 0 until 3) air[c] = max(air[c], AIR_MIN)
            // 4. Moments (I, I^2, h, I h).
            val moments = FloatArray(gw * gh * 4)
            for (i in 0 until gw * gh) {
                val hz = min(eroded[i * 4] / air[0], min(eroded[i * 4 + 1] / air[1], eroded[i * 4 + 2] / air[2]))
                val haze = min(max(hz, 0f), 1f)
                val mi = cells[i * 4]
                moments[i * 4] = mi
                moments[i * 4 + 1] = cells[i * 4 + 1]
                moments[i * 4 + 2] = haze
                moments[i * 4 + 3] = mi * haze
            }
            // 5. Box H, box V + coefficients, box H, box V.
            val a = box(moments, gw, gh, 1, 0, coefStep = false)
            val b = box(a, gw, gh, 0, 1, coefStep = true)
            val c = box(b, gw, gh, 1, 0, coefStep = false)
            val coef = box(c, gw, gh, 0, 1, coefStep = false)
            return EffectsGrid(grid, cells, coef, air)
        }

        /** One axis of the box mean of radius [RADIUS] (GLSL `EFFECTS_BOX_FRAG`), optionally the coefficient step. */
        @Suppress("LongParameterList", "NestedBlockDepth")
        private fun box(src: FloatArray, gw: Int, gh: Int, dx: Int, dy: Int, coefStep: Boolean): FloatArray {
            val out = FloatArray(src.size)
            val s = FloatArray(4)
            for (y in 0 until gh) {
                for (x in 0 until gw) {
                    s.fill(0f)
                    var n = 0
                    for (k in -RADIUS..RADIUS) {
                        val qx = x + dx * k
                        val qy = y + dy * k
                        if (qx in 0 until gw && qy in 0 until gh) {
                            val q = (qy * gw + qx) * 4
                            for (c in 0 until 4) s[c] += src[q + c]
                            n++
                        }
                    }
                    val o = (y * gw + x) * 4
                    for (c in 0 until 4) out[o + c] = s[c] / n
                    if (coefStep) {
                        val mi = out[o]
                        val variance = max(out[o + 1] - mi * mi, 0f)
                        val aC = variance / (variance + EPS_CLARITY)
                        val cov = out[o + 3] - mi * out[o + 2]
                        val aH = cov / (variance + EPS_HAZE)
                        val bH = out[o + 2] - aH * mi
                        out[o] = aC
                        out[o + 1] = mi * (1f - aC)
                        out[o + 2] = aH
                        out[o + 3] = bH
                    }
                }
            }
            return out
        }
    }
}

/**
 * The post-crop finish (docs/adr/0014): vignette then grain, on the geometry operator's output (so they follow the
 * crop, turns and flips), as linear Rec.2020, before the output transform. Positions are output pixel centres
 * normalised by the output size, so the preview, the 100 % tiles and an export of any size agree.
 *
 * - **Vignette**: the distance `d` from the centre on a shape set by roundness (0: an ellipse fitting the frame;
 *   +100: a circle; -100: a superellipse close to the frame's rectangle; the corners are always at `d = sqrt 2`),
 *   mask `w = smoothstep(r0 - f/2, r0 + f/2, d)` (midpoint `r0`, feather `f`), applied to the lightness
 *   `p = cbrt(Y)` as one gain on the three channels (hue kept): darken `p (1 - k w)` with highlights protection,
 *   lighten `p + (1 - p) k w`.
 * - **Grain**: value noise on a lattice whose cell is a fixed fraction of the output's long edge ([PostParams.from]:
 *   size), smooth (smoothstep) interpolation, plus an octave twice as fine for roughness, seeded by [GRAIN_SEED] with
 *   the dither's integer hash (identical on CPU and GPU), added to the lightness weighted towards the midtones
 *   `4 p (1 - p)`. The same lattice point always gets the same value: the preview, 100 % and the export show the
 *   same grain pattern, each sampled at its own resolution.
 */
data class PostParams(
    val vignette: Float = 0f,
    val midpoint: Float = 0f,
    val feather: Float = 0f,
    val highlights: Float = 0f,
    val round: Float = 0f,
    val exponent: Float = 2f,
    val grain: Float = 0f,
    /** Lattice cells per output long edge (`1 / cell fraction`). */
    val grainCells: Float = 1f,
    val roughness: Float = 0f,
) {
    val vignetteActive: Boolean get() = vignette != 0f
    val grainActive: Boolean get() = grain != 0f
    val active: Boolean get() = vignetteActive || grainActive

    /** `sqrt 2 / 2^(1/e)`: keeps the corners at `sqrt 2` for every exponent. */
    val norm: Float get() = (sqrt(2.0) / StrictMath.pow(2.0, 1.0 / exponent)).toFloat()

    /** Lattice cells per output pixel for an output whose long edge is [longEdge] (`u = o * grainScale`). */
    fun grainScale(longEdge: Int): Float = (grainCells.toDouble() / longEdge).toFloat()

    companion object {
        val NONE: PostParams = PostParams()

        /** Darkening at -100 (lightness x0.2 where the mask is 1). */
        const val VIGNETTE_DARK: Float = 0.8f

        /** Lightening at +100 (70 % of the way to white). */
        const val VIGNETTE_LIGHT: Float = 0.7f

        /** Grain amplitude at 100 (lightness, before the midtone weight). */
        const val GRAIN_AMPLITUDE: Float = 0.08f

        /** Grain cell at size 0 and 100, in pixels of a 3000 px long edge (0.75-4 px). */
        const val GRAIN_MIN_PX: Float = 0.75f
        const val GRAIN_MAX_PX: Float = 4f
        const val GRAIN_REFERENCE_EDGE: Float = 3000f

        /** The grain's seed (fixed: the same edit gives the same grain everywhere). */
        const val GRAIN_SEED: Int = 0x6A1F

        private const val PERCENT = 100f

        fun from(e: Effects): PostParams {
            val r = RecipeRanges
            val amount = r.vignetteAmount.clamp(e.vignetteAmount) / PERCENT
            val roundness = r.vignetteRoundness.clamp(e.vignetteRoundness) / PERCENT
            val size = r.grainSize.clamp(e.grainSize) / PERCENT
            val cellPx = GRAIN_MIN_PX + (GRAIN_MAX_PX - GRAIN_MIN_PX) * size
            return PostParams(
                vignette = if (amount < 0f) amount * VIGNETTE_DARK else amount * VIGNETTE_LIGHT,
                midpoint = 0.4f + 1.1f * (r.vignetteMidpoint.clamp(e.vignetteMidpoint) / PERCENT),
                feather = 0.05f + 1.15f * (r.vignetteFeather.clamp(e.vignetteFeather) / PERCENT),
                highlights = r.vignetteHighlights.clamp(e.vignetteHighlights) / PERCENT,
                round = max(roundness, 0f),
                exponent = 2f + 6f * max(-roundness, 0f),
                grain = r.grainAmount.clamp(e.grainAmount) / PERCENT * GRAIN_AMPLITUDE,
                grainCells = GRAIN_REFERENCE_EDGE / cellPx,
                roughness = r.grainRoughness.clamp(e.grainRoughness) / PERCENT,
            )
        }
    }
}

/** [PostParams] on an output image (CPU reference of the output passes' `postCrop`). */
object PostCropOp {
    /** Applies [p] to [rgb] (`width*height*3`, the geometry operator's output) in place. */
    fun apply(rgb: FloatArray, width: Int, height: Int, p: PostParams) {
        if (!p.active) return
        val gk = p.grainScale(max(width, height))
        val c = FloatArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 3
                c[0] = rgb[i]
                c[1] = rgb[i + 1]
                c[2] = rgb[i + 2]
                pixel(c, x + 0.5f, y + 0.5f, width.toFloat(), height.toFloat(), gk, p)
                rgb[i] = c[0]
                rgb[i + 1] = c[1]
                rgb[i + 2] = c[2]
            }
        }
    }

    /** One pixel at output point ([ox], [oy]) of a [w] x [h] output; [gk] = [PostParams.grainScale] (GLSL). */
    @Suppress("LongParameterList")
    fun pixel(c: FloatArray, ox: Float, oy: Float, w: Float, h: Float, gk: Float, p: PostParams) {
        if (p.vignetteActive) {
            val hx = w * 0.5f
            val hy = h * 0.5f
            val rc = sqrt((hx * hx + hy * hy) * 0.5f)
            val sx = 1f / hx + (1f / rc - 1f / hx) * p.round
            val sy = 1f / hy + (1f / rc - 1f / hy) * p.round
            val vx = abs((ox - hx) * sx)
            val vy = abs((oy - hy) * sy)
            val d = pow(pow(vx, p.exponent) + pow(vy, p.exponent), 1f / p.exponent) * p.norm
            val m = LowResGrid.smoothstep(p.midpoint - p.feather * 0.5f, p.midpoint + p.feather * 0.5f, d)
            val y = luma(c)
            val l = cbrt(y)
            val s = if (p.vignette < 0f) {
                l * (1f + p.vignette * m * (1f - p.highlights * LowResGrid.smoothstep(0.5f, 0.9f, l)))
            } else {
                l + max(1f - l, 0f) * p.vignette * m
            }
            gain(c, l, s, neutralLift = p.vignette > 0f)
        }
        if (p.grainActive) {
            val n0 = valueNoise(ox * gk, oy * gk, PostParams.GRAIN_SEED)
            val n1 = valueNoise(ox * gk * 2f + OCTAVE_OFFSET, oy * gk * 2f + OCTAVE_OFFSET, PostParams.GRAIN_SEED + 1)
            val n = (n0 + p.roughness * n1) / sqrt(1f + p.roughness * p.roughness)
            val y = luma(c)
            val l = cbrt(y)
            val weight = min(max(4f * l * (1f - l), 0f), 1f)
            gain(c, l, l + p.grain * NOISE_SCALE * n * weight, neutralLift = false)
        }
    }

    /** Takes the lightness of [c] from [l] to [target] with one gain on the channels (GLSL `lightnessGain`). */
    private fun gain(c: FloatArray, l: Float, target: Float, neutralLift: Boolean) {
        val t = max(target, 0f)
        if (l > MIN_L) {
            val g = t / l
            val g3 = g * g * g
            c[0] *= g3
            c[1] *= g3
            c[2] *= g3
        } else if (neutralLift) {
            val v = t * t * t
            c[0] = v
            c[1] = v
            c[2] = v
        }
    }

    /** Smoothly interpolated lattice noise in [-1, 1] at ([u], [v]) (GLSL `valueNoise`). */
    fun valueNoise(u: Float, v: Float, seed: Int): Float {
        val fu = floor(u)
        val fv = floor(v)
        val ix = fu.toInt()
        val iy = fv.toInt()
        val tx = u - fu
        val ty = v - fv
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        val a = lattice(ix, iy, seed)
        val b = lattice(ix + 1, iy, seed)
        val d = lattice(ix, iy + 1, seed)
        val e = lattice(ix + 1, iy + 1, seed)
        val top = a + (b - a) * sx
        val bottom = d + (e - d) * sx
        return top + (bottom - top) * sy
    }

    /** The lattice value at ([x], [y]): a triangular-distributed number in [-1, 1] from the dither's hash. */
    fun lattice(x: Int, y: Int, seed: Int): Float {
        val h = Dither.hash(x + Dither.hash(y + seed * GOLDEN_RATIO_32))
        return ((h and LOW16) + (h ushr SHIFT16)) * INV_65535 - 1f
    }

    /** Rec.2020 luminance (`luma` in the shaders). */
    fun luma(c: FloatArray): Float = 0.2627f * c[0] + 0.678f * c[1] + 0.0593f * c[2]

    private fun cbrt(y: Float): Float = if (y > 0f) StrictMath.cbrt(y.toDouble()).toFloat() else 0f

    private fun pow(x: Float, e: Float): Float = StrictMath.pow(x.toDouble(), e.toDouble()).toFloat()

    private fun abs(x: Float): Float = kotlin.math.abs(x)

    private const val MIN_L = 1e-4f
    private const val GOLDEN_RATIO_32 = -1640531527
    private const val LOW16 = 0xFFFF
    private const val SHIFT16 = 16
    private const val INV_65535 = 1f / 65535f

    /** The rough octave's lattice offset, so its points never coincide with the base lattice's. */
    const val OCTAVE_OFFSET: Float = 0.37f

    /** Scales the interpolated noise (standard deviation about 0.3) to about 0.5. */
    const val NOISE_SCALE: Float = 1.6f
}
