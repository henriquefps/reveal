@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Detail operator's numbers (slot 11, docs/adr/0012), resolved once on the CPU for one recipe at one render scale
 * and shared by the CPU reference ([DetailOp]) and the GPU passes (`gl/DetailPasses`).
 *
 * **Scale.** The recipe's lengths are full-resolution pixels. [pixelScale] is how many full-resolution pixels one
 * rendered pixel covers: 1 for the 100 % tiles and the export, the proxy's downscale (about 2.5 for a 24 MP photo on a
 * 2400 px proxy) for the editor's fit view. Each wavelet level keeps its full-resolution spatial scale (its step is
 * divided by [pixelScale] and rounded; levels finer than a rendered pixel are skipped: the proxy's binning already
 * averaged that noise away), the sharpening radius is divided by it (a radius below half a rendered pixel is held there
 * and the amount reduced in proportion), and the masking gradient is measured per full-resolution pixel. So the fit
 * view is a sensible approximation of what 100 % shows, never a different look.
 *
 * **Noise model.** The luminance and colour thresholds are multiplied by [noiseFactor] ([NoiseModel]: the photo's
 * expected noise relative to the reference capture), so an amount removes the same share of the noise at any ISO.
 *
 * @property lumStep rendered-pixel step of each luminance level ([LEVELS]); 0 = the level does not run.
 * @property lumTau shrinkage threshold of the luminance detail at each level (OkLab L).
 * @property lumEdge `1 / (2 sigma^2)` of the luminance edge stop of each level's smoothing.
 * @property chromaStep step of each colour level in **half-resolution** texels (colour runs on a 2x2-binned copy);
 *   0 = the level does not run.
 * @property chromaRange `1 / (2 sigma^2)` of the chroma (a, b) range weight of the colour levels.
 * @property chromaMix how much of each colour level's smoothing is kept.
 * @property chromaLuma `1 / (2 sigma^2)` of the luminance guard of the colour smoothing (colour does not cross L
 *   edges).
 * @property fineRange `1 / (2 sigma^2)` of the edge test that keeps the full-resolution colour residual (strong colour
 *   edges finer than the half-resolution copy survive, fine colour noise does not); 0 when colour NR is off.
 * @property blur the sharpening Gaussian's half kernel (index 0 = centre, normalised over `-R..R`).
 * @property sharpenGain multiplier of the shaped high-pass.
 * @property coring2 squared texture-coring threshold (0 = every amplitude is sharpened).
 * @property haloInv `1 / c` of the halo limit `h / (1 + |h| / c)`.
 * @property maskLow masking ramp start (gradient of L per full-resolution pixel); [maskHigh] 0 = no mask.
 * @property gradientScale full-resolution pixels per rendered pixel, inverted (the masking gradient's unit).
 * @property noiseFactor the noise model's factor the thresholds were scaled by.
 */
data class DetailParams(
    val lumStep: List<Int>,
    val lumTau: List<Float>,
    val lumEdge: List<Float>,
    val chromaStep: List<Int>,
    val chromaRange: Float,
    val chromaMix: List<Float>,
    val chromaLuma: Float,
    val fineRange: Float,
    val blur: List<Float>,
    val sharpenGain: Float,
    val coring2: Float,
    val haloInv: Float,
    val maskLow: Float,
    val maskHigh: Float,
    val gradientScale: Float,
    val noiseFactor: Float = 1f,
) {
    /** True when luminance noise reduction runs (at least one level). */
    val lumActive: Boolean get() = lumStep.indices.any { lumLevelActive(it) }

    /** True when colour noise reduction runs (the half-resolution copy, its levels and the residual test). */
    val chromaActive: Boolean get() = fineRange > 0f

    /** True when a noise reduction runs. */
    val nrActive: Boolean get() = lumActive || chromaActive

    /** True when sharpening runs. */
    val sharpenActive: Boolean get() = sharpenGain != 0f

    /** True when the operator changes anything; otherwise it is skipped (bit-exact identity). */
    val active: Boolean get() = nrActive || sharpenActive

    /** True when luminance level [i] runs. */
    fun lumLevelActive(i: Int): Boolean = lumStep[i] > 0 && lumTau[i] > 0f

    /** True when colour level [i] runs. */
    fun chromaLevelActive(i: Int): Boolean = chromaActive && chromaStep[i] > 0 && chromaMix[i] > 0f

    /** Sharpening blur half-width in rendered pixels. */
    val blurRadius: Int get() = blur.size - 1

    /** True when the masking ramp is on. */
    val masking: Boolean get() = maskHigh > 0f

    /** How far (rendered pixels) the denoised luminance of a pixel reads: two taps of each level's step. */
    val lumReach: Int get() = lumStep.indices.sumOf { if (lumLevelActive(it)) 2 * lumStep[it] else 0 }

    /**
     * How far (rendered pixels) a pixel's colour reads: the 2x2 binning, the half-resolution levels (two taps of
     * their step, two rendered pixels per texel) and the bilinear upsampling.
     */
    val chromaReach: Int
        get() = if (!chromaActive) {
            0
        } else {
            CHROMA_MARGIN + 4 * chromaStep.indices.sumOf { if (chromaLevelActive(it)) chromaStep[it] else 0 }
        }

    /**
     * How far (rendered pixels) a result pixel reads, the blur of [blurReach] (sharpening and Effects' texture share
     * it) included. Tiles need this much apron for the core to equal a whole-image render.
     */
    fun reach(blurReach: Int): Int = max(lumReach + blurReach, chromaReach)

    /** The sharpening blur's reach (radius, plus one for the masking gradient), 0 when sharpening is off. */
    val sharpenReach: Int get() = if (sharpenActive) blurRadius + (if (masking) 1 else 0) else 0

    companion object {
        /** Wavelet levels (full-resolution steps 1, 2, 4, 8, 16). */
        const val LEVELS: Int = 5

        /** Largest reach at full resolution of Detail and Effects together (`DevelopParams.reach`). */
        const val MAX_REACH: Int = 2 * 31 + 9 + 1

        /** Luminance shrinkage threshold at amount 100 (OkLab L; high-ISO noise is about 0.01-0.03). */
        const val LUM_TAU_MAX: Float = 0.06f

        /** Exponent of the amount -> threshold curve (calibrated on CC0 ISO 2000 and 12800 files, docs/adr/0012). */
        private const val LUM_CURVE = 1.5

        /** Per-level share of the luminance threshold (finer levels carry more of white-ish noise). */
        val LUM_LEVEL: FloatArray = floatArrayOf(1f, 0.62f, 0.38f, 0.22f, 0.13f)

        /** Chroma range sigma at amount 100 (OkLab a/b distance). */
        const val CHROMA_SIGMA_MAX: Float = 0.06f

        /** Sharpening gain at amount 100. */
        const val SHARPEN_GAIN: Float = 1.2f

        /** Texture-coring threshold at detail 0 (OkLab L). */
        const val CORING_MAX: Float = 0.006f

        /** Masking gradient (OkLab L per full-resolution pixel) where the mask is fully on at masking 100. */
        const val MASK_MAX: Float = 0.06f

        /** Smallest rendered blur sigma; smaller radii keep this and lower the amount in proportion. */
        const val MIN_SIGMA: Float = 0.5f

        /**
         * A level runs when its rendered step is at least one pixel (texel for colour): finer ones were averaged away
         * by the proxy's binning (or, for colour, by the 2x2 binning of the half-resolution copy).
         */
        private const val MIN_STEP: Float = 1f

        /** Rendered pixels the colour path reads beyond its levels: the binning (1), the upsampling (2), rounded up. */
        private const val CHROMA_MARGIN = 4

        /**
         * The residual's edge test uses twice the colour sigma (a quarter of the range weight): only a residual well
         * above the noise is a colour edge worth keeping.
         */
        private const val FINE_RANGE = 0.25f

        private const val PERCENT = 100f
        private const val FINE_LEVELS = 2
        private const val BLUR_SIGMAS = 3f

        /** The identity (nothing runs). */
        val NONE: DetailParams = from(Detail(), 1f)

        /**
         * Resolves [detail] for a render where one pixel covers [pixelScale] full-resolution pixels (>= 1), with the
         * noise model's [noiseFactor] ([NoiseModel.factor]).
         */
        @Suppress("LongMethod")
        fun from(detail: Detail, pixelScale: Float, noiseFactor: Float = 1f): DetailParams {
            val r = RecipeRanges
            val ps = max(1f, pixelScale)
            // The threshold grows as amount^1.5: the low half of the slider stays gentle for the moderate noise of
            // mid ISOs (a texture of the same amplitude as the noise survives), the top keeps ISO 12800's strength.
            val lumLinear = r.nrLuminance.clamp(detail.nrLuminance) / PERCENT
            val lum = StrictMath.pow(lumLinear.toDouble(), LUM_CURVE).toFloat() * noiseFactor
            val lumDetail = r.nrLuminanceDetail.clamp(detail.nrLuminanceDetail) / PERCENT
            val lumContrast = r.nrLuminanceContrast.clamp(detail.nrLuminanceContrast) / PERCENT
            val col = r.nrColour.clamp(detail.nrColour) / PERCENT
            val colDetail = r.nrColourDetail.clamp(detail.nrColourDetail) / PERCENT
            val smooth = r.nrColourSmoothness.clamp(detail.nrColourSmoothness) / PERCENT
            val steps = ArrayList<Int>(LEVELS)
            val taus = ArrayList<Float>(LEVELS)
            val edges = ArrayList<Float>(LEVELS)
            val chromaSteps = ArrayList<Int>(LEVELS)
            val mixes = ArrayList<Float>(LEVELS)
            val sigmaC = col * noiseFactor * CHROMA_SIGMA_MAX * mix(1.6f, 0.6f, colDetail)
            for (i in 0 until LEVELS) {
                val t = (1 shl i) / ps
                steps += if (t < MIN_STEP) 0 else max(1, t.roundToInt())
                val half = t * 0.5f
                chromaSteps += if (half < MIN_STEP) 0 else max(1, half.roundToInt())
                val shape = if (i < FINE_LEVELS) mix(1.5f, 0.3f, lumDetail) else 1f - 0.85f * lumContrast
                val tau = lum * LUM_TAU_MAX * LUM_LEVEL[i] * shape
                taus += tau
                val sigmaE = max(tau * mix(8f, 3f, lumDetail), 1e-4f)
                edges += if (tau > 0f) 1f / (2f * sigmaE * sigmaE) else 0f
                val levelMix = when (i) {
                    3 -> min(1f, smooth * 2f)
                    4 -> max(0f, smooth * 2f - 1f)
                    else -> 1f
                }
                mixes += if (col > 0f) levelMix else 0f
            }
            val sigmaCl = mix(0.25f, 0.04f, colDetail)
            val range = if (sigmaC > 0f) 1f / (2f * sigmaC * sigmaC) else 0f
            val amount = r.sharpenAmount.clamp(detail.sharpenAmount) / PERCENT
            val (blur, attenuation) = gaussian(r.sharpenRadius.clamp(detail.sharpenRadius) / ps)
            val sharpDetail = r.sharpenDetail.clamp(detail.sharpenDetail) / PERCENT
            val coring = (1f - sharpDetail) * CORING_MAX
            val halo = mix(0.04f, 0.5f, sharpDetail)
            val masking = r.sharpenMasking.clamp(detail.sharpenMasking) / PERCENT
            val maskHigh = masking * MASK_MAX
            return DetailParams(
                lumStep = steps,
                lumTau = taus,
                lumEdge = edges,
                chromaStep = chromaSteps,
                chromaRange = range,
                chromaMix = mixes,
                chromaLuma = 1f / (2f * sigmaCl * sigmaCl),
                fineRange = range * FINE_RANGE,
                blur = blur,
                sharpenGain = amount * SHARPEN_GAIN * attenuation,
                coring2 = coring * coring,
                haloInv = 1f / halo,
                maskLow = maskHigh * 0.5f,
                maskHigh = maskHigh,
                gradientScale = 1f / ps,
                noiseFactor = noiseFactor,
            )
        }

        /**
         * Normalised Gaussian half kernel for a rendered [sigma] (radius `ceil(3 sigma)`), computed in double, and the
         * attenuation of the effect when [sigma] is below [MIN_SIGMA] (the kernel is then held at [MIN_SIGMA]).
         */
        fun gaussian(sigma: Float): Pair<List<Float>, Float> {
            val s = max(sigma, MIN_SIGMA)
            val radius = ceil(BLUR_SIGMAS * s).toInt()
            val w = DoubleArray(radius + 1) { StrictMath.exp(-(it * it) / (2.0 * s * s)) }
            val sum = w[0] + 2.0 * (1..radius).sumOf { w[it] }
            return w.map { (it / sum).toFloat() } to (if (sigma < MIN_SIGMA) sigma / MIN_SIGMA else 1f)
        }

        private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}

/**
 * Slots 11 and 12 on the working image after the colour operators, in OkLab (`L` for luminance, `a, b` for colour): the
 * CPU reference of `gl/DetailShaders` + `gl/EffectsShaders`, pass for pass (same pass structure, same tap order, same
 * float16 storage). Neighbourhood taps are clamped to the image.
 *
 * Detail (docs/adr/0012):
 * 1. **Luminance noise reduction**: an edge-avoiding à-trous wavelet (Dammertz et al. 2010, "Edge-avoiding À-Trous
 *    Wavelet Transform for fast Global Illumination Filtering", HPG; the B3-spline à-trous of Starck, Murtagh, Bijaoui
 *    1998) over the levels that run, each smoothing **separable** (`h = [1, 4, 6, 4, 1] / 16` with holes of the level's
 *    step along x, then along y, each edge-stopped by `exp(-dL^2 / 2 sigma_e^2)` against its centre): 10 taps instead
 *    of 25. The level's detail `d = L_i - L_i+1` is shrunk by `d * (d^2 / (d^2 + tau^2))^2` and accumulated; the result
 *    is the coarsest level plus the shrunk details. Every stored value is rounded to float16 ([Half]) and `d` is taken
 *    against the stored `L_i+1`, so with no shrinkage the levels telescope back to the input exactly.
 * 2. **Colour noise reduction** on a 2x2-binned (half-resolution) copy `D` of `(L, a, b)`: its levels smooth `a, b`
 *    (5x5, weighted by chroma distance and a luminance guard on `D`'s `L`, mixed in by the level's share) into `S`. The
 *    full-resolution colour is `up(S) + r * (1 - exp(-|r|^2 / 8 sigma^2))^2` with the residual `r = ab - up(D)`: colour
 *    finer than the binned copy is dropped where it is small (noise) and kept where it is a real colour edge. `up` is
 *    bilinear with explicit weights (texel `J` of the copy is centred between full-resolution pixels `2J` and `2J+1`).
 * 3. **Sharpening** of the denoised `L`: an unsharp mask with a separable Gaussian, the high-pass `h = L - G` shaped by
 *    texture coring `h^3 / (h^2 + t^2)` (the Detail slider) and the halo limit `h / (1 + |h| / c)`, times the edge mask
 *    `smoothstep(low, high, |grad G|)` (Masking).
 *
 * Effects (slot 12, docs/adr/0014): **texture** (a second, cross-bilateral Gaussian of the denoised `L` in the same
 * blur passes, its band shaped so strong edges are left alone), **clarity** and **dehaze** ([EffectsGrid], built from
 * the develop output before Detail; applied with the grid's guided-filter coefficients). See [EffectsParams].
 */
object DetailOp {
    private val H = floatArrayOf(0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f)
    private const val TAPS = 5
    private const val LAB = 4

    /**
     * Applies [d] and the local Effects of [e] to [rgb] (`width*height*3`, linear Rec.2020) in place; nothing happens
     * when neither runs. [grid] is the low-resolution grid of this image (clarity and dehaze).
     */
    fun apply(
        rgb: FloatArray,
        width: Int,
        height: Int,
        d: DetailParams,
        e: EffectsParams = EffectsParams.NONE,
        grid: LowResGrid? = null,
    ) {
        if (!d.active && !e.localActive) return
        render(rgb, width, height, d, e, grid ?: LowResGrid(width, height), showMask = false)
    }

    /** The masking overlay: the sharpening mask as grey (white = sharpened), what the editor shows while dragging. */
    fun mask(rgb: FloatArray, width: Int, height: Int, p: DetailParams) {
        render(rgb, width, height, p, EffectsParams.NONE, LowResGrid(width, height), showMask = true)
    }

    /** The develop output in OkLab, stored as float16 (`(L, a, b, 0)` per pixel), what `DEVELOP_FRAG` writes. */
    fun toLab(rgb: FloatArray, n: Int): FloatArray {
        val lab = FloatArray(n * LAB)
        val tmp = FloatArray(3)
        for (i in 0 until n) {
            OkLab.fromRgb(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2], tmp)
            lab[i * LAB] = Half.round(tmp[0])
            lab[i * LAB + 1] = Half.round(tmp[1])
            lab[i * LAB + 2] = Half.round(tmp[2])
        }
        return lab
    }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun render(
        rgb: FloatArray,
        width: Int,
        height: Int,
        p: DetailParams,
        e: EffectsParams,
        lowRes: LowResGrid,
        showMask: Boolean,
    ) {
        val n = width * height
        val lab = toLab(rgb, n)
        val grid = if (e.gridActive) EffectsGrid.build(lab, lowRes) else null
        // 1. Luminance: (L_i, acc) through the levels; denoised L = L + acc.
        val lumL = FloatArray(n) { lab[it * LAB] }
        val acc = FloatArray(n)
        if (p.lumActive) {
            val lh = FloatArray(n)
            for (i in 0 until DetailParams.LEVELS) {
                if (p.lumLevelActive(i)) lumLevel(lumL, acc, lh, width, height, p.lumStep[i], p.lumTau[i], p.lumEdge[i])
            }
        }
        val l0 = FloatArray(n) { lumL[it] + acc[it] }
        // 2. Colour on the half-resolution copy.
        val chroma = if (p.chromaActive) Chroma.run(lab, width, height, p) else null
        // 3. The blurs of the denoised L: sharpening (x) and texture (y).
        val sharpen = p.sharpenActive || showMask
        val blurs = if (sharpen || e.textureActive) {
            blur(l0, width, height, p.blur, e.textureBlur, e.textureRange)
        } else {
            null
        }
        val tmp = FloatArray(3)
        val coef = FloatArray(EffectsGrid.COEFS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val lnr = l0[i]
                var l = lnr
                var mask = 1f
                if (sharpen) {
                    val b = checkNotNull(blurs)
                    val g = b[i * 2]
                    val h = l - g
                    if (p.masking) {
                        val gx = b[(y * width + min(x + 1, width - 1)) * 2] - b[(y * width + max(x - 1, 0)) * 2]
                        val gy = b[(min(y + 1, height - 1) * width + x) * 2] - b[(max(y - 1, 0) * width + x) * 2]
                        val grad = 0.5f * sqrt(gx * gx + gy * gy) * p.gradientScale
                        mask = LowResGrid.smoothstep(p.maskLow, p.maskHigh, grad)
                    }
                    if (p.sharpenActive) l += p.sharpenGain * shape(h, p) * mask
                }
                if (showMask) {
                    OkLab.toRgb(mask, 0f, 0f, tmp)
                    rgb[i * 3] = tmp[0]
                    rgb[i * 3 + 1] = tmp[1]
                    rgb[i * 3 + 2] = tmp[2]
                    continue
                }
                if (e.textureActive) l += e.textureDelta(lnr - checkNotNull(blurs)[i * 2 + 1])
                if (grid != null) {
                    grid.sample(x, y, lnr, coef)
                    if (e.clarityActive) l = e.clarity(l, coef[0] * lnr + coef[1])
                }
                var a = lab[i * LAB + 1]
                var b = lab[i * LAB + 2]
                if (chroma != null) {
                    a = chroma[i * 2]
                    b = chroma[i * 2 + 1]
                }
                OkLab.toRgb(l, a, b, tmp)
                if (grid != null && e.dehazeActive) {
                    e.dehaze(tmp, coef[2] * lnr + coef[3], grid.air)
                }
                rgb[i * 3] = tmp[0]
                rgb[i * 3 + 1] = tmp[1]
                rgb[i * 3 + 2] = tmp[2]
            }
        }
    }

    /** The high-pass after texture coring and the halo limit (GLSL `sharpenShape`). */
    fun shape(h: Float, p: DetailParams): Float {
        val h2 = h * h
        val cored = if (p.coring2 > 0f) h * (h2 / (h2 + p.coring2)) else h
        return cored / (1f + abs(cored) * p.haloInv)
    }

    /**
     * One separable luminance level in place (GLSL `LUM_H_FRAG` then `LUM_V_FRAG`): [l] holds `L_i` and becomes
     * `L_i+1`, [acc] gets the shrunk detail; [lh] is scratch for the horizontal pass.
     */
    @Suppress("LongParameterList", "NestedBlockDepth")
    private fun lumLevel(
        l: FloatArray,
        acc: FloatArray,
        lh: FloatArray,
        width: Int,
        height: Int,
        step: Int,
        tau: Float,
        edge: Float,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = l[y * width + x]
                var sum = 0f
                var ws = 0f
                for (k in 0 until TAPS) {
                    val q = l[y * width + (x + (k - 2) * step).coerceIn(0, width - 1)]
                    val dl = q - c
                    val w = H[k] * exp(-dl * dl * edge)
                    sum += w * q
                    ws += w
                }
                lh[y * width + x] = Half.round(sum / ws)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val c = lh[i]
                var sum = 0f
                var ws = 0f
                for (k in 0 until TAPS) {
                    val q = lh[(y + (k - 2) * step).coerceIn(0, height - 1) * width + x]
                    val dl = q - c
                    val w = H[k] * exp(-dl * dl * edge)
                    sum += w * q
                    ws += w
                }
                // The detail against the stored smooth value: the levels telescope exactly.
                val l1 = Half.round(sum / ws)
                val d = l[i] - l1
                val keep = d * d / (d * d + tau * tau)
                acc[i] = Half.round(acc[i] + d * keep * keep)
                // In place is safe: the vertical pass reads only lh.
                l[i] = l1
            }
        }
    }

    /** The colour path on the half-resolution copy (GLSL `DOWN_FRAG`, `CHROMA_FRAG`, the final pass's `chroma`). */
    private object Chroma {
        @Suppress("NestedBlockDepth", "LongMethod")
        fun run(lab: FloatArray, width: Int, height: Int, p: DetailParams): FloatArray {
            val hw = (width + 1) / 2
            val hh = (height + 1) / 2
            val down = FloatArray(hw * hh * 3)
            for (j in 0 until hh) {
                for (i in 0 until hw) {
                    var sl = 0f
                    var sa = 0f
                    var sb = 0f
                    for (k in 0 until 4) {
                        val x = min(2 * i + (k and 1), width - 1)
                        val y = min(2 * j + (k shr 1), height - 1)
                        val q = (y * width + x) * LAB
                        sl += lab[q]
                        sa += lab[q + 1]
                        sb += lab[q + 2]
                    }
                    val o = (j * hw + i) * 3
                    down[o] = Half.round(sl * 0.25f)
                    down[o + 1] = Half.round(sa * 0.25f)
                    down[o + 2] = Half.round(sb * 0.25f)
                }
            }
            var src = down.copyOf()
            var dst = FloatArray(src.size)
            for (level in 0 until DetailParams.LEVELS) {
                if (!p.chromaLevelActive(level)) continue
                level(src, dst, hw, hh, p.chromaStep[level], p.chromaRange, p.chromaMix[level], p.chromaLuma)
                val t = src
                src = dst
                dst = t
            }
            val out = FloatArray(width * height * 2)
            val s = FloatArray(2)
            val dd = FloatArray(2)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    up(src, hw, hh, x, y, s)
                    up(down, hw, hh, x, y, dd)
                    val q = (y * width + x) * LAB
                    val ra = lab[q + 1] - dd[0]
                    val rb = lab[q + 2] - dd[1]
                    val edge = 1f - exp(-(ra * ra + rb * rb) * p.fineRange)
                    val keep = edge * edge
                    out[(y * width + x) * 2] = s[0] + ra * keep
                    out[(y * width + x) * 2 + 1] = s[1] + rb * keep
                }
            }
            return out
        }

        /** Bilinear `(a, b)` of the half-resolution [src] at full-resolution pixel ([x], [y]), explicit weights. */
        @Suppress("LongParameterList")
        fun up(src: FloatArray, hw: Int, hh: Int, x: Int, y: Int, out: FloatArray) {
            val u = (x + 0.5f) * 0.5f - 0.5f
            val v = (y + 0.5f) * 0.5f - 0.5f
            val fu = floor(u)
            val fv = floor(v)
            val fx = u - fu
            val fy = v - fv
            val x0 = fu.toInt().coerceIn(0, hw - 1)
            val x1 = (fu.toInt() + 1).coerceIn(0, hw - 1)
            val y0 = fv.toInt().coerceIn(0, hh - 1)
            val y1 = (fv.toInt() + 1).coerceIn(0, hh - 1)
            for (c in 0 until 2) {
                val a = src[(y0 * hw + x0) * 3 + 1 + c]
                val b = src[(y0 * hw + x1) * 3 + 1 + c]
                val d = src[(y1 * hw + x0) * 3 + 1 + c]
                val e = src[(y1 * hw + x1) * 3 + 1 + c]
                val top = a + (b - a) * fx
                val bottom = d + (e - d) * fx
                out[c] = top + (bottom - top) * fy
            }
        }

        /** One colour level on the half-resolution copy (`(L, a, b)` per texel; `L` is the guard, never changed). */
        @Suppress("LongParameterList", "NestedBlockDepth")
        private fun level(
            src: FloatArray,
            dst: FloatArray,
            w: Int,
            h: Int,
            step: Int,
            range: Float,
            mixC: Float,
            guard: Float,
        ) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = (y * w + x) * 3
                    val cl = src[c]
                    val ca = src[c + 1]
                    val cb = src[c + 2]
                    var sumA = 0f
                    var sumB = 0f
                    var wC = 0f
                    for (j in 0 until TAPS) {
                        val hy = H[j]
                        val qy = (y + (j - 2) * step).coerceIn(0, h - 1)
                        for (i in 0 until TAPS) {
                            val hw = H[i] * hy
                            val qx = (x + (i - 2) * step).coerceIn(0, w - 1)
                            val q = (qy * w + qx) * 3
                            val dl = src[q] - cl
                            val da = src[q + 1] - ca
                            val db = src[q + 2] - cb
                            val wc = hw * exp(-(da * da + db * db) * range - dl * dl * guard)
                            sumA += wc * src[q + 1]
                            sumB += wc * src[q + 2]
                            wC += wc
                        }
                    }
                    dst[c] = cl
                    dst[c + 1] = Half.round(ca + (sumA / wC - ca) * mixC)
                    dst[c + 2] = Half.round(cb + (sumB / wC - cb) * mixC)
                }
            }
        }
    }

    /**
     * The two blurs of the denoised [l] in one pair of separable passes (GLSL `BLUR_FRAG`): sharpening's Gaussian [ws]
     * and texture's **cross-bilateral** Gaussian [wt] (each tap also weighted by `exp(-dL^2 * textureRange)` against
     * the axis' centre value, so the texture band never spans a strong edge: no halo), half kernels padded with zeros
     * to the longer one. Returns `(G_sharpen, G_texture)` per pixel.
     */
    @Suppress("NestedBlockDepth", "LongParameterList", "LongMethod")
    private fun blur(
        l: FloatArray,
        width: Int,
        height: Int,
        ws: List<Float>,
        wt: List<Float>,
        textureRange: Float,
    ): FloatArray {
        val r = max(ws.size, wt.size) - 1
        val a = FloatArray(r + 1) { ws.getOrElse(it) { 0f } }
        val b = FloatArray(r + 1) { wt.getOrElse(it) { 0f } }
        val rows = FloatArray(width * height * 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                fun v(xx: Int): Float = l[y * width + xx.coerceIn(0, width - 1)]
                val c = v(x)
                var s0 = a[0] * c
                var s1 = b[0] * c
                var n1 = b[0]
                for (k in 1..r) {
                    val lo = v(x - k)
                    val hi = v(x + k)
                    s0 += a[k] * (lo + hi)
                    val wl = b[k] * exp(-(lo - c) * (lo - c) * textureRange)
                    val wh = b[k] * exp(-(hi - c) * (hi - c) * textureRange)
                    s1 += wl * lo + wh * hi
                    n1 += wl + wh
                }
                rows[(y * width + x) * 2] = s0
                rows[(y * width + x) * 2 + 1] = if (n1 > 0f) s1 / n1 else c
            }
        }
        val out = FloatArray(width * height * 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                fun v(yy: Int, ch: Int): Float = rows[(yy.coerceIn(0, height - 1) * width + x) * 2 + ch]
                val c = v(y, 1)
                var s0 = a[0] * v(y, 0)
                var s1 = b[0] * c
                var n1 = b[0]
                for (k in 1..r) {
                    s0 += a[k] * (v(y - k, 0) + v(y + k, 0))
                    val lo = v(y - k, 1)
                    val hi = v(y + k, 1)
                    val wl = b[k] * exp(-(lo - c) * (lo - c) * textureRange)
                    val wh = b[k] * exp(-(hi - c) * (hi - c) * textureRange)
                    s1 += wl * lo + wh * hi
                    n1 += wl + wh
                }
                out[(y * width + x) * 2] = s0
                out[(y * width + x) * 2 + 1] = if (n1 > 0f) s1 / n1 else c
            }
        }
        return out
    }

    private fun exp(x: Float): Float = StrictMath.exp(x.toDouble()).toFloat()
}
