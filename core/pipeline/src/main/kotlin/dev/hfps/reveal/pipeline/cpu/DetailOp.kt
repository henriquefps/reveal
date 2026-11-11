@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.abs
import kotlin.math.ceil
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
 * @property step rendered-pixel step of each wavelet level ([LEVELS]); 0 = the level does not run.
 * @property lumTau shrinkage threshold of the luminance detail at each level (OkLab L); 0 = no luminance NR there.
 * @property lumEdge `1 / (2 sigma^2)` of the luminance edge stop of each level's smoothing.
 * @property chromaRange `1 / (2 sigma^2)` of the chroma (a, b) range weight of each level.
 * @property chromaMix how much of each level's chroma smoothing is kept (0 = colour NR off at that level).
 * @property chromaLuma `1 / (2 sigma^2)` of the luminance guard of the chroma smoothing (colour does not cross L edges).
 * @property blur the sharpening Gaussian's half kernel (index 0 = centre, normalised over `-R..R`).
 * @property sharpenGain multiplier of the shaped high-pass.
 * @property coring2 squared texture-coring threshold (0 = every amplitude is sharpened).
 * @property haloInv `1 / c` of the halo limit `h / (1 + |h| / c)`.
 * @property maskLow masking ramp start (gradient of L per full-resolution pixel); [maskHigh] 0 = no mask.
 * @property gradientScale full-resolution pixels per rendered pixel, inverted (the masking gradient's unit).
 */
data class DetailParams(
    val step: List<Int>,
    val lumTau: List<Float>,
    val lumEdge: List<Float>,
    val chromaRange: List<Float>,
    val chromaMix: List<Float>,
    val chromaLuma: Float,
    val blur: List<Float>,
    val sharpenGain: Float,
    val coring2: Float,
    val haloInv: Float,
    val maskLow: Float,
    val maskHigh: Float,
    val gradientScale: Float,
) {
    /** True when a wavelet level runs (luminance or colour noise reduction at this scale). */
    val nrActive: Boolean get() = step.indices.any { levelActive(it) }

    /** True when sharpening runs. */
    val sharpenActive: Boolean get() = sharpenGain != 0f

    /** True when the operator changes anything; otherwise it is skipped (bit-exact identity). */
    val active: Boolean get() = nrActive || sharpenActive

    /** True when level [i] runs. */
    fun levelActive(i: Int): Boolean = step[i] > 0 && (lumTau[i] > 0f || chromaMix[i] > 0f)

    /** Sharpening blur half-width in rendered pixels. */
    val blurRadius: Int get() = blur.size - 1

    /** True when the masking ramp is on. */
    val masking: Boolean get() = maskHigh > 0f

    /**
     * How far (rendered pixels) a result pixel reads: the sum of the levels' reach (two taps of their step each side)
     * plus the blur and the gradient. Tiles need this much apron for the core to equal a whole-image render.
     */
    val reach: Int
        get() {
            var r = 0
            for (i in step.indices) if (levelActive(i)) r += 2 * step[i]
            if (sharpenActive) r += blurRadius + (if (masking) 1 else 0)
            return r
        }

    companion object {
        /** Wavelet levels (full-resolution steps 1, 2, 4, 8, 16). */
        const val LEVELS: Int = 5

        /** Largest [reach] at full resolution: all levels (2 * 31) plus a radius-3 blur (9) and the gradient (1). */
        const val MAX_REACH: Int = 2 * 31 + 9 + 1

        /** Luminance shrinkage threshold at amount 100 (OkLab L; high-ISO noise is about 0.01-0.03). */
        const val LUM_TAU_MAX: Float = 0.06f

        /** Per-level share of the luminance threshold (finer levels carry more of white-ish noise). */
        val LUM_LEVEL: FloatArray = floatArrayOf(1f, 0.62f, 0.38f, 0.22f, 0.13f)

        /** Chroma range sigma at amount 100 (OkLab a/b distance). */
        const val CHROMA_SIGMA_MAX: Float = 0.06f

        /** Sharpening gain at amount 100. */
        const val SHARPEN_GAIN: Float = 1.2f

        /** Texture-coring threshold at detail 0 (OkLab L). */
        const val CORING_MAX: Float = 0.006f

        /** Masking gradient (OkLab L per full-resolution pixel) where the mask is fully on at masking 100. */
        const val MASK_MAX: Float = 0.03f

        /** Smallest rendered sharpening sigma; smaller radii keep this and lower the amount in proportion. */
        const val MIN_SIGMA: Float = 0.5f

        /** A level runs when its rendered step is at least this (finer ones were averaged away by the proxy). */
        private const val MIN_STEP: Float = 0.75f

        private const val PERCENT = 100f
        private const val FINE_LEVELS = 2
        private const val BLUR_SIGMAS = 3f

        /** The identity (nothing runs). */
        val NONE: DetailParams = from(Detail(), 1f)

        /** Resolves [detail] for a render where one pixel covers [pixelScale] full-resolution pixels (>= 1). */
        @Suppress("LongMethod")
        fun from(detail: Detail, pixelScale: Float): DetailParams {
            val r = RecipeRanges
            val ps = max(1f, pixelScale)
            val lum = r.nrLuminance.clamp(detail.nrLuminance) / PERCENT
            val lumDetail = r.nrLuminanceDetail.clamp(detail.nrLuminanceDetail) / PERCENT
            val lumContrast = r.nrLuminanceContrast.clamp(detail.nrLuminanceContrast) / PERCENT
            val col = r.nrColour.clamp(detail.nrColour) / PERCENT
            val colDetail = r.nrColourDetail.clamp(detail.nrColourDetail) / PERCENT
            val smooth = r.nrColourSmoothness.clamp(detail.nrColourSmoothness) / PERCENT
            val steps = ArrayList<Int>(LEVELS)
            val taus = ArrayList<Float>(LEVELS)
            val edges = ArrayList<Float>(LEVELS)
            val ranges = ArrayList<Float>(LEVELS)
            val mixes = ArrayList<Float>(LEVELS)
            val sigmaC = col * CHROMA_SIGMA_MAX * mix(1.6f, 0.6f, colDetail)
            for (i in 0 until LEVELS) {
                val t = (1 shl i) / ps
                steps += if (t < MIN_STEP) 0 else max(1, t.roundToInt())
                val shape = if (i < FINE_LEVELS) mix(1.5f, 0.3f, lumDetail) else 1f - 0.85f * lumContrast
                val tau = lum * LUM_TAU_MAX * LUM_LEVEL[i] * shape
                taus += tau
                val sigmaE = max(tau * mix(8f, 3f, lumDetail), 1e-4f)
                edges += if (tau > 0f) 1f / (2f * sigmaE * sigmaE) else 0f
                ranges += if (sigmaC > 0f) 1f / (2f * sigmaC * sigmaC) else 0f
                val levelMix = when (i) {
                    3 -> min(1f, smooth * 2f)
                    4 -> max(0f, smooth * 2f - 1f)
                    else -> 1f
                }
                mixes += if (col > 0f) levelMix else 0f
            }
            val sigmaCl = mix(0.25f, 0.04f, colDetail)
            val amount = r.sharpenAmount.clamp(detail.sharpenAmount) / PERCENT
            var sigma = r.sharpenRadius.clamp(detail.sharpenRadius) / ps
            var attenuation = 1f
            if (sigma < MIN_SIGMA) {
                attenuation = sigma / MIN_SIGMA
                sigma = MIN_SIGMA
            }
            val blur = gaussian(sigma)
            val sharpDetail = r.sharpenDetail.clamp(detail.sharpenDetail) / PERCENT
            val coring = (1f - sharpDetail) * CORING_MAX
            val halo = mix(0.04f, 0.5f, sharpDetail)
            val masking = r.sharpenMasking.clamp(detail.sharpenMasking) / PERCENT
            val maskHigh = masking * MASK_MAX
            return DetailParams(
                step = steps,
                lumTau = taus,
                lumEdge = edges,
                chromaRange = ranges,
                chromaMix = mixes,
                chromaLuma = 1f / (2f * sigmaCl * sigmaCl),
                blur = blur,
                sharpenGain = amount * SHARPEN_GAIN * attenuation,
                coring2 = coring * coring,
                haloInv = 1f / halo,
                maskLow = maskHigh * 0.5f,
                maskHigh = maskHigh,
                gradientScale = 1f / ps,
            )
        }

        /** Normalised Gaussian half kernel for [sigma] (radius `ceil(3 sigma)`), computed in double. */
        private fun gaussian(sigma: Float): List<Float> {
            val radius = ceil(BLUR_SIGMAS * sigma).toInt()
            val w = DoubleArray(radius + 1) { StrictMath.exp(-(it * it) / (2.0 * sigma * sigma)) }
            val sum = w[0] + 2.0 * (1..radius).sumOf { w[it] }
            return w.map { (it / sum).toFloat() }
        }

        private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}

/**
 * Slot 11, Detail (docs/adr/0012): noise reduction then sharpening, on the working image after the colour operators,
 * in OkLab (`L` for luminance, `a, b` for colour). The CPU reference; `gl/DetailShaders` mirrors every pass line for
 * line (same pass structure, same tap order). Neighbourhood taps are clamped to the image.
 *
 * 1. **Noise reduction**: an edge-avoiding à-trous wavelet (Dammertz et al. 2010, "Edge-avoiding À-Trous Wavelet
 *    Transform for fast Global Illumination Filtering", HPG; the B3-spline à-trous of Starck, Murtagh, Bijaoui 1998)
 *    over [DetailParams.LEVELS] levels, each a 5x5 kernel `h = [1, 4, 6, 4, 1] / 16` with holes of the level's step:
 *    - luminance: the level's smoothing is edge-stopped (`exp(-dL^2 / 2 sigma_e^2)`), its detail `d = L_i - L_i+1` is
 *      shrunk by the squared Wiener-like factor `d * (d^2 / (d^2 + tau^2))^2` (a smooth garrote: details
 *      well above tau pass almost whole, those below it vanish, no hard knee as in soft thresholding) and accumulated; the result is the coarsest level plus the shrunk details. Every stored value is
 *      rounded to float16 ([Half], as the GPU stores it) and `d` is taken against the stored `L_i+1`, so with no
 *      shrinkage the levels telescope back to the input exactly;
 *    - colour: the level's smoothing of `a, b` weighted by chroma distance and a luminance guard, mixed in by the
 *      level's share; the result is the last level (colour noise is low-frequency: the coarse levels matter).
 * 2. **Sharpening** of the denoised `L`: an unsharp mask with a separable Gaussian, the high-pass `h = L - G` shaped by
 *    texture coring `h^3 / (h^2 + t^2)` (the Detail slider) and the halo limit `h / (1 + |h| / c)`, times the edge
 *    mask `smoothstep(low, high, |grad G|)` (Masking).
 */
object DetailOp {
    private val H = floatArrayOf(0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f)
    private const val TAPS = 5
    private const val LAB = 4

    /** Applies [p] to [rgb] (`width*height*3`, linear Rec.2020) in place; nothing happens when it is inactive. */
    fun apply(rgb: FloatArray, width: Int, height: Int, p: DetailParams) {
        if (!p.active) return
        render(rgb, width, height, p, showMask = false)
    }

    /** The masking overlay: the sharpening mask as grey (white = sharpened), what the editor shows while dragging. */
    fun mask(rgb: FloatArray, width: Int, height: Int, p: DetailParams) {
        render(rgb, width, height, p, showMask = true)
    }

    private fun render(rgb: FloatArray, width: Int, height: Int, p: DetailParams, showMask: Boolean) {
        val n = width * height
        var lab = FloatArray(n * LAB)
        val tmp = FloatArray(3)
        for (i in 0 until n) {
            OkLab.fromRgb(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2], tmp)
            // Stored as float16 like the GPU's OkLab target (Half: exact values, same on both sides).
            lab[i * LAB] = Half.round(tmp[0])
            lab[i * LAB + 1] = Half.round(tmp[1])
            lab[i * LAB + 2] = Half.round(tmp[2])
            lab[i * LAB + 3] = 0f
        }
        var other = FloatArray(n * LAB)
        for (level in 0 until DetailParams.LEVELS) {
            if (!p.levelActive(level)) continue
            level(lab, other, width, height, p, level)
            val t = lab
            lab = other
            other = t
        }
        val sharpen = p.sharpenActive || showMask
        val blurred = if (sharpen) blur(lab, other, width, height, p) else null
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var l = lab[i * LAB] + lab[i * LAB + 3]
                var mask = 1f
                if (blurred != null) {
                    val g = blurred[i]
                    val h = l - g
                    if (p.masking) {
                        val gx = blurred[y * width + min(x + 1, width - 1)] - blurred[y * width + max(x - 1, 0)]
                        val gy = blurred[min(y + 1, height - 1) * width + x] - blurred[max(y - 1, 0) * width + x]
                        val grad = 0.5f * sqrt(gx * gx + gy * gy) * p.gradientScale
                        mask = LowResGrid.smoothstep(p.maskLow, p.maskHigh, grad)
                    }
                    if (p.sharpenActive) l += p.sharpenGain * shape(h, p) * mask
                }
                if (showMask) {
                    OkLab.toRgb(mask, 0f, 0f, tmp)
                } else {
                    OkLab.toRgb(l, lab[i * LAB + 1], lab[i * LAB + 2], tmp)
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

    /** One wavelet level from [src] into [dst] (GLSL `DETAIL_LEVEL_FRAG`). */
    @Suppress("LongParameterList", "NestedBlockDepth", "LongMethod")
    private fun level(src: FloatArray, dst: FloatArray, width: Int, height: Int, p: DetailParams, level: Int) {
        val step = p.step[level]
        val tau = p.lumTau[level]
        val edge = p.lumEdge[level]
        val range = p.chromaRange[level]
        val mixC = p.chromaMix[level]
        val guard = p.chromaLuma
        val lumOn = tau > 0f
        val chromaOn = mixC > 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = (y * width + x) * LAB
                val cl = src[c]
                val ca = src[c + 1]
                val cb = src[c + 2]
                var sumL = 0f
                var wL = 0f
                var sumA = 0f
                var sumB = 0f
                var wC = 0f
                for (j in 0 until TAPS) {
                    val hy = H[j]
                    val qy = (y + (j - 2) * step).coerceIn(0, height - 1)
                    for (i in 0 until TAPS) {
                        val hw = H[i] * hy
                        val qx = (x + (i - 2) * step).coerceIn(0, width - 1)
                        val q = (qy * width + qx) * LAB
                        val dL = src[q] - cl
                        if (lumOn) {
                            val wl = hw * exp(-dL * dL * edge)
                            sumL += wl * src[q]
                            wL += wl
                        }
                        if (chromaOn) {
                            val da = src[q + 1] - ca
                            val db = src[q + 2] - cb
                            val wc = hw * exp(-(da * da + db * db) * range - dL * dL * guard)
                            sumA += wc * src[q + 1]
                            sumB += wc * src[q + 2]
                            wC += wc
                        }
                    }
                }
                if (lumOn) {
                    // The detail against the stored smooth value: the levels telescope exactly.
                    val l1 = Half.round(sumL / wL)
                    val d = cl - l1
                    dst[c] = l1
                    val keep = d * d / (d * d + tau * tau)
                    dst[c + 3] = Half.round(src[c + 3] + d * keep * keep)
                } else {
                    dst[c] = cl
                    dst[c + 3] = src[c + 3]
                }
                if (chromaOn) {
                    dst[c + 1] = Half.round(ca + (sumA / wC - ca) * mixC)
                    dst[c + 2] = Half.round(cb + (sumB / wC - cb) * mixC)
                } else {
                    dst[c + 1] = ca
                    dst[c + 2] = cb
                }
            }
        }
    }

    /** Separable Gaussian of the denoised `L` (`L + acc`), horizontal then vertical (GLSL `DETAIL_BLUR_FRAG`). */
    private fun blur(lab: FloatArray, scratch: FloatArray, width: Int, height: Int, p: DetailParams): FloatArray {
        val w = p.blur.toFloatArray()
        val r = w.size - 1
        val rows = scratch
        for (y in 0 until height) {
            for (x in 0 until width) {
                fun v(xx: Int): Float {
                    val q = (y * width + xx.coerceIn(0, width - 1)) * LAB
                    return lab[q] + lab[q + 3]
                }
                var sum = w[0] * v(x)
                for (k in 1..r) sum += w[k] * (v(x - k) + v(x + k))
                rows[y * width + x] = sum
            }
        }
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                fun v(yy: Int): Float = rows[yy.coerceIn(0, height - 1) * width + x]
                var sum = w[0] * v(y)
                for (k in 1..r) sum += w[k] * (v(y - k) + v(y + k))
                out[y * width + x] = sum
            }
        }
        return out
    }

    private fun exp(x: Float): Float = StrictMath.exp(x.toDouble()).toFloat()
}
