@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.optics.VignetteTable
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Slot 3, vignetting correction (docs/adr/0015): a per-pixel gain on scene-linear light right after the camera matrix,
 * `rgb *= gain(r)`, `r` the pixel centre's distance from the image centre in half diagonals of the image being
 * rendered, `gain` the [VignetteTable] (`1 / illumination^amount`). Mirrored by `SCENE_FRAG` (`uVignette`).
 */
object VignetteOp {
    /**
     * Applies [table] to [rgb] (`width*height*3`). [originX]/[originY] and the full image size
     * [imageWidth] x [imageHeight] place a tile of a larger image (whole-image renders: zero and the same size).
     */
    @Suppress("LongParameterList")
    fun apply(
        rgb: FloatArray,
        width: Int,
        height: Int,
        table: VignetteTable,
        originX: Int = 0,
        originY: Int = 0,
        imageWidth: Int = width,
        imageHeight: Int = height,
    ) {
        val n = norm(imageWidth, imageHeight)
        for (y in 0 until height) {
            val dy = (originY + y) + 0.5f - n[1]
            for (x in 0 until width) {
                val dx = (originX + x) + 0.5f - n[0]
                val g = table.gain(sqrt(dx * dx + dy * dy) * n[2])
                val i = (y * width + x) * 3
                rgb[i] *= g
                rgb[i + 1] *= g
                rgb[i + 2] *= g
            }
        }
    }

    /** `centreX, centreY, 1 / halfDiagonal` as floats (the shader's `uVignette.yzw`). */
    fun norm(imageWidth: Int, imageHeight: Int): FloatArray = floatArrayOf(
        (imageWidth / 2.0).toFloat(),
        (imageHeight / 2.0).toFloat(),
        (2 / hypot(imageWidth.toDouble(), imageHeight.toDouble())).toFloat(),
    )
}

/**
 * Defringe (docs/adr/0015, slot 10b) resolved for one render. Hue windows are OkLab hue angles in degrees: the purple
 * slider's 0..100 spans [PURPLE_FROM]..[PURPLE_FROM] + [PURPLE_SPAN] (blue to magenta-red), the green one's
 * [GREEN_FROM]..[GREEN_FROM] + [GREEN_SPAN] (yellow-green to cyan-green).
 *
 * @property radius neighbourhood half-width in rendered pixels (the full-resolution [RADIUS] scaled, at least 1).
 */
data class DefringeParams(
    val purpleStrength: Float,
    val purpleLow: Float,
    val purpleHigh: Float,
    val greenStrength: Float,
    val greenLow: Float,
    val greenHigh: Float,
    val radius: Int,
) {
    val active: Boolean get() = purpleStrength > 0f || greenStrength > 0f

    /** The neighbourhood a result pixel reads (tiles need this much apron); 0 when inactive. */
    val reach: Int get() = if (active) radius else 0

    companion object {
        /** Neighbourhood half-width at full resolution, pixels: fringes of current lenses are 1-4 px wide. */
        const val RADIUS: Float = 3f
        const val PURPLE_FROM: Float = 250f
        const val PURPLE_SPAN: Float = 110f
        const val GREEN_FROM: Float = 100f
        const val GREEN_SPAN: Float = 100f

        /** Soft edge of a hue window, degrees. */
        const val FEATHER: Float = 10f

        /** OkLab lightness range across the neighbourhood where a pixel starts / fully counts as at an edge. */
        const val EDGE_LOW: Float = 0.05f
        const val EDGE_HIGH: Float = 0.25f

        /** Amount at which the desaturation reaches `1 - 1/e`. */
        private const val AMOUNT_SCALE = 5.0

        val NONE: DefringeParams = DefringeParams(0f, 0f, 0f, 0f, 0f, 0f, 1)

        /** Resolves [optics] for a render where one pixel covers [pixelScale] full-resolution pixels. */
        fun from(optics: Optics, pixelScale: Float): DefringeParams {
            val r = RecipeRanges
            fun strength(amount: Float): Float = (1.0 - StrictMath.exp(-amount / AMOUNT_SCALE)).toFloat()
            val purple = r.defringePurpleAmount.clamp(optics.purpleAmount)
            val green = r.defringeGreenAmount.clamp(optics.greenAmount)
            val pLo = r.defringePurpleHueLow.clamp(min(optics.purpleHueLow, optics.purpleHueHigh))
            val pHi = r.defringePurpleHueHigh.clamp(max(optics.purpleHueLow, optics.purpleHueHigh))
            val gLo = r.defringeGreenHueLow.clamp(min(optics.greenHueLow, optics.greenHueHigh))
            val gHi = r.defringeGreenHueHigh.clamp(max(optics.greenHueLow, optics.greenHueHigh))
            return DefringeParams(
                purpleStrength = if (purple > 0f) strength(purple) else 0f,
                purpleLow = PURPLE_FROM + pLo / 100f * PURPLE_SPAN,
                purpleHigh = PURPLE_FROM + pHi / 100f * PURPLE_SPAN,
                greenStrength = if (green > 0f) strength(green) else 0f,
                greenLow = GREEN_FROM + gLo / 100f * GREEN_SPAN,
                greenHigh = GREEN_FROM + gHi / 100f * GREEN_SPAN,
                radius = max(1, (RADIUS / max(1f, pixelScale)).roundToInt()),
            )
        }
    }
}

/**
 * Defringe (docs/adr/0015): desaturates purple and green fringes next to high-contrast edges. In OkLab (each value
 * rounded to float16 as the GPU stores it, [Half]):
 *
 * ```
 * E = max L - min L over the (2 radius + 1)^2 neighbourhood (taps clamped to the image)
 * e = smoothstep(EDGE_LOW, EDGE_HIGH, E)
 * h = atan2(b, a) in degrees [0, 360); purple reads hues below 90 as h + 360
 * w(lo, hi) = smoothstep(lo - FEATHER, lo, h) * (1 - smoothstep(hi, hi + FEATHER, h))
 * k = e * max(purpleStrength * w(purple), greenStrength * w(green))
 * (a, b) *= 1 - k
 * ```
 * Mirrored by `gl/DefringeShaders.kt` line for line. The result is written back as linear Rec.2020 RGB.
 */
object DefringeOp {
    /** Applies [p] to [rgb] (`width*height*3`, linear Rec.2020) in place; nothing happens when it is inactive. */
    fun apply(rgb: FloatArray, width: Int, height: Int, p: DefringeParams) {
        if (!p.active) return
        val n = width * height
        val lab = FloatArray(n * 3)
        val tmp = FloatArray(3)
        for (i in 0 until n) {
            OkLab.fromRgb(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2], tmp)
            lab[i * 3] = Half.round(tmp[0])
            lab[i * 3 + 1] = Half.round(tmp[1])
            lab[i * 3 + 2] = Half.round(tmp[2])
        }
        val r = p.radius
        for (y in 0 until height) {
            for (x in 0 until width) {
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -r..r) {
                        val l = lab[(yy * width + (x + dx).coerceIn(0, width - 1)) * 3]
                        lo = min(lo, l)
                        hi = max(hi, l)
                    }
                }
                val i = (y * width + x) * 3
                val k = weight(lab[i + 1], lab[i + 2], hi - lo, p)
                OkLab.toRgb(lab[i], lab[i + 1] * (1f - k), lab[i + 2] * (1f - k), tmp)
                rgb[i] = tmp[0]
                rgb[i + 1] = tmp[1]
                rgb[i + 2] = tmp[2]
            }
        }
    }

    /** The desaturation `k` of a pixel with chroma ([a], [b]) whose neighbourhood spans [range] in lightness. */
    fun weight(a: Float, b: Float, range: Float, p: DefringeParams): Float {
        val e = LowResGrid.smoothstep(DefringeParams.EDGE_LOW, DefringeParams.EDGE_HIGH, range)
        if (e == 0f) return 0f
        var h = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat()
        if (h < 0f) h += 360f
        val hp = if (h < 90f) h + 360f else h
        val purple = p.purpleStrength * window(hp, p.purpleLow, p.purpleHigh)
        val green = p.greenStrength * window(h, p.greenLow, p.greenHigh)
        return e * max(purple, green)
    }

    private fun window(h: Float, lo: Float, hi: Float): Float {
        val f = DefringeParams.FEATHER
        return LowResGrid.smoothstep(lo - f, lo, h) * (1f - LowResGrid.smoothstep(hi, hi + f, h))
    }
}
