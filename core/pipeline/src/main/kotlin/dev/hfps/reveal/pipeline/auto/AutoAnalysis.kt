@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.auto

import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CameraMatrixOp
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.HighlightReconstructionOp
import dev.hfps.reveal.pipeline.cpu.LowResGrid
import dev.hfps.reveal.pipeline.cpu.Luma
import dev.hfps.reveal.pipeline.cpu.OkLab
import dev.hfps.reveal.pipeline.cpu.ProfileOp
import dev.hfps.reveal.pipeline.cpu.RawWhiteBalanceOp
import dev.hfps.reveal.pipeline.faces.Face
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What Auto knows about a photo (docs/adr/0010): plain numbers, so the rules ([AutoRules]) are pure functions that
 * can be tested on fixtures and reused by M3's smart presets.
 *
 * The tone axis is `s = log2(Y)`, `Y` the Rec.2020 luminance of the scene-linear image at exposure 0 (after the
 * white balance, highlight reconstruction and camera matrix, before Light and the profile): 0 is the reference white,
 * -2.47 mid grey, the same axis as the Highlights/Shadows base layer (ADR 0007). Histograms have [BINS_PER_STOP]
 * bins per stop from [MIN_STOPS] to [MAX_STOPS] (values outside land in the end bins) and sum to 1.
 *
 * @property histogram fraction of pixels per bin.
 * @property centreHistogram the same, centre-weighted (weight `1 + 2 exp(-r^2 / (2 * 0.2^2))`, `r` the distance
 *   from the centre in units of the frame's width/height): the subject is more often near the centre.
 * @property toneTable display-referred luminance of a neutral grey of luminance `2^s` at each bin centre through
 *   the photo's profile (amount included): the profile's tone curve as the rules see it. The identity `2^s` for
 *   display-referred input (the profile does not apply there).
 * @property clippedFraction fraction of pixels with a raw channel at the sensor's clip (0 without a known clip).
 * @property medianChroma median OkLab chroma of the display-referred render over mid-tone pixels (display `Y` in
 *   0.05..0.8; 0 when there are none).
 * @property sceneReferred false for JPEG/HEIF input (already rendered: its whites are not recoverable).
 * @property subjectHistogram the histogram weighted towards the probable subject (weight `1 + 2 saliency`,
 *   [SaliencyMap]; docs/adr/0010, "Subject"); the centre-weighted one when no map is known.
 * @property faceHistogram the histogram of the faces' skin (an ellipse inside each detected face, weighted by the
 *   detector's score), `null` without faces in the crop (docs/adr/0010, "Faces").
 * @property faceArea the faces' boxes as a fraction of the crop's area (summed, at most 1).
 * @property faceCount faces whose centre is inside the crop.
 */
class AutoAnalysis(
    val histogram: DoubleArray,
    val centreHistogram: DoubleArray,
    val toneTable: DoubleArray,
    val clippedFraction: Double,
    val medianChroma: Double,
    val sceneReferred: Boolean,
    val subjectHistogram: DoubleArray = centreHistogram,
    val faceHistogram: DoubleArray? = null,
    val faceArea: Double = 0.0,
    val faceCount: Int = 0,
) {
    init {
        require(
            histogram.size == BINS && centreHistogram.size == BINS && toneTable.size == BINS &&
                subjectHistogram.size == BINS && (faceHistogram == null || faceHistogram.size == BINS),
        ) {
            "analysis tables must have $BINS bins"
        }
    }

    /** The faces' skin key: the median stop of [faceHistogram], `null` without faces. */
    val faceKey: Double? get() = faceHistogram?.let { percentile(HALF, it) }

    /** The [q]-quantile (0..1) of [hist] in stops, linear inside a bin. */
    fun percentile(q: Double, hist: DoubleArray = histogram): Double {
        var acc = 0.0
        for (i in 0 until BINS) {
            val next = acc + hist[i]
            if (next >= q && hist[i] > 0.0) {
                val f = ((q - acc) / hist[i]).coerceIn(0.0, 1.0)
                return MIN_STOPS + (i + f) / BINS_PER_STOP
            }
            acc = next
        }
        return MAX_STOPS
    }

    /** Mean of `s` over the quantiles [lo]..[hi] of [hist] (bin centres): a trimmed log-average ("key"). */
    fun trimmedMean(lo: Double, hi: Double, hist: DoubleArray = histogram): Double {
        var acc = 0.0
        var sum = 0.0
        var weight = 0.0
        for (i in 0 until BINS) {
            val a = max(acc, lo)
            val b = min(acc + hist[i], hi)
            if (b > a) {
                sum += (b - a) * centre(i)
                weight += b - a
            }
            acc += hist[i]
        }
        return if (weight > 0.0) sum / weight else percentile(HALF, hist)
    }

    /** Display luminance of a neutral at scene stop [s] through the profile (linear between bin centres). */
    fun display(s: Double): Double {
        val x = (s - MIN_STOPS) * BINS_PER_STOP - HALF
        return when {
            x <= 0.0 -> toneTable[0] * StrictMath.pow(2.0, s - centre(0))

            // the toe is linear in light
            x >= BINS - 1 -> toneTable[BINS - 1]

            else -> {
                val i = floor(x).toInt()
                toneTable[i] + (toneTable[i + 1] - toneTable[i]) * (x - i)
            }
        }
    }

    /** The scene stop whose display luminance is [y] (the inverse of [display]; the profile is monotonic). */
    fun sceneFor(y: Double): Double {
        var lo = MIN_STOPS - SEARCH_MARGIN
        var hi = MAX_STOPS
        if (display(hi) <= y) return hi
        repeat(BISECTIONS) {
            val mid = (lo + hi) * HALF
            if (display(mid) < y) lo = mid else hi = mid
        }
        return (lo + hi) * HALF
    }

    /** The display-referred lightness histogram (`cbrt(Y)`, [DISPLAY_BINS] bins over 0..1) at exposure 0. */
    fun displayHistogram(): DoubleArray {
        val out = DoubleArray(DISPLAY_BINS)
        for (i in 0 until BINS) {
            val p = StrictMath.cbrt(display(centre(i)).coerceIn(0.0, 1.0))
            out[min((p * DISPLAY_BINS).toInt(), DISPLAY_BINS - 1)] += histogram[i]
        }
        return out
    }

    companion object {
        const val MIN_STOPS: Double = -16.0
        const val MAX_STOPS: Double = 6.0
        const val BINS_PER_STOP: Int = 16
        const val BINS: Int = ((MAX_STOPS - MIN_STOPS) * BINS_PER_STOP).toInt()
        const val DISPLAY_BINS: Int = 64
        private const val HALF = 0.5
        private const val SEARCH_MARGIN = 8.0
        private const val BISECTIONS = 48

        /** Scene stop at the centre of bin [i]. */
        fun centre(i: Int): Double = MIN_STOPS + (i + HALF) / BINS_PER_STOP

        /** Bin of scene stop [s] (clamped to the end bins). */
        fun bin(s: Double): Int = floor((s - MIN_STOPS) * BINS_PER_STOP).toInt().coerceIn(0, BINS - 1)
    }
}

/**
 * Builds [AutoAnalysis] on the CPU reference (deterministic on every device, unlike a GPU readback whose float
 * behaviour differs between drivers) from a small point-sampled copy of the proxy ([sample]).
 */
object AutoAnalyser {
    /** Longest side of the analysis sample (about 170 000 pixels for 3:2). */
    const val ANALYSIS_SIDE: Int = 512

    private const val CENTRE_BOOST = 2.0
    private const val SUBJECT_BOOST = 2.0
    private const val SKIN_RX = 0.6f
    private const val SKIN_RY = 0.6f
    private const val SKIN_DY = 0.08f
    private const val CENTRE_SIGMA = 0.2
    private const val CLIP_LEVEL = 0.99f
    private const val CHROMA_Y_MIN = 0.05f
    private const val CHROMA_Y_MAX = 0.8f
    private const val CHROMA_BINS = 400
    private const val CHROMA_MAX = 0.4
    private const val LN2 = 0.6931471805599453
    private const val MIN_Y = 9.5367431640625e-7 // 2^-20, as the local tone operator

    /**
     * A copy of [input] with at most [maxSide] pixels on its long side, **point-sampled** at the centres of equal
     * cells (not averaged: a clipped pixel stays clipped, so highlight reconstruction and the clipping fraction see
     * real pixels). Keeps the colour metadata.
     */
    fun sample(input: PipelineInput, maxSide: Int = ANALYSIS_SIDE): PipelineInput {
        val long = max(input.width, input.height)
        if (long <= maxSide) return input
        val w = max(1, (input.width.toLong() * maxSide / long).toInt())
        val h = max(1, (input.height.toLong() * maxSide / long).toInt())
        val out = FloatArray(w * h * PipelineInput.CHANNELS)
        for (y in 0 until h) {
            val sy = ((2L * y + 1) * input.height / (2L * h)).toInt()
            for (x in 0 until w) {
                val sx = ((2L * x + 1) * input.width / (2L * w)).toInt()
                val s = (sy * input.width + sx) * PipelineInput.CHANNELS
                val o = (y * w + x) * PipelineInput.CHANNELS
                out[o] = input.linearRgb[s]
                out[o + 1] = input.linearRgb[s + 1]
                out[o + 2] = input.linearRgb[s + 2]
            }
        }
        return PipelineInput(
            input.kind,
            w,
            h,
            out,
            input.cameraToXyz,
            input.asShotMultipliers?.copyOf(),
            input.saturation,
        )
    }

    /**
     * Analyses [sample] as [recipe]'s profile and white balance render it, with every Light and colour slider at
     * "no change" (so the analysis, and Auto's result, does not depend on the values Auto sets: tapping Auto twice
     * gives the same values). Pixels outside the recipe's crop are ignored. [faces] and [saliency] are the photo's
     * ([PhotoFeatures]): functions of the photo only, so they keep Auto idempotent too.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun analyse(
        sample: PipelineInput,
        recipe: Recipe,
        faces: List<Face> = emptyList(),
        saliency: SaliencyMap? = null,
    ): AutoAnalysis {
        val base = Recipe(
            profile = recipe.profile,
            colour = Colour(whiteBalance = recipe.colour.whiteBalance),
        )
        val params = DevelopParams.from(sample, base)
        val rgb = sample.linearRgb.copyOf()
        RawWhiteBalanceOp.apply(rgb, params.wbMultipliers)
        val hp = params.highlights
        val saturation = sample.saturation
        if (hp != null && saturation != null && HighlightReconstructionOp.needed(sample.linearRgb, saturation)) {
            val grid = LowResGrid(sample.width, sample.height)
            HighlightReconstructionOp.apply(rgb, grid, hp, HighlightReconstructionOp.chroma(rgb, grid, hp))
        }
        CameraMatrixOp.apply(rgb, params.cameraToWorking)
        val inside = cropMask(sample, recipe)

        val hist = DoubleArray(AutoAnalysis.BINS)
        val centre = DoubleArray(AutoAnalysis.BINS)
        val subject = DoubleArray(AutoAnalysis.BINS)
        val skin = DoubleArray(AutoAnalysis.BINS)
        val crop = recipe.geometry.crop
        val inCrop = faces.filter { f ->
            crop == null || (f.centreX in crop.left..crop.right && f.centreY in crop.top..crop.bottom)
        }
        var clipped = 0
        var count = 0
        val clipLevel = saturation?.let { it * CLIP_LEVEL } ?: Float.MAX_VALUE
        for (y in 0 until sample.height) {
            val dy = (y + HALF) / sample.height - HALF
            for (x in 0 until sample.width) {
                val p = y * sample.width + x
                if (!inside(x, y)) continue
                val i = p * PipelineInput.CHANNELS
                val lum = Luma.of(rgb[i], rgb[i + 1], rgb[i + 2]).toDouble()
                val s = StrictMath.log(max(lum, MIN_Y)) / LN2
                val dx = (x + HALF) / sample.width - HALF
                val b = AutoAnalysis.bin(s)
                hist[b] += 1.0
                val centreWeight = 1.0 + CENTRE_BOOST * exp(-(dx * dx + dy * dy) / (2 * CENTRE_SIGMA * CENTRE_SIGMA))
                centre[b] += centreWeight
                val u = ((x + HALF) / sample.width).toFloat()
                val v = ((y + HALF) / sample.height).toFloat()
                subject[b] += saliency?.let { 1.0 + SUBJECT_BOOST * it.at(u, v) } ?: centreWeight
                for (f in inCrop) if (inSkin(f, u, v)) skin[b] += f.score.toDouble()
                count++
                val raw = sample.linearRgb
                if (raw[i] >= clipLevel || raw[i + 1] >= clipLevel || raw[i + 2] >= clipLevel) clipped++
            }
        }
        normalise(hist)
        normalise(centre)
        normalise(subject)
        val hasSkin = skin.sum() > 0.0
        normalise(skin)

        ProfileOp.apply(rgb, params.profile)
        val chroma = medianChroma(rgb, sample, inside)

        val table = DoubleArray(AutoAnalysis.BINS) { i ->
            val grey = StrictMath.pow(2.0, AutoAnalysis.centre(i)).toFloat()
            val g = floatArrayOf(grey, grey, grey)
            ProfileOp.apply(g, params.profile)
            Luma.of(g[0], g[1], g[2]).toDouble()
        }
        return AutoAnalysis(
            histogram = hist,
            centreHistogram = centre,
            toneTable = table,
            clippedFraction = if (count == 0) 0.0 else clipped.toDouble() / count,
            medianChroma = chroma,
            sceneReferred = sample.kind == InputKind.SCENE_REFERRED,
            subjectHistogram = subject,
            faceHistogram = if (hasSkin) skin else null,
            faceArea = faceArea(inCrop, recipe),
            faceCount = inCrop.size,
        )
    }

    /**
     * True when normalised ([u], [v]) lies on [face]'s skin: an ellipse centred a little below the box centre (the
     * box runs from the brows to the chin), [SKIN_RX] x [SKIN_RY] of the box's half-size, so hair, eyes and the
     * background around a tilted face stay out.
     */
    fun inSkin(face: Face, u: Float, v: Float): Boolean {
        val dx = (u - face.centreX) / (face.width * HALF_F * SKIN_RX)
        val dy = (v - face.centreY - face.height * SKIN_DY) / (face.height * HALF_F * SKIN_RY)
        return dx * dx + dy * dy <= 1f
    }

    private fun faceArea(faces: List<Face>, recipe: Recipe): Double {
        val crop = recipe.geometry.crop
        val cropArea = crop?.let { ((it.right - it.left) * (it.bottom - it.top)).toDouble() } ?: 1.0
        return (faces.sumOf { (it.width * it.height).toDouble() } / cropArea).coerceIn(0.0, 1.0)
    }

    private fun medianChroma(rgb: FloatArray, sample: PipelineInput, inside: (Int, Int) -> Boolean): Double {
        val bins = IntArray(CHROMA_BINS)
        val lab = FloatArray(3)
        var n = 0
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val i = (y * sample.width + x) * PipelineInput.CHANNELS
                val lum = Luma.of(rgb[i], rgb[i + 1], rgb[i + 2])
                if (inside(x, y) && lum >= CHROMA_Y_MIN && lum <= CHROMA_Y_MAX) {
                    OkLab.fromRgb(rgb[i], rgb[i + 1], rgb[i + 2], lab)
                    val c = sqrt((lab[1] * lab[1] + lab[2] * lab[2]).toDouble())
                    bins[min((c / CHROMA_MAX * CHROMA_BINS).toInt(), CHROMA_BINS - 1)]++
                    n++
                }
            }
        }
        var acc = 0
        val median = (0 until CHROMA_BINS).firstOrNull { i ->
            acc += bins[i]
            acc * 2 >= n
        } ?: (CHROMA_BINS - 1)
        return if (n == 0) 0.0 else (median + HALF) * CHROMA_MAX / CHROMA_BINS
    }

    /** True for pixels inside [recipe]'s crop (the whole frame without one). */
    private fun cropMask(sample: PipelineInput, recipe: Recipe): (Int, Int) -> Boolean {
        val crop = recipe.geometry.crop ?: return { _, _ -> true }
        val x0 = crop.left * sample.width
        val x1 = crop.right * sample.width
        val y0 = crop.top * sample.height
        val y1 = crop.bottom * sample.height
        return { x, y -> x + HALF_F >= x0 && x + HALF_F < x1 && y + HALF_F >= y0 && y + HALF_F < y1 }
    }

    private fun normalise(h: DoubleArray) {
        val sum = h.sum()
        if (sum > 0.0) for (i in h.indices) h[i] /= sum
    }

    private const val HALF = 0.5
    private const val HALF_F = 0.5f
}
