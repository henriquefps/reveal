@file:Suppress("MagicNumber", "MaxLineLength")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.cpu.CameraMatchZvE10
import dev.hfps.reveal.pipeline.cpu.HueTreatment
import dev.hfps.reveal.pipeline.cpu.ProfileLooks
import dev.hfps.reveal.pipeline.cpu.ProfileOp
import dev.hfps.reveal.pipeline.cpu.ProfileParams
import dev.hfps.reveal.pipeline.cpu.ToneCurve
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in calibration of "Camera JPEG match" and of Standard's tone (docs/adr/0008, "Calibration"). Runs only with
 * `REVEAL_CALIBRATION_DIR` pointing at COPIES of RAW+JPEG pairs prepared by `scripts/calibration/calibrate.sh`;
 * prints aggregates (`CALIB` lines: pair counts, mean/median/p95 ΔE2000) and the fitted constants for
 * `CameraMatchZvE10.kt`, never file names or pixels.
 *
 * Fits (Levenberg-Marquardt on per-patch CIEDE2000, `CalibrationModel.kt`):
 * - **Standard tone:** gain + 3x3 (rows sum to 1) + the [ProfileLooks.Filmic] family in Rec.2020; Standard keeps the
 *   tone only.
 * - **Camera match:** gain + 3x3 + a free 16-knot curve in sRGB primaries + order-3 hue treatment, with weak
 *   regularisation (grey maps to grey, smooth curve, small hue coefficients).
 *
 * Hold-out: the pairs come from two shooting sessions (sorted file order keeps them apart); each model is fitted on
 * one session and measured on the other, both ways, then fitted on all pairs for the shipped constants, which are
 * finally re-measured through the real float operators ([ProfileOp]).
 */
class CameraMatchCalibrationTest {
    private val dir: File? = System.getenv("REVEAL_CALIBRATION_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?.takeIf { it.isDirectory }

    private class Sample(val pair: Int, val reveal: DoubleArray, val jpegLab: DoubleArray)

    @Test
    fun `calibrate camera match and standard tone`() {
        val folder = dir
        assumeTrue("REVEAL_CALIBRATION_DIR not set", folder != null)
        val pairs = CalibrationPairs.pairs(folder!!)
        val samples = ArrayList<Sample>()
        var patchCount = 0L
        pairs.forEachIndexed { i, (dump, jpg) ->
            val all = CalibrationPairs.patches(i, dump, jpg)
            patchCount += all.size
            val random = Random(i)
            all.shuffled(random).take(PER_PAIR).forEach {
                samples += Sample(i, it.reveal, ColourDifference.lab(it.jpeg))
            }
        }
        val sessionSplit = SESSION_SPLIT.coerceAtMost(pairs.size - 1)
        val a = samples.filter { it.pair < sessionSplit }
        val b = samples.filter { it.pair >= sessionSplit }
        println(
            "CALIB pairs=${pairs.size} (sessions $sessionSplit+${pairs.size - sessionSplit}) usable patches=$patchCount sampled=${samples.size}",
        )

        // Hold-out both ways.
        for ((name, train, test) in listOf(Triple("A->B", a, b), Triple("B->A", b, a))) {
            val std = fitStandard(train)
            val cam = fitCamera(train, std)
            report("hold-out $name standard-tone (fit with matrix)", test) { predictStandard(std, it) }
            report("hold-out $name camera-match train", train) { predictCamera(cam, it) }
            report("hold-out $name camera-match test", test) { predictCamera(cam, it) }
        }

        // Final fit on all pairs.
        val std = fitStandard(samples)
        val cam = fitCamera(samples, std)
        report("all standard-tone (fit with matrix)", samples) { predictStandard(std, it) }
        report("all camera-match (double model)", samples) { predictCamera(cam, it) }
        printConstants(std, cam)

        // Through the real float operators: M0 default, the new Standard (fitted tone, neutral colour), camera match.
        val m0 = { s: Sample -> m0Default(s.reveal) }
        report("real-ops M0 default (knee 0.8, gain 1)", samples, m0)
        val filmic = filmicOf(std)
        val stdLook = ProfileLooks.designed(filmic)
        val stdParams = ProfileLooks.params(stdLook, stdLook, 1.0, 1.0)
        report("real-ops new Standard", samples) { realOps(stdParams, it.reveal) }
        val camLook = CameraMatchZvE10.look(cam[0], matrixOf(cam), curveOf(cam), chromaOf(cam), shiftOf(cam))
        val camParams = ProfileLooks.params(stdLook, camLook, 1.0, 1.0)
        report("real-ops Camera JPEG match", samples) { realOps(camParams, it.reveal) }
        report("real-ops Camera JPEG match session A", a) { realOps(camParams, it.reveal) }
        report("real-ops Camera JPEG match session B", b) { realOps(camParams, it.reveal) }
        // The constants in the repository (what ships), when they were pasted from an earlier run.
        val shipped = ProfileLooks.params(ProfileLooks.standardLook, CameraMatchZvE10.look(), 1.0, 1.0)
        report("real-ops shipped Camera JPEG match", samples) { realOps(shipped, it.reveal) }
        val shippedStd = ProfileLooks.params(ProfileLooks.standardLook, ProfileLooks.standardLook, 1.0, 1.0)
        report("real-ops shipped Standard", samples) { realOps(shippedStd, it.reveal) }
    }

    // ------------------------------------------------------------------ Standard: gain + matrix + filmic (Rec.2020)

    private fun matrix(p: DoubleArray, o: Int) = doubleArrayOf(
        1 - p[o] - p[o + 1], p[o], p[o + 1],
        p[o + 2], 1 - p[o + 2] - p[o + 3], p[o + 3],
        p[o + 4], p[o + 5], 1 - p[o + 4] - p[o + 5],
    )

    private fun filmicOf(p: DoubleArray) = ProfileLooks.Filmic(p[0], exp(p[7]), exp(p[8]), exp(p[9]), p[10])

    private fun predictStandard(p: DoubleArray, s: Sample): DoubleArray {
        val (y, m) = ModelMaths.filmic(filmicOf(p))
        return predictStandard(p, y, m, s)
    }

    private fun predictStandard(p: DoubleArray, y: DoubleArray, m: DoubleArray, s: Sample): DoubleArray {
        val g = StrictMath.pow(2.0, p[0])
        val mat = matrix(p, 1)
        val x = s.reveal
        val v = DoubleArray(3) { r ->
            ModelMaths.curve(g * (mat[r * 3] * x[0] + mat[r * 3 + 1] * x[1] + mat[r * 3 + 2] * x[2]), y, m)
        }
        return ColourDifference.lab(ModelMaths.toSrgb(v))
    }

    private fun fitStandard(train: List<Sample>): DoubleArray {
        val p0 = doubleArrayOf(1.4, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, ln(1.13), ln(5.0), ln(2.45), 0.08)
        return LevenbergMarquardt.minimise(p0, MAX_ITERATIONS) { p ->
            val (y, m) = ModelMaths.filmic(filmicOf(p))
            LevenbergMarquardt.parallel(train.size) {
                ColourDifference.deltaE2000(predictStandard(p, y, m, train[it]), train[it].jpegLab)
            }
        }
    }

    // ------------------------------------------------------------------ Camera match: gain + matrix + spline (sRGB) + hue

    private val knots = ToneCurve.KNOTS
    private val hueOffset = 8 + knots - 1

    private fun curveOf(p: DoubleArray): DoubleArray {
        var z = p[7]
        return DoubleArray(knots) { i ->
            if (i > 0) z += exp(p[7 + i])
            ModelMaths.sigmoid(z)
        }
    }

    private fun matrixOf(p: DoubleArray) = matrix(p, 1)
    private fun chromaOf(p: DoubleArray) = DoubleArray(HueTreatment.COEFFICIENTS) { p[hueOffset + it] }
    private fun shiftOf(p: DoubleArray) = DoubleArray(HueTreatment.COEFFICIENTS) {
        p[
            hueOffset +
                HueTreatment.COEFFICIENTS +
                it,
        ]
    }

    private fun predictCamera(p: DoubleArray, s: Sample): DoubleArray {
        val y = curveOf(p)
        return predictCamera(p, y, ModelMaths.tangents(y), chromaOf(p), shiftOf(p), s)
    }

    @Suppress("LongParameterList")
    private fun predictCamera(
        p: DoubleArray,
        y: DoubleArray,
        m: DoubleArray,
        chroma: DoubleArray,
        shift: DoubleArray,
        s: Sample,
    ): DoubleArray {
        val g = StrictMath.pow(2.0, p[0])
        val mat = matrixOf(p)
        val x = s.reveal
        val v = DoubleArray(3) { r -> g * (mat[r * 3] * x[0] + mat[r * 3 + 1] * x[1] + mat[r * 3 + 2] * x[2]) }
        val inCurve = ModelMaths.r2s.times(v)
        val curved = DoubleArray(3) { ModelMaths.curve(inCurve[it], y, m) }
        val back = ModelMaths.hue(ModelMaths.s2r.times(curved), chroma, shift)
        return ColourDifference.lab(ModelMaths.toSrgb(back))
    }

    private fun fitCamera(train: List<Sample>, std: DoubleArray): DoubleArray {
        // Start from the parametric fit: its knots (as logits), its gain and matrix, no hue treatment.
        val (ys, _) = ModelMaths.filmic(filmicOf(std))
        val z = DoubleArray(knots) {
            val v = ys[it].coerceIn(1e-6, 0.9999)
            ln(v / (1 - v))
        }
        val p0 = DoubleArray(hueOffset + 2 * HueTreatment.COEFFICIENTS)
        for (i in 0..6) p0[i] = std[i]
        p0[7] = z[0]
        for (i in 1 until knots) p0[7 + i] = ln((z[i] - z[i - 1]).coerceAtLeast(1e-3))
        val weight = sqrt(train.size.toDouble()) / 10
        return LevenbergMarquardt.minimise(p0, MAX_ITERATIONS) { p ->
            val y = curveOf(p)
            val m = ModelMaths.tangents(y)
            val chroma = chromaOf(p)
            val shift = shiftOf(p)
            val errors = LevenbergMarquardt.parallel(train.size) {
                ColourDifference.deltaE2000(predictCamera(p, y, m, chroma, shift, train[it]), train[it].jpegLab)
            }
            val grey = ModelMaths.curve(ProfileLooks.GREY, y, m)
            val logits = DoubleArray(knots) { ln(y[it] / (1 - y[it])) }
            val reg = ArrayList<Double>()
            reg += GREY_WEIGHT * (grey - ProfileLooks.GREY) * weight
            for (i in 1 until knots - 1) reg += SMOOTH_WEIGHT * (logits[i + 1] - 2 * logits[i] + logits[i - 1]) * weight
            for (i in 1 until HueTreatment.COEFFICIENTS) reg += RIDGE_WEIGHT * chroma[i] * weight
            for (i in 0 until HueTreatment.COEFFICIENTS) reg += RIDGE_WEIGHT * shift[i] * weight
            errors + reg.toDoubleArray()
        }
    }

    // ------------------------------------------------------------------ measurement

    private fun realOps(params: ProfileParams, reveal: DoubleArray): DoubleArray {
        val rgb = FloatArray(3) { reveal[it].toFloat() }
        ProfileOp.apply(rgb, params)
        return ColourDifference.lab(ModelMaths.toSrgb(DoubleArray(3) { rgb[it].toDouble() }))
    }

    private fun m0Default(reveal: DoubleArray): DoubleArray {
        val k = 0.8
        val v = DoubleArray(3) { c ->
            val x = reveal[c]
            if (x <= k) {
                x
            } else {
                val u = (minOf(x, 1e4) - k) / (1 - k)
                k + (1 - k) * u / StrictMath.pow(1 + StrictMath.pow(u, 8.0), 1.0 / 8)
            }
        }
        return ColourDifference.lab(ModelMaths.toSrgb(v))
    }

    private fun report(name: String, set: List<Sample>, predict: (Sample) -> DoubleArray) {
        val e = LevenbergMarquardt.parallel(set.size) { ColourDifference.deltaE2000(predict(set[it]), set[it].jpegLab) }
        val sorted = e.sorted()
        val perPair = set.indices.groupBy { set[it].pair }.values.map { idx -> idx.sumOf { e[it] } / idx.size }
        val lightness = set.indices.map { predict(set[it])[0] - set[it].jpegLab[0] }.average()
        println(
            "CALIB $name: pairs=${perPair.size} patches=${set.size} mean=${f(
                e.average(),
            )} median=${f(sorted[sorted.size / 2])} " +
                "p95=${f(
                    sorted[(sorted.size * 95) / 100],
                )} per-pair-mean=${f(perPair.min())}..${f(perPair.max())} meanDeltaL=${f(lightness)}",
        )
    }

    private fun printConstants(std: DoubleArray, cam: DoubleArray) {
        val fil = filmicOf(std)
        println(
            "CALIB-CONST STANDARD gainEv=${g(
                fil.gainEv,
            )} contrast=${g(
                fil.contrast,
            )} shoulder=${g(fil.shoulder)} whiteStops=${g(fil.whiteStops)} toe=${g(fil.toe)}",
        )
        println("CALIB-CONST STANDARD-MATRIX ${matrix(std, 1).joinToString(", ") { g(it) }}")
        println("CALIB-CONST GAIN_EV ${g(cam[0])}")
        println("CALIB-CONST MATRIX ${matrixOf(cam).joinToString(", ") { g(it) }}")
        println("CALIB-CONST CURVE ${curveOf(cam).joinToString(", ") { g(it) }}")
        println("CALIB-CONST HUE_CHROMA ${chromaOf(cam).joinToString(", ") { g(it) }}")
        println("CALIB-CONST HUE_SHIFT ${shiftOf(cam).joinToString(", ") { g(it) }}")
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    private fun g(v: Double) = String.format(Locale.ROOT, "%.6g", v)

    private companion object {
        const val PER_PAIR = 1500
        const val SESSION_SPLIT = 17
        const val MAX_ITERATIONS = 200
        const val GREY_WEIGHT = 30.0
        const val SMOOTH_WEIGHT = 0.3
        const val RIDGE_WEIGHT = 2.0
    }
}
