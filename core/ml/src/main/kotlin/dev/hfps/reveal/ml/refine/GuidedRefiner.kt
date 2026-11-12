@file:Suppress("MagicNumber", "NestedBlockDepth")

package dev.hfps.reveal.ml.refine

import dev.hfps.reveal.ml.Mask
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.tensor.Resample
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Edge refinement of a model mask against the photo (docs/adr/0021 "Edge refinement"): the colour guided filter of
 * He, Sun and Tang (2013) in its fast form (He and Sun 2015). The linear model `q = a · I + b` is fitted in every
 * window of the guide `I` (the display image) at a reduced resolution, its coefficients averaged, and applied at full
 * resolution, so a 256 px mask follows hair and branches of a 24 MP photo.
 *
 * Two halves, the same on the CPU and the GPU:
 * - [coefficients]: at `guide / subsample`, cheap (box means over prefix sums, a 3 x 3 solve per pixel); CPU only.
 * - [apply]: per full-resolution pixel, `a` and `b` bilinearly upsampled (explicit weights, pixel centres aligned)
 *   and dotted with the guide's colour; [GuidedFilterShader] is the same maths as a fragment pass.
 */
object GuidedRefiner {
    /** [radius] in pixels of the reduced image; [eps] the regularisation (the edge threshold, in 0..1 units²). */
    data class Params(val subsample: Int = 4, val radius: Int = 8, val eps: Float = 1e-3f) {
        init {
            require(subsample >= 1 && radius >= 1 && eps > 0f) { "invalid guided filter parameters" }
        }
    }

    /**
     * The averaged coefficients at the reduced size: RGBA per pixel (`a.r, a.g, a.b, b`), interleaved, ready for an
     * RGBA32F texture.
     */
    class Coefficients(val width: Int, val height: Int, val rgba: FloatArray)

    /** [mask] (any resolution) refined against [guide] at the guide's resolution. */
    fun refine(guide: MlImage, mask: Mask, params: Params = Params()): Mask =
        apply(guide, coefficients(guide, mask, params))

    /** The fitted coefficients of [mask] against [guide], at `guide / subsample`. */
    fun coefficients(guide: MlImage, mask: Mask, params: Params = Params()): Coefficients {
        val w = max(1, (guide.width.toFloat() / params.subsample).roundToInt())
        val h = max(1, (guide.height.toFloat() / params.subsample).roundToInt())
        val img = Resample.interleaved(guide.rgb, guide.width, guide.height, 3, w, h)
        val p = Resample.plane(mask.values, mask.width, mask.height, w, h)
        val n = w * h
        val r = params.radius
        val ch = Array(3) { c -> FloatArray(n) { img[it * 3 + c] } }
        val mI = Array(3) { BoxMean.of(ch[it], w, h, r) }
        val mp = BoxMean.of(p, w, h, r)
        val mIp = Array(3) { c -> BoxMean.of(FloatArray(n) { ch[c][it] * p[it] }, w, h, r) }
        // Covariance of the guide: rr, rg, rb, gg, gb, bb.
        val pairs = arrayOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 2)
        val mII = Array(6) { k ->
            val (i, j) = pairs[k]
            BoxMean.of(FloatArray(n) { ch[i][it] * ch[j][it] }, w, h, r)
        }
        val a = Array(3) { FloatArray(n) }
        val b = FloatArray(n)
        val eps = params.eps.toDouble()
        IntStream.range(0, h).parallel().forEach { y ->
            for (i in y * w until (y + 1) * w) solve(i, mI, mp, mIp, mII, eps, a, b)
        }
        val ma = Array(3) { BoxMean.of(a[it], w, h, r) }
        val mb = BoxMean.of(b, w, h, r)
        val rgba = FloatArray(n * 4)
        for (i in 0 until n) {
            rgba[i * 4] = ma[0][i]
            rgba[i * 4 + 1] = ma[1][i]
            rgba[i * 4 + 2] = ma[2][i]
            rgba[i * 4 + 3] = mb[i]
        }
        return Coefficients(w, h, rgba)
    }

    /** One window's `a = (Σ + eps U)⁻¹ cov(I, p)`, `b = mean(p) - a · mean(I)`, in double precision. */
    @Suppress("LongParameterList")
    private fun solve(
        i: Int,
        mI: Array<FloatArray>,
        mp: FloatArray,
        mIp: Array<FloatArray>,
        mII: Array<FloatArray>,
        eps: Double,
        a: Array<FloatArray>,
        b: FloatArray,
    ) {
        val r = mI[0][i].toDouble()
        val g = mI[1][i].toDouble()
        val bl = mI[2][i].toDouble()
        val p = mp[i].toDouble()
        val vrr = mII[0][i] - r * r + eps
        val vrg = mII[1][i] - r * g
        val vrb = mII[2][i] - r * bl
        val vgg = mII[3][i] - g * g + eps
        val vgb = mII[4][i] - g * bl
        val vbb = mII[5][i] - bl * bl + eps
        val cr = mIp[0][i] - r * p
        val cg = mIp[1][i] - g * p
        val cb = mIp[2][i] - bl * p
        // Inverse of the symmetric matrix by cofactors.
        val i00 = vgg * vbb - vgb * vgb
        val i01 = vgb * vrb - vrg * vbb
        val i02 = vrg * vgb - vgg * vrb
        val i11 = vrr * vbb - vrb * vrb
        val i12 = vrb * vrg - vrr * vgb
        val i22 = vrr * vgg - vrg * vrg
        val det = vrr * i00 + vrg * i01 + vrb * i02
        val ar = (i00 * cr + i01 * cg + i02 * cb) / det
        val ag = (i01 * cr + i11 * cg + i12 * cb) / det
        val ab = (i02 * cr + i12 * cg + i22 * cb) / det
        a[0][i] = ar.toFloat()
        a[1][i] = ag.toFloat()
        a[2][i] = ab.toFloat()
        b[i] = (p - ar * r - ag * g - ab * bl).toFloat()
    }

    /** `q = a · I + b` at the guide's resolution (the CPU reference of [GuidedFilterShader.APPLY_FRAG]). */
    fun apply(guide: MlImage, k: Coefficients): Mask {
        val tx = Resample.taps(k.width, guide.width)
        val ty = Resample.taps(k.height, guide.height)
        val rgb = guide.rgb
        val c = k.rgba
        val out = FloatArray(guide.width * guide.height)
        IntStream.range(0, guide.height).parallel().forEach { y ->
            val coef = FloatArray(4)
            for (x in 0 until guide.width) {
                coef.fill(0f)
                for (j in ty.start[y] until ty.start[y + 1]) {
                    val row = ty.index[j] * k.width
                    for (t in tx.start[x] until tx.start[x + 1]) {
                        val wt = ty.weight[j] * tx.weight[t]
                        val o = (row + tx.index[t]) * 4
                        coef[0] += c[o] * wt
                        coef[1] += c[o + 1] * wt
                        coef[2] += c[o + 2] * wt
                        coef[3] += c[o + 3] * wt
                    }
                }
                val i = y * guide.width + x
                val q = coef[0] * rgb[i * 3] + coef[1] * rgb[i * 3 + 1] + coef[2] * rgb[i * 3 + 2] + coef[3]
                out[i] = q.coerceIn(0f, 1f)
            }
        }
        return Mask(guide.width, guide.height, out)
    }
}

/** Box means over a (2r + 1)² window, clamped at the borders (mean of the valid part), via prefix sums in double. */
internal object BoxMean {
    fun of(src: FloatArray, w: Int, h: Int, r: Int): FloatArray = vertical(horizontal(src, w, h, r), w, h, r)

    private fun horizontal(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            val sum = DoubleArray(w + 1)
            for (x in 0 until w) sum[x + 1] = sum[x] + src[y * w + x]
            for (x in 0 until w) {
                val lo = max(0, x - r)
                val hi = minOf(w, x + r + 1)
                out[y * w + x] = ((sum[hi] - sum[lo]) / (hi - lo)).toFloat()
            }
        }
        return out
    }

    private fun vertical(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(w * h)
        IntStream.range(0, w).parallel().forEach { x ->
            val sum = DoubleArray(h + 1)
            for (y in 0 until h) sum[y + 1] = sum[y] + src[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - r)
                val hi = minOf(h, y + r + 1)
                out[y * w + x] = ((sum[hi] - sum[lo]) / (hi - lo)).toFloat()
            }
        }
        return out
    }
}
