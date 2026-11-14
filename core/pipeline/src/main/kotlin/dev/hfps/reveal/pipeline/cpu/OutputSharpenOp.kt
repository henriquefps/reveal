package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.export.OutputSharpening
import dev.hfps.reveal.pipeline.export.SharpenAmount
import dev.hfps.reveal.pipeline.export.SharpenMedium
import kotlin.math.exp

/**
 * Output sharpening (docs/adr/0009 "Output sharpening"): an unsharp mask on the export **at its output size**, after
 * the Lanczos resampling and before the post-crop finish and the output transform. CPU reference; the GPU
 * (`ExportShaders.SHARPEN_FRAG`) is the same maths line for line.
 *
 * On linear working-space (Rec.2020) RGB, for output pixel `p`:
 * - `Y(q) = 0.2627 r + 0.6780 g + 0.0593 b`, `L(q) = cbrt(max(Y(q), 0))` (a perceptual lightness: the halo is the
 *   same size in dark and light tones);
 * - `B(p) = sum_{dx,dy in [-R, R]} w[|dx|] w[|dy|] L(clamp(p + (dx, dy)))` (separable Gaussian of [Params.sigma],
 *   radius `R = ceil(2.5 sigma)`, weights normalised so the 2-D sum is 1; taps clamp to the output's edges);
 * - `d = clamp(amount (L(p) - B(p)), -limit, limit)` (the limit caps halos at hard edges);
 * - `L' = max(L(p) + d, 0)`, and the pixel is scaled by `(L' / L(p))^3` (lightness only: hue and saturation keep;
 *   pixels with `L(p) <= EPSILON` are left alone).
 *
 * The medium sets sigma and the base amount, the level scales the amount ([params]); everything is computed in Double
 * and rounded to float once.
 */
object OutputSharpenOp {
    /** The constants of one setting: [weights] are the 1-D kernel `w[0..radius]`. */
    class Params(val sigma: Double, val radius: Int, val weights: FloatArray, val amount: Float, val limit: Float)

    /** Rec.2020 luminance weights (the working space, ADR 0002). */
    const val LUMA_R: Float = 0.2627f
    const val LUMA_G: Float = 0.6780f
    const val LUMA_B: Float = 0.0593f

    /** Halo limit in cube-root lightness units (about 6 % of the range). */
    const val LIMIT: Float = 0.06f

    /** Below this lightness a pixel is left alone (its ratio would be unstable). */
    const val EPSILON: Float = 1e-3f

    /** Largest radius any setting uses (the export plan's output margin is at most this). */
    const val MAX_RADIUS: Int = 3

    private const val RADIUS_SIGMAS = 2.5

    private fun sigma(medium: SharpenMedium): Double = when (medium) {
        SharpenMedium.SCREEN -> 0.6
        SharpenMedium.GLOSSY -> 0.9
        SharpenMedium.MATTE -> 1.2
    }

    private fun baseAmount(medium: SharpenMedium): Double = when (medium) {
        SharpenMedium.SCREEN -> 0.9
        SharpenMedium.GLOSSY -> 1.2
        SharpenMedium.MATTE -> 1.5
    }

    private fun levelScale(amount: SharpenAmount): Double = when (amount) {
        SharpenAmount.LOW -> 0.55
        SharpenAmount.STANDARD -> 1.0
        SharpenAmount.HIGH -> 1.6
    }

    /** The constants of [setting]. */
    fun params(setting: OutputSharpening): Params {
        val sigma = sigma(setting.medium)
        val radius = kotlin.math.ceil(RADIUS_SIGMAS * sigma).toInt()
        val raw = DoubleArray(radius + 1) { k -> exp(-(k * k) / (2 * sigma * sigma)) }
        val total = raw[0] + 2 * (1..radius).sumOf { raw[it] }
        val weights = FloatArray(radius + 1) { (raw[it] / total).toFloat() }
        val amount = (baseAmount(setting.medium) * levelScale(setting.amount)).toFloat()
        return Params(sigma, radius, weights, amount, LIMIT)
    }

    /** Output pixels a tile needs around it for [setting] (0 without sharpening). */
    fun radius(setting: OutputSharpening?): Int = setting?.let { params(it).radius } ?: 0

    /** Perceptual lightness `cbrt(max(Y, 0))` of a working-space pixel. */
    fun lightness(r: Float, g: Float, b: Float): Float {
        val y = LUMA_R * r + LUMA_G * g + LUMA_B * b
        return StrictMath.cbrt(maxOf(y, 0f).toDouble()).toFloat()
    }

    /** Sharpens the interleaved RGB image [rgb] ([width] x [height]); returns a new array. */
    fun apply(rgb: FloatArray, width: Int, height: Int, params: Params): FloatArray {
        val c = PipelineInput.CHANNELS
        val light = FloatArray(width * height) { i -> lightness(rgb[i * c], rgb[i * c + 1], rgb[i * c + 2]) }
        val out = rgb.copyOf()
        val r = params.radius
        val w = params.weights
        for (y in 0 until height) {
            for (x in 0 until width) {
                val l0 = light[y * width + x]
                if (l0 <= EPSILON) continue
                var blur = 0f
                for (dy in -r..r) {
                    val qy = (y + dy).coerceIn(0, height - 1)
                    var row = 0f
                    for (dx in -r..r) {
                        val qx = (x + dx).coerceIn(0, width - 1)
                        row += w[kotlin.math.abs(dx)] * light[qy * width + qx]
                    }
                    blur += w[kotlin.math.abs(dy)] * row
                }
                val d = (params.amount * (l0 - blur)).coerceIn(-params.limit, params.limit)
                val l1 = maxOf(l0 + d, 0f)
                val k = l1 / l0
                val ratio = k * k * k
                val i = (y * width + x) * c
                out[i] = rgb[i] * ratio
                out[i + 1] = rgb[i + 1] * ratio
                out[i + 2] = rgb[i + 2] * ratio
            }
        }
        return out
    }
}
