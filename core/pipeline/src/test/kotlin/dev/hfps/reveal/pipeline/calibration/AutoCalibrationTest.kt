@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.AutoAnalyser
import dev.hfps.reveal.pipeline.auto.AutoAnalysis
import dev.hfps.reveal.pipeline.auto.AutoParams
import dev.hfps.reveal.pipeline.auto.AutoRules
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OkLab
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import java.util.Locale
import java.util.stream.IntStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in calibration of Auto's constants (docs/adr/0010, "Calibration") on COPIES of the owner's RAW+JPEG pairs,
 * prepared like the Camera JPEG match calibration (`scripts/calibration/calibrate.sh` decodes each ARW to
 * `<name>.rdump` next to the copied `<name>.JPG`; run this class with `--tests '*AutoCalibrationTest*'`).
 *
 * For every pair: Auto (Standard profile, as-shot white balance) on the analysis sample of the RAW, the CPU render
 * of the result at about 640 px, and the camera JPEG at the same size. Compared: CIE L* at the 5/25/50/75/95th
 * percentiles of luminance and the median OkLab chroma of mid-tones. The camera's own exposure, DRO and colour engine
 * are the reference for "natural"; the owner's taste is "bright", which [BRIGHT_BIAS] adds on top afterwards.
 * Prints only aggregates (`AUTO-CALIB` lines) and the fitted constants (`AUTO-CONST`), never names or pixels.
 */
class AutoCalibrationTest {
    private val dir: File? = System.getenv("REVEAL_CALIBRATION_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?.takeIf { it.isDirectory }

    private class Stats(val lStar: DoubleArray, val chroma: Double)

    private class Pair(val input: PipelineInput, val analysis: AutoAnalysis, val camera: Stats, val asShot: Stats)

    @Test
    fun `calibrate auto against the camera JPEGs`() {
        val folder = dir
        assumeTrue("REVEAL_CALIBRATION_DIR not set", folder != null)
        val files = CalibrationPairs.pairs(folder!!)
        val pairs = files.map { (dump, jpg) ->
            val raw = RawDump.read(dump)
            val input = raw.pipelineInput(maxOf(1, raw.width / SIDE))
            val analysis = AutoAnalyser.analyse(AutoAnalyser.sample(input), Recipe())
            Pair(input, analysis, jpegStats(jpg), renderStats(input, Recipe()))
        }
        val split = SESSION_SPLIT.coerceAtMost(pairs.size - 1)
        report("as shot (no Auto)", pairs, pairs.map { it.asShot })
        // Taste limits (not fitted): a small contrast rule, at least a little shadow lift, vibrance at most +20.
        var params = AutoParams(contrastStrength = 0.2, shadowFloor = 0.01, maxVibrance = 20)
        report("auto, initial constants", pairs, evaluate(pairs, params))

        // Stage 1: exposure constants on the median alone. Stage 2: tone constants on the shape (every percentile
        // relative to the pair's own median error) and the colour, so tone sliders cannot hide exposure errors.
        val exposureAxes: List<kotlin.Pair<String, (AutoParams, Double) -> AutoParams>> = listOf(
            "targetGrey" to { p, v -> p.copy(targetGrey = v) },
            "keyCentreWeight" to { p, v -> p.copy(keyCentreWeight = v) },
            "keyMedianWeight" to { p, v -> p.copy(keyMedianWeight = v) },
        )
        val toneAxes: List<kotlin.Pair<String, (AutoParams, Double) -> AutoParams>> = listOf(
            "shadowFloor" to { p, v -> p.copy(shadowFloor = v) },
            "highlightCeiling" to { p, v -> p.copy(highlightCeiling = v) },
            "contrastTarget" to { p, v -> p.copy(contrastTarget = v) },
            "whitePoint" to { p, v -> p.copy(whitePoint = v) },
            "blackPoint" to { p, v -> p.copy(blackPoint = v) },
            "vibranceTarget" to { p, v -> p.copy(vibranceTarget = v) },
        )
        val grids = mapOf(
            "targetGrey" to (0..32).map { 0.12 + it * 0.005 },
            "keyCentreWeight" to (0..4).map { it * 0.25 },
            "keyMedianWeight" to (0..4).map { it * 0.25 },
            "shadowFloor" to (0..10).map { 0.01 + it * 0.005 },
            "highlightCeiling" to (0..6).map { 0.80 + it * 0.025 },
            "contrastTarget" to (0..10).map { 0.20 + it * 0.02 },
            "contrastStrength" to (0..5).map { it * 0.2 },
            "whitePoint" to (0..8).map { 0.90 + it * 0.01 },
            "blackPoint" to (0..12).map { 0.02 + it * 0.02 },
            "vibranceTarget" to (0..10).map { 0.04 + it * 0.01 },
        )
        fun descend(
            start: AutoParams,
            axes: List<kotlin.Pair<String, (AutoParams, Double) -> AutoParams>>,
            stage: Int,
        ): AutoParams {
            var params = start
            repeat(ROUNDS) { round ->
                for ((name, set) in axes) {
                    var best = params
                    var bestLoss = loss(pairs, params, stage)
                    for (v in grids.getValue(name)) {
                        val candidate = set(params, v)
                        val l = loss(pairs, candidate, stage)
                        if (l < bestLoss - 1e-9) {
                            bestLoss = l
                            best = candidate
                        }
                    }
                    params = best
                }
                println(
                    String.format(
                        Locale.ROOT,
                        "AUTO-CALIB stage %d round %d loss %.3f",
                        stage,
                        round + 1,
                        loss(pairs, params, stage),
                    ),
                )
            }
            return params
        }
        if (System.getenv("REVEAL_AUTO_FIT") == "1") {
            params = descend(params, exposureAxes, 1)
            params = descend(params, toneAxes, 2)
            params = descend(params, exposureAxes, 1)
        }
        val fitted = evaluate(pairs, params)
        report("auto, fitted (all pairs)", pairs, fitted)
        report("auto, fitted, session 1", pairs.take(split), fitted.take(split))
        report("auto, fitted, session 2", pairs.drop(split), fitted.drop(split))
        val bright = params.copy(targetGrey = params.targetGrey * BRIGHT_BIAS)
        report("auto, fitted + bright bias", pairs, evaluate(pairs, bright))
        // Robustness: the same RAWs 1.5 EV under-exposed (no new clipping), with and without Auto.
        val dark = pairs.map { pair ->
            val src = pair.input
            val scaled = FloatArray(src.linearRgb.size) { src.linearRgb[it] * UNDER }
            val input =
                PipelineInput(
                    src.kind,
                    src.width,
                    src.height,
                    scaled,
                    src.cameraToXyz,
                    src.asShotMultipliers,
                    src.saturation,
                )
            Pair(
                input,
                AutoAnalyser.analyse(AutoAnalyser.sample(input), Recipe()),
                pair.camera,
                renderStats(input, Recipe()),
            )
        }
        report("-1.5 EV, as shot (no Auto)", dark, dark.map { it.asShot })
        report("-1.5 EV, auto (bright)", dark, evaluate(dark, bright))
        println("AUTO-CONST $params")
        println("AUTO-CONST bright targetGrey ${bright.targetGrey}")
        values("fitted + bright bias", pairs, bright)
        val shipped = AutoParams()
        for ((name, candidate) in listOf("shipped" to shipped)) {
            report("auto, $name", pairs, evaluate(pairs, candidate))
            println(
                String.format(
                    Locale.ROOT,
                    "AUTO-CALIB auto, %s: median loss %.2f, shape loss %.2f",
                    name,
                    loss(pairs, candidate, 1),
                    loss(pairs, candidate, 2),
                ),
            )
            values(name, pairs, candidate)
        }
        report("-1.5 EV, auto (shipped)", dark, evaluate(dark, shipped))
    }

    private fun values(name: String, pairs: List<Pair>, p: AutoParams) {
        val results = pairs.map { AutoRules.compute(it.analysis, p) }
        fun range(f: (dev.hfps.reveal.pipeline.auto.AutoResult) -> Float) = results.map(f).let {
            String.format(Locale.ROOT, "%.2f..%.2f (median %.2f)", it.min(), it.max(), it.sorted()[it.size / 2])
        }
        println(
            "AUTO-CALIB values $name: exposure ${range {
                it.exposureEv
            }} contrast ${range {
                it.contrast
            }} highlights ${range {
                it.highlights
            }} shadows ${range {
                it.shadows
            }} whites ${range { it.whites }} blacks ${range { it.blacks }} vibrance ${range { it.vibrance }}",
        )
    }

    private fun evaluate(pairs: List<Pair>, p: AutoParams): List<Stats> {
        val out = arrayOfNulls<Stats>(pairs.size)
        IntStream.range(0, pairs.size).parallel().forEach { i ->
            val recipe = AutoRules.applyTo(Recipe(), AutoRules.compute(pairs[i].analysis, p), AutoScope.ALL)
            out[i] = renderStats(pairs[i].input, recipe)
        }
        return out.map { it!! }
    }

    private fun loss(pairs: List<Pair>, p: AutoParams, stage: Int): Double {
        val stats = evaluate(pairs, p)
        var sum = 0.0
        pairs.forEachIndexed { i, pair ->
            val median = stats[i].lStar[2] - pair.camera.lStar[2]
            if (stage == 1) {
                sum += median * median
            } else {
                for (q in QUANTILES.indices) {
                    val d = stats[i].lStar[q] - pair.camera.lStar[q] - median
                    sum += QUANTILE_WEIGHTS[q] * d * d
                }
                val dc = (stats[i].chroma - pair.camera.chroma) * CHROMA_SCALE
                sum += dc * dc
            }
        }
        return sum / pairs.size
    }

    private fun report(name: String, pairs: List<Pair>, stats: List<Stats>) {
        val sb = StringBuilder("AUTO-CALIB $name (${pairs.size} pairs): ΔL* mean / mean|.|")
        for (q in QUANTILES.indices) {
            val d = pairs.indices.map { stats[it].lStar[q] - pairs[it].camera.lStar[q] }
            sb.append(
                String.format(
                    Locale.ROOT,
                    "  p%02d %+.2f / %.2f",
                    (QUANTILES[q] * 100).toInt(),
                    d.average(),
                    d.map(::abs).average(),
                ),
            )
        }
        val dc = pairs.indices.map { stats[it].chroma - pairs[it].camera.chroma }
        sb.append(String.format(Locale.ROOT, "  chroma %+.4f / %.4f", dc.average(), dc.map(::abs).average()))
        val medianL = pairs.indices.map { abs(stats[it].lStar[2] - pairs[it].camera.lStar[2]) }.sorted()
        sb.append(
            String.format(Locale.ROOT, "  |ΔL* p50| median %.2f max %.2f", medianL[medianL.size / 2], medianL.last()),
        )
        println(sb)
    }

    private fun renderStats(input: PipelineInput, recipe: Recipe): Stats {
        val rgb = CpuDevelop.renderLinear(input, recipe)
        val n = input.width * input.height
        val y = DoubleArray(n)
        val chroma = ArrayList<Double>()
        val lab = FloatArray(3)
        for (i in 0 until n) {
            val r = rgb[i * 3].coerceIn(0f, 1f)
            val g = rgb[i * 3 + 1].coerceIn(0f, 1f)
            val b = rgb[i * 3 + 2].coerceIn(0f, 1f)
            y[i] = 0.2627 * r + 0.6780 * g + 0.0593 * b
            if (y[i] in 0.05..0.8) {
                OkLab.fromRgb(r, g, b, lab)
                chroma += sqrt((lab[1] * lab[1] + lab[2] * lab[2]).toDouble())
            }
        }
        return Stats(lStarPercentiles(y), median(chroma))
    }

    private fun jpegStats(file: File): Stats {
        val image = ImageIO.read(file)
        val step = maxOf(1, image.width / SIDE)
        val toRec2020 = ColorSpaces.REC2020.fromXyz * ColorSpaces.SRGB.toXyz
        val y = ArrayList<Double>()
        val chroma = ArrayList<Double>()
        val lab = FloatArray(3)
        var yy = step / 2
        while (yy < image.height) {
            var xx = step / 2
            while (xx < image.width) {
                val p = image.getRGB(xx, yy)
                val lin = doubleArrayOf(LIN[p shr 16 and 0xff], LIN[p shr 8 and 0xff], LIN[p and 0xff])
                val rec = toRec2020.times(lin)
                val lum = 0.2627 * rec[0] + 0.6780 * rec[1] + 0.0593 * rec[2]
                y += lum
                if (lum in 0.05..0.8) {
                    OkLab.fromRgb(rec[0].toFloat(), rec[1].toFloat(), rec[2].toFloat(), lab)
                    chroma += sqrt((lab[1] * lab[1] + lab[2] * lab[2]).toDouble())
                }
                xx += step
            }
            yy += step
        }
        return Stats(lStarPercentiles(y.toDoubleArray()), median(chroma))
    }

    private fun lStarPercentiles(y: DoubleArray): DoubleArray {
        val sorted = y.sortedArray()
        return DoubleArray(QUANTILES.size) { lStar(sorted[((sorted.size - 1) * QUANTILES[it]).toInt()]) }
    }

    private fun lStar(y: Double): Double {
        val t = y.coerceIn(0.0, 1.0)
        return if (t > 216.0 / 24389) 116 * StrictMath.cbrt(t) - 16 else t * 24389.0 / 27
    }

    private fun median(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sorted()[v.size / 2]

    private companion object {
        const val SIDE = 640
        const val SESSION_SPLIT = 17
        const val ROUNDS = 2
        const val UNDER = 0.35355339f // 2^-1.5
        const val CHROMA_SCALE = 100.0
        const val BRIGHT_BIAS = 1.07
        val QUANTILES = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)
        val QUANTILE_WEIGHTS = doubleArrayOf(0.5, 1.0, 2.0, 1.0, 0.5)
        val LIN = DoubleArray(256) {
            val v = it / 255.0
            if (v <= 0.04045) v / 12.92 else StrictMath.pow((v + 0.055) / 1.055, 2.4)
        }
    }
}
