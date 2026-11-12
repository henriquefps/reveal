@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.faces.Face
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What Auto white balance needs from an image, small enough to keep after the proxy is uploaded and dropped: a
 * downsampled copy of the input's own linear pixels (un-balanced camera RGB for a RAW), which blocks contained a
 * clipped pixel, and the input's colour metadata (a 1x1 [PipelineInput] without pixels).
 */
class WbAnalysis(
    val width: Int,
    val height: Int,
    /** Block means, `width * height * 3`, in the input's own linear RGB. */
    val rgb: FloatArray,
    /** True where the block held a pixel at or above the clip level (its colour is unreliable). */
    val clipped: BooleanArray,
    /** The input's colour metadata (kind, camera matrix, as-shot multipliers, saturation). */
    val colour: PipelineInput,
)

/**
 * Auto white balance (docs/adr/0008): a scene-neutral estimate on analysis data, a pure function, JVM-tested on
 * synthetic scenes under known lights. Works in the input's own linear RGB, where an illuminant change is close to a
 * per-channel scale (von Kries), so the estimates below are estimates of the light's own RGB response:
 *
 * 1. **Usable blocks:** not clipped, not in the darkest 2 % (noise) and with some signal.
 * 2. **Shades of grey** (Finlayson & Trezzi 2004): `e_c = (mean x_c^p)^(1/p)`, p = 4. Grey world (p = 1) is fooled
 *    by large coloured areas; the higher norm leans on brighter, more reliable surfaces.
 * 3. **White patch:** the mean of the brightest 3 % of usable blocks (specular and white surfaces reflect the
 *    light's colour). Blended with step 2 geometrically, 30 % weight.
 * 4. **Grey pixels (gamut-constrained):** every block is read as if it were the light; the blocks whose colour *is*
 *    a plausible light (2100-14000 K, |Duv| <= 0.01, i.e. tint ±30 around the Planckian locus) are probably neutral
 *    surfaces (walls, roads, clouds, paper) and their median in mired and tint is the main estimate. Medians, so a
 *    bright sky or a large coloured area (grass, foliage: far off the locus) cannot dominate. When at least 5 % of
 *    the blocks qualify, the result is 90 % this and 10 % steps 2-3 (in mired and tint); otherwise steps 2-3 alone.
 *    Norm-based estimates fail on landscapes (sky and grass are bright and coloured: about 12 000 K on the public
 *    Nikon sample), grey pixels do not (5 300-5 800 K; the camera's as shot is 5 600 K).
 * 5. **Skin-tone guard:** skin is warm, frequent in people pictures and close to the locus, so it would read as a
 *    warm light. Blocks that, balanced by the current estimate, have a skin chromaticity (OkLab hue 30-75°, chroma
 *    0.04-0.15, judged at a fixed luminance Y 0.35, about L 0.7, so exposure does not matter) are left out and
 *    steps 2-4 run again (twice), when skin covers 3-50 % of the usable area (more than half is more likely a warm
 *    cast on neutral surfaces than skin).
 * 6. **Constraint:** the result is limited to 2000-15000 K and tint ±30: real lights (daylight, tungsten, flash,
 *    fluorescent, LED) lie inside, so a scene dominated by one colour cannot produce a light that does not exist.
 *
 * **With detected faces** (docs/adr/0010, "Faces"): the blocks under the faces (the box enlarged by
 * [FACE_BOX_MARGIN]: skin and hair) never take part in steps 2-4; step 5 drops its upper bound (people are known to
 * be there, a close-up may be mostly skin); and a final **skin guard** checks the faces' own colour: their skin
 * (the blocks inside [AutoAnalyser.inSkin][dev.hfps.reveal.pipeline.auto.AutoAnalyser.inSkin]'s ellipse, score
 * weighted) balanced by the estimate must have an OkLab hue in [FACE_HUE_MIN]..[FACE_HUE_MAX]° (natural skin of any
 * tone; outside it the faces look green, magenta or blue). If not, the estimate moves by the smallest step on a grid
 * of ±[FACE_MAX_MIRED] mired and ±[FACE_MAX_TINT] tint that brings the hue inside (or nearest to it).
 *
 * The result is plain slider values ([estimate] / [whiteBalance]): temperature/tint for a RAW, the relative pair
 * for display-referred input, `mode = AUTO` (product principle: Auto proposes values the user can change).
 */
object AutoWhiteBalance {
    /** Longest side of the analysis image. */
    const val ANALYSIS_SIDE: Int = 192

    private const val NORM_P = 4.0
    private const val WHITE_PATCH_FRACTION = 0.03
    private const val WHITE_PATCH_WEIGHT = 0.3
    private const val DARK_FRACTION = 0.02
    private const val CLIP_MARGIN = 0.95f
    private const val SKIN_MIN_FRACTION = 0.03
    private const val SKIN_MAX_FRACTION = 0.5
    private const val SKIN_ITERATIONS = 2
    private const val TINT_LIMIT = 30.0
    private const val MIN_SIGNAL = 1e-5
    private const val GREY_MIN_FRACTION = 0.05
    private const val GREY_WEIGHT = 0.9
    private const val GREY_MIN_K = 2100.0
    private const val GREY_MAX_K = 14000.0
    private const val MIRED_PER_K = 1e6

    /** Area-average downsample of [input] to at most [maxSide] px, flagging blocks with a clipped pixel. */
    fun analyse(input: PipelineInput, maxSide: Int = ANALYSIS_SIDE): WbAnalysis {
        val scale = max(1, (max(input.width, input.height) + maxSide - 1) / maxSide)
        val w = max(1, input.width / scale)
        val h = max(1, input.height / scale)
        val out = FloatArray(w * h * 3)
        val clipped = BooleanArray(w * h)
        val clipLevel = input.saturation?.let { it * CLIP_MARGIN } ?: Float.MAX_VALUE
        for (by in 0 until h) {
            for (bx in 0 until w) {
                val o = by * w + bx
                clipped[o] = blockMean(input, bx * scale, by * scale, scale, clipLevel, out, o * 3)
            }
        }
        val colour = PipelineInput(
            input.kind,
            1,
            1,
            FloatArray(3),
            input.cameraToXyz,
            input.asShotMultipliers?.copyOf(),
            input.saturation,
        )
        return WbAnalysis(w, h, out, clipped, colour)
    }

    /** Mean of the [scale]-sized block at ([x0], [y0]) into [out] at [o]; true when a pixel reaches [clipLevel]. */
    private fun blockMean(
        input: PipelineInput,
        x0: Int,
        y0: Int,
        scale: Int,
        clipLevel: Float,
        out: FloatArray,
        o: Int,
    ): Boolean {
        val src = input.linearRgb
        val sum = DoubleArray(3)
        var clip = false
        for (y in y0 until min(input.height, y0 + scale)) {
            for (x in x0 until min(input.width, x0 + scale)) {
                val i = (y * input.width + x) * 3
                for (c in 0..2) {
                    sum[c] += src[i + c]
                    clip = clip || src[i + c] >= clipLevel
                }
            }
        }
        val n = (scale * scale).toDouble()
        for (c in 0..2) out[o + c] = (sum[c] / n).toFloat()
        return clip
    }

    /** The estimated light as temperature and tint (steps 1-6, and the face rules for [faces]). */
    fun estimate(
        analysis: WbAnalysis,
        skinGuard: Boolean = true,
        faces: List<Face> = emptyList(),
    ): WhiteBalanceModel.Cct {
        val all = usableBlocks(analysis)
        require(all.isNotEmpty()) { "no usable pixels for auto white balance" }
        val underFaces = all.filter { i -> faces.any { underFace(analysis, i, it) } }.toHashSet()
        // Keep the faces' blocks when they are nearly everything (a tight close-up): better than nothing.
        val usable = if (all.size - underFaces.size >=
            FACE_MIN_REST * all.size
        ) {
            all.filter { it !in underFaces }
        } else {
            all
        }
        val m = analysis.colour.cameraToXyz
        val asLight = HashMap<Int, WhiteBalanceModel.Cct>()
        for (i in usable) {
            asLight[i] = WhiteBalanceModel.cctAndTint(
                m.times(
                    analysis.rgb[i * 3].toDouble(),
                    analysis.rgb[i * 3 + 1].toDouble(),
                    analysis.rgb[i * 3 + 2].toDouble(),
                ),
            )
        }
        var skin = emptySet<Int>()
        var result = combine(analysis, usable, asLight)
        if (skinGuard) {
            repeat(SKIN_ITERATIONS) {
                val xyz = WhiteBalanceModel.illuminantXyz(result.temperatureK, result.tint)
                val light = m.inverse().times(xyz)
                val found = usable.filter { isSkin(analysis, it, light) }
                val upper = if (faces.isEmpty()) SKIN_MAX_FRACTION else 1.0
                if (found.size >= SKIN_MIN_FRACTION * usable.size && found.size < upper * usable.size) {
                    skin = found.toHashSet()
                    result = combine(analysis, usable.filter { it !in skin }, asLight)
                }
            }
        }
        return if (faces.isEmpty()) result else skinGuard(analysis, result, faces)
    }

    /** The face skin guard: the smallest move of [estimate] that gives the faces' skin a natural hue. */
    private fun skinGuard(
        analysis: WbAnalysis,
        estimate: WhiteBalanceModel.Cct,
        faces: List<Face>,
    ): WhiteBalanceModel.Cct {
        val skin = faceSkin(analysis, faces) ?: return estimate
        val mired = MIRED_PER_K / estimate.temperatureK
        var best = estimate
        var bestCost = Double.MAX_VALUE
        var bestMiss = Double.MAX_VALUE
        for (dm in -FACE_MAX_MIRED..FACE_MAX_MIRED step FACE_MIRED_STEP) {
            for (dt in -FACE_MAX_TINT..FACE_MAX_TINT) {
                val cct = constrain(WhiteBalanceModel.Cct(MIRED_PER_K / (mired + dm), estimate.tint + dt))
                val hue = skinHue(analysis, skin, cct)
                val miss = when {
                    hue < FACE_HUE_MIN -> FACE_HUE_MIN - hue
                    hue > FACE_HUE_MAX -> hue - FACE_HUE_MAX
                    else -> 0.0
                }
                val nm = dm.toDouble() / FACE_MAX_MIRED
                val nt = dt.toDouble() / FACE_MAX_TINT
                val cost = nm * nm + nt * nt
                // Inside the range first, then the smallest move; outside it everywhere: the nearest hue.
                if (miss < bestMiss - HUE_EPSILON ||
                    (kotlin.math.abs(miss - bestMiss) <= HUE_EPSILON && cost < bestCost)
                ) {
                    best = cct
                    bestCost = cost
                    bestMiss = miss
                }
            }
        }
        return best
    }

    /** Score-weighted mean raw colour of the unclipped blocks on the faces' skin; `null` when none. */
    @Suppress("NestedBlockDepth")
    private fun faceSkin(a: WbAnalysis, faces: List<Face>): DoubleArray? {
        val sum = DoubleArray(3)
        var weight = 0.0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val i = y * a.width + x
                if (a.clipped[i]) continue
                val u = (x + HALF) / a.width
                val v = (y + HALF) / a.height
                for (f in faces) {
                    if (!dev.hfps.reveal.pipeline.auto.AutoAnalyser.inSkin(f, u, v)) continue
                    for (c in 0..2) sum[c] += a.rgb[i * 3 + c] * f.score.toDouble()
                    weight += f.score
                }
            }
        }
        if (weight <= 0.0 || sum.any { it <= 0.0 }) return null
        return DoubleArray(3) { sum[it] / weight }
    }

    /** The faces' skin hue (the guard's measure, degrees) under the light [cct]; `null` without usable skin. */
    fun faceSkinHue(analysis: WbAnalysis, faces: List<Face>, cct: WhiteBalanceModel.Cct): Double? =
        faceSkin(analysis, faces)?.let { skinHue(analysis, it, cct) }

    /** OkLab hue (degrees) of raw [rgb] balanced for the light [cct], judged at the skin luminance as [isSkin]. */
    private fun skinHue(a: WbAnalysis, rgb: DoubleArray, cct: WhiteBalanceModel.Cct): Double {
        val m = a.colour.cameraToXyz
        val light = m.inverse().times(WhiteBalanceModel.illuminantXyz(cct.temperatureK, cct.tint))
        val lab = skinLab(a, rgb, light)
        return Math.toDegrees(StrictMath.atan2(lab[2].toDouble(), lab[1].toDouble()))
    }

    /** True when block [i]'s centre lies in a face's box enlarged by [FACE_BOX_MARGIN]. */
    private fun underFace(a: WbAnalysis, i: Int, f: Face): Boolean {
        val u = ((i % a.width) + HALF) / a.width
        val v = ((i / a.width) + HALF) / a.height
        val mx = f.width * FACE_BOX_MARGIN / 2
        val my = f.height * FACE_BOX_MARGIN / 2
        return u >= f.left - mx && u <= f.right + mx && v >= f.top - my && v <= f.bottom + my
    }

    /** Steps 2-3 (norms) and 5 (grey pixels) over [blocks], combined and constrained (step 6). */
    private fun combine(
        analysis: WbAnalysis,
        blocks: List<Int>,
        asLight: Map<Int, WhiteBalanceModel.Cct>,
    ): WhiteBalanceModel.Cct {
        val norms =
            constrain(WhiteBalanceModel.cctAndTint(analysis.colour.cameraToXyz.times(lightRgb(analysis, blocks))))
        val grey = blocks.mapNotNull { asLight[it] }.filter { plausible(it) }
        if (grey.size < GREY_MIN_FRACTION * blocks.size) return norms
        val greyMired = median(grey.map { MIRED_PER_K / it.temperatureK })
        val greyTint = median(grey.map { it.tint })
        val mired = (1 - GREY_WEIGHT) * MIRED_PER_K / norms.temperatureK + GREY_WEIGHT * greyMired
        return constrain(
            WhiteBalanceModel.Cct(
                MIRED_PER_K / mired,
                (1 - GREY_WEIGHT) * norms.tint + GREY_WEIGHT * greyTint,
            ),
        )
    }

    /** [estimate] as the recipe white balance (`mode = AUTO`). */
    fun whiteBalance(analysis: WbAnalysis, faces: List<Face> = emptyList()): WhiteBalance {
        val cct = estimate(analysis, faces = faces)
        val xyz = WhiteBalanceModel.illuminantXyz(cct.temperatureK, cct.tint)
        return WhiteBalanceModel.fromIlluminant(analysis.colour, xyz, WbMode.AUTO)
    }

    private fun plausible(c: WhiteBalanceModel.Cct) = c.temperatureK > GREY_MIN_K && c.temperatureK < GREY_MAX_K &&
        kotlin.math.abs(c.tint) <= TINT_LIMIT

    private fun median(v: List<Double>): Double = v.sorted().let { it[it.size / 2] }

    private fun usableBlocks(a: WbAnalysis): List<Int> {
        val n = a.width * a.height
        val luma = DoubleArray(n) { (a.rgb[it * 3] + a.rgb[it * 3 + 1] + a.rgb[it * 3 + 2]).toDouble() }
        val sorted = luma.filterIndexed { i, _ -> !a.clipped[i] }.sorted()
        if (sorted.isEmpty()) return emptyList()
        val dark = max(sorted[(sorted.size * DARK_FRACTION).toInt()], MIN_SIGNAL)
        return (0 until n).filter { i ->
            !a.clipped[i] && luma[i] >= dark && a.rgb[i * 3] > 0f && a.rgb[i * 3 + 1] > 0f && a.rgb[i * 3 + 2] > 0f
        }
    }

    /** Steps 2-3 over [blocks]: the light's RGB response (any scale). */
    private fun lightRgb(a: WbAnalysis, blocks: List<Int>): DoubleArray {
        val norm = DoubleArray(3)
        for (i in blocks) for (c in 0..2) norm[c] += StrictMath.pow(a.rgb[i * 3 + c].toDouble(), NORM_P)
        val sog = DoubleArray(3) { StrictMath.pow(norm[it] / blocks.size, 1.0 / NORM_P) }
        val bright = blocks.sortedByDescending { a.rgb[it * 3] + a.rgb[it * 3 + 1] + a.rgb[it * 3 + 2] }
            .take(max(1, (blocks.size * WHITE_PATCH_FRACTION).toInt()))
        val wp = DoubleArray(3)
        for (i in bright) {
            val s = (a.rgb[i * 3] + a.rgb[i * 3 + 1] + a.rgb[i * 3 + 2]).toDouble()
            for (c in 0..2) wp[c] += a.rgb[i * 3 + c] / s
        }
        val sogG = sog[1]
        val wpG = wp[1]
        return DoubleArray(3) {
            StrictMath.pow(sog[it] / sogG, 1.0 - WHITE_PATCH_WEIGHT) * StrictMath.pow(wp[it] / wpG, WHITE_PATCH_WEIGHT)
        }
    }

    private fun isSkin(a: WbAnalysis, i: Int, light: DoubleArray): Boolean {
        val raw = doubleArrayOf(a.rgb[i * 3].toDouble(), a.rgb[i * 3 + 1].toDouble(), a.rgb[i * 3 + 2].toDouble())
        if (a.colour.cameraToXyz.times(raw)[1] <= MIN_SIGNAL) return false
        val lab = skinLab(a, raw, light)
        val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        val hue = Math.toDegrees(StrictMath.atan2(lab[2].toDouble(), lab[1].toDouble()))
        return c in SKIN_C_MIN..SKIN_C_MAX && hue in SKIN_HUE_MIN..SKIN_HUE_MAX
    }

    /**
     * OkLab of raw [raw] as the develop path shows it when the light is [light] (XYZ = M raw, adapted from M light to
     * D65), judged by chromaticity: scaled to the luminance of typical skin (Y 0.35, OkLab L about 0.7).
     */
    private fun skinLab(a: WbAnalysis, raw: DoubleArray, light: DoubleArray): FloatArray {
        val m = a.colour.cameraToXyz
        val adapt = ColorSpaces.bradfordAdaptation(m.times(light), ColorSpaces.D65_XYZ)
        val xyz = adapt.times(m.times(raw))
        val rgb = ColorSpaces.REC2020.fromXyz.times(xyz)
        val k = SKIN_Y / xyz[1].coerceAtLeast(MIN_SIGNAL)
        val lab = FloatArray(3)
        OkLab.fromRgb((rgb[0] * k).toFloat(), (rgb[1] * k).toFloat(), (rgb[2] * k).toFloat(), lab)
        return lab
    }

    private fun constrain(cct: WhiteBalanceModel.Cct) = WhiteBalanceModel.Cct(
        cct.temperatureK.coerceIn(WhiteBalanceModel.MIN_TEMPERATURE_K, WhiteBalanceModel.MAX_TEMPERATURE_K),
        cct.tint.coerceIn(-TINT_LIMIT, TINT_LIMIT),
    )

    private const val SKIN_Y = 0.35
    private const val SKIN_C_MIN = 0.04f
    private const val SKIN_C_MAX = 0.15f
    private const val SKIN_HUE_MIN = 30.0
    private const val SKIN_HUE_MAX = 75.0
    private const val HALF = 0.5f
    private const val FACE_BOX_MARGIN = 0.3f
    private const val FACE_MIN_REST = 0.2
    private const val FACE_HUE_MIN = 22.0
    private const val FACE_HUE_MAX = 52.0
    private const val FACE_MAX_MIRED = 40
    private const val FACE_MIRED_STEP = 2
    private const val FACE_MAX_TINT = 10
    private const val HUE_EPSILON = 1e-9
}
