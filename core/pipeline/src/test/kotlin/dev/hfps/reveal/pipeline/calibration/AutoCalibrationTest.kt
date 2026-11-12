@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.AutoAnalyser
import dev.hfps.reveal.pipeline.auto.AutoAnalysis
import dev.hfps.reveal.pipeline.auto.AutoParams
import dev.hfps.reveal.pipeline.auto.AutoRules
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.auto.PhotoFeatures
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.faces.Face
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
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
 *
 * Faces (M3, docs/adr/0010 "Faces"): both images are turned upright (EXIF orientation), the photo's features
 * ([PhotoFeatures]: faces, subject map) are found on the RAW, and the faces' skin L* (median inside the skin
 * ellipses) is compared too: the ZV-E10 meters on faces, so its JPEG is the reference for face brightness. Rows are
 * reported for all pairs and for the pairs with and without faces; `AUTO-WB` lines compare the Auto white balance
 * with and without the face rules against the camera's own (as-shot) white balance and the faces' skin hue.
 */
class AutoCalibrationTest {
    private val dir: File? = System.getenv("REVEAL_CALIBRATION_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?.takeIf { it.isDirectory }

    private class Stats(val lStar: DoubleArray, val chroma: Double, val faceL: Double = Double.NaN, val faceHue: Double = Double.NaN)

    private class Pair(
        val input: PipelineInput,
        val analysis: AutoAnalysis,
        val camera: Stats,
        val asShot: Stats,
        val features: PhotoFeatures? = null,
        val plain: AutoAnalysis = analysis,
    ) {
        val faces: List<Face> get() = features?.faces.orEmpty()
    }

    @Test
    fun `calibrate auto against the camera JPEGs`() {
        val folder = dir
        assumeTrue("REVEAL_CALIBRATION_DIR not set", folder != null)
        val files = CalibrationPairs.pairs(folder!!)
        val pairs = files.map { (dump, jpg) ->
            val raw = RawDump.read(dump)
            val turns = UprightPairs.turns(UprightPairs.orientation(jpg))
            val input = UprightPairs.rotate(raw.pipelineInput(maxOf(1, raw.width / SIDE)), turns)
            val features = PhotoFeatures.of(input)
            val sample = features.sample
            val plain = AutoAnalyser.analyse(sample, Recipe())
            val analysis = AutoAnalyser.analyse(sample, Recipe(), features.faces, features.saliency)
            Pair(
                input,
                analysis,
                jpegStats(jpg, turns, features.faces),
                renderStats(input, Recipe(), features.faces),
                features,
                plain,
            )
        }
        val withFaces = pairs.count { it.faces.isNotEmpty() }
        println(
            "AUTO-CALIB faces: $withFaces of ${pairs.size} pairs; faces per pair " +
                pairs.map { it.faces.size }.sorted().joinToString(","),
        )
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

        // M3: the shipped constants without the photo's features (= M1's Auto) against the face-aware Auto.
        val m1 = evaluate(pairs, shipped, features = false)
        val m3 = evaluate(pairs, shipped)
        for ((name, subset) in listOf(
            "all" to pairs.indices.toList(),
            "faces" to pairs.indices.filter { pairs[it].faces.isNotEmpty() },
            "no faces" to pairs.indices.filter { pairs[it].faces.isEmpty() },
        )) {
            if (subset.isEmpty()) continue
            report("as shot, $name", subset.map { pairs[it] }, subset.map { pairs[it].asShot })
            report("M1 auto (no features), $name", subset.map { pairs[it] }, subset.map { m1[it] })
            report("M3 auto (faces + subject), $name", subset.map { pairs[it] }, subset.map { m3[it] })
        }
        values("M3 auto", pairs, shipped)
        if (System.getenv("REVEAL_AUTO_FIT").orEmpty() in setOf("1", "faces")) fitFaces(pairs, shipped)
        whiteBalance(pairs)
    }

    /** Stage 3 (opt-in): the face constants on the pairs with faces (face L* and median L*). */
    private fun fitFaces(pairs: List<Pair>, start: AutoParams) {
        val faced = pairs.filter { it.faces.isNotEmpty() }
        if (faced.isEmpty()) return
        fun loss(p: AutoParams): Double {
            val stats = evaluate(faced, p)
            return faced.indices.sumOf { i ->
                val f = stats[i].faceL - faced[i].camera.faceL
                val m = stats[i].lStar[2] - faced[i].camera.lStar[2]
                (if (f.isNaN()) 0.0 else f * f) + 0.5 * m * m
            } / faced.size
        }
        val axes: List<kotlin.Pair<String, kotlin.Pair<List<Double>, (AutoParams, Double) -> AutoParams>>> = listOf(
            "faceTarget" to ((0..20).map { 0.06 + it * 0.01 } to { p, v -> p.copy(faceTarget = v) }),
            "faceTolerance" to ((0..15).map { it * 0.1 } to { p, v -> p.copy(faceTolerance = v) }),
            "faceWeight" to ((0..10).map { it * 0.1 } to { p, v -> p.copy(faceWeight = v) }),
            "faceAreaFull" to (listOf(0.0005, 0.001, 0.002, 0.005, 0.01, 0.02) to { p, v -> p.copy(faceAreaFull = v) }),
        )
        var params = start
        repeat(ROUNDS) { round ->
            for ((_, axis) in axes) {
                val (grid, set) = axis
                params = (grid.map { set(params, it) } + params).minBy(::loss)
            }
            println(String.format(Locale.ROOT, "AUTO-CALIB faces stage round %d loss %.3f", round + 1, loss(params)))
        }
        println(
            "AUTO-CONST faces faceTarget ${params.faceTarget} faceTolerance ${params.faceTolerance} " +
                "faceWeight ${params.faceWeight} faceAreaFull ${params.faceAreaFull}",
        )
        report("M3 auto, fitted face constants, faces", faced, evaluate(faced, params))
    }

    /**
     * The Auto white balance with and without the face rules against the camera's as-shot white balance (its own
     * auto WB: a reference, not the truth) and, on the pairs with faces, the skin hue against the camera JPEG's.
     */
    private fun whiteBalance(pairs: List<Pair>) {
        val rows = pairs.mapNotNull { pair ->
            val wb = pair.features?.wb ?: return@mapNotNull null
            val asShot = WhiteBalanceModel.asShot(pair.input)
            val plain = AutoWhiteBalance.estimate(wb)
            val guarded = AutoWhiteBalance.estimate(wb, faces = pair.faces)
            fun hue(cct: WhiteBalanceModel.Cct): Double {
                if (pair.faces.isEmpty()) return Double.NaN
                val w = WhiteBalance(mode = WbMode.CUSTOM, temperatureK = cct.temperatureK.toFloat(), tint = cct.tint.toFloat())
                return renderStats(pair.input, Recipe(colour = Colour(whiteBalance = w)), pair.faces).faceHue
            }
            arrayOf(
                MIRED / plain.temperatureK - MIRED / asShot.temperatureK,
                plain.tint - asShot.tint,
                MIRED / guarded.temperatureK - MIRED / asShot.temperatureK,
                guarded.tint - asShot.tint,
                hueDiff(hue(plain), pair.camera.faceHue),
                hueDiff(hue(guarded), pair.camera.faceHue),
                hueDiff(pair.asShot.faceHue, pair.camera.faceHue),
                if (pair.faces.isEmpty()) 0.0 else 1.0,
            )
        }
        fun line(name: String, r: List<Array<Double>>) {
            if (r.isEmpty()) return
            fun mean(i: Int) = r.map { it[i] }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }
            fun meanAbs(i: Int) = r.map { abs(it[i]) }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }
            println(
                String.format(
                    Locale.ROOT,
                    "AUTO-WB %s (%d): vs camera as shot: plain Δmired %+.1f / %.1f Δtint %+.1f / %.1f; with faces Δmired %+.1f / %.1f Δtint %+.1f / %.1f; skin hue vs camera JPEG |Δ| as shot %.1f°, plain %.1f°, with faces %.1f° (mean %+.1f / %+.1f / %+.1f)",
                    name, r.size, mean(0), meanAbs(0), mean(1), meanAbs(1), mean(2), meanAbs(2), mean(3), meanAbs(3),
                    meanAbs(6), meanAbs(4), meanAbs(5), mean(6), mean(4), mean(5),
                ),
            )
        }
        val guardHues = pairs.mapNotNull { pair ->
            val wb = pair.features?.wb ?: return@mapNotNull null
            if (pair.faces.isEmpty()) return@mapNotNull null
            AutoWhiteBalance.faceSkinHue(wb, pair.faces, WhiteBalanceModel.asShot(pair.input))
        }.sorted()
        val cameraHues = pairs.map { it.camera.faceHue }.filter { !it.isNaN() }.sorted()
        println("AUTO-WB skin hue (guard measure) at the camera's as shot: " + guardHues.joinToString(" ") { String.format(Locale.ROOT, "%.0f", it) })
        println("AUTO-WB skin hue of the camera JPEGs: " + cameraHues.joinToString(" ") { String.format(Locale.ROOT, "%.0f", it) })
        line("all", rows)
        line("faces", rows.filter { it[7] > 0 })
        line("no faces", rows.filter { it[7] == 0.0 })
    }

    private fun hueDiff(a: Double, b: Double): Double {
        if (a.isNaN() || b.isNaN()) return Double.NaN
        var d = a - b
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
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

    private fun evaluate(pairs: List<Pair>, p: AutoParams, features: Boolean = true): List<Stats> {
        val out = arrayOfNulls<Stats>(pairs.size)
        IntStream.range(0, pairs.size).parallel().forEach { i ->
            val analysis = if (features) pairs[i].analysis else pairs[i].plain
            val recipe = AutoRules.applyTo(Recipe(), AutoRules.compute(analysis, p), AutoScope.ALL)
            out[i] = renderStats(pairs[i].input, recipe, pairs[i].faces)
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
        val face = pairs.indices.map { stats[it].faceL - pairs[it].camera.faceL }.filter { !it.isNaN() }
        if (face.isNotEmpty()) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "  face ΔL* %+.2f / %.2f (max %.2f, %d)",
                    face.average(),
                    face.map(::abs).average(),
                    face.maxOf(::abs),
                    face.size,
                ),
            )
        }
        println(sb)
    }

    private fun renderStats(input: PipelineInput, recipe: Recipe, faces: List<Face> = emptyList()): Stats {
        val rgb = CpuDevelop.renderLinear(input, recipe)
        val n = input.width * input.height
        val y = DoubleArray(n)
        val chroma = ArrayList<Double>()
        val skin = SkinStats()
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
            skin.add(faces, (i % input.width + 0.5f) / input.width, (i / input.width + 0.5f) / input.height, r, g, b)
        }
        return Stats(lStarPercentiles(y), median(chroma), skin.lStar(), skin.hue())
    }

    /** Median L* and mean OkLab hue of the pixels on the faces' skin. */
    private class SkinStats {
        private val y = ArrayList<Double>()
        private var a = 0.0
        private var b = 0.0
        private val lab = FloatArray(3)

        fun add(faces: List<Face>, u: Float, v: Float, r: Float, g: Float, bl: Float) {
            if (faces.none { AutoAnalyser.inSkin(it, u, v) }) return
            y += 0.2627 * r + 0.6780 * g + 0.0593 * bl
            OkLab.fromRgb(r, g, bl, lab)
            a += lab[1]
            b += lab[2]
        }

        fun lStar(): Double = if (y.isEmpty()) Double.NaN else lStarOf(y.sorted()[y.size / 2])

        fun hue(): Double = if (y.isEmpty()) Double.NaN else Math.toDegrees(StrictMath.atan2(b, a))
    }

    private fun jpegStats(file: File, turns: Int = 0, faces: List<Face> = emptyList()): Stats {
        val image = UprightPairs.rotate(ImageIO.read(file), turns)
        val step = maxOf(1, maxOf(image.width, image.height) / SIDE)
        val toRec2020 = ColorSpaces.REC2020.fromXyz * ColorSpaces.SRGB.toXyz
        val y = ArrayList<Double>()
        val chroma = ArrayList<Double>()
        val skin = SkinStats()
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
                skin.add(
                    faces,
                    (xx + 0.5f) / image.width,
                    (yy + 0.5f) / image.height,
                    rec[0].toFloat(),
                    rec[1].toFloat(),
                    rec[2].toFloat(),
                )
                xx += step
            }
            yy += step
        }
        return Stats(lStarPercentiles(y.toDoubleArray()), median(chroma), skin.lStar(), skin.hue())
    }

    private fun lStarPercentiles(y: DoubleArray): DoubleArray {
        val sorted = y.sortedArray()
        return DoubleArray(QUANTILES.size) { lStar(sorted[((sorted.size - 1) * QUANTILES[it]).toInt()]) }
    }

    private fun lStar(y: Double): Double = lStarOf(y)

    private fun median(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sorted()[v.size / 2]

    private companion object {
        fun lStarOf(y: Double): Double {
            val t = y.coerceIn(0.0, 1.0)
            return if (t > 216.0 / 24389) 116 * StrictMath.cbrt(t) - 16 else t * 24389.0 / 27
        }

        const val MIRED = 1e6
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
