@file:Suppress("MagicNumber", "NestedBlockDepth", "ReturnCount")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.cpu.CameraMatchZvE10
import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.Mat3
import dev.hfps.reveal.pipeline.cpu.ProfileLooks
import dev.hfps.reveal.pipeline.cpu.ToneCurve
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Colour difference and colour-space helpers in double precision for the calibration (CIE 15:2004, CIEDE2000). */
object ColourDifference {
    private val srgbToXyz = ColorSpaces.SRGB.toXyz
    private val white = ColorSpaces.D65_XYZ

    /** CIELAB (D65) of linear sRGB. */
    fun lab(rgb: DoubleArray): DoubleArray {
        val xyz = srgbToXyz.times(rgb)
        val f = DoubleArray(3) { c ->
            val t = xyz[c] / white[c]
            val d = 6.0 / 29.0
            if (t > d * d * d) StrictMath.cbrt(t) else t / (3 * d * d) + 4.0 / 29.0
        }
        return doubleArrayOf(116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2]))
    }

    /** CIEDE2000 (Sharma, Wu & Dalal 2005 implementation notes). */
    @Suppress("LongMethod")
    fun deltaE2000(l1: DoubleArray, l2: DoubleArray): Double {
        val c1 = sqrt(l1[1] * l1[1] + l1[2] * l1[2])
        val c2 = sqrt(l2[1] * l2[1] + l2[2] * l2[2])
        val cb = (c1 + c2) / 2
        val cb7 = StrictMath.pow(cb, 7.0)
        val g = 0.5 * (1 - sqrt(cb7 / (cb7 + 6103515625.0)))
        val a1 = (1 + g) * l1[1]
        val a2 = (1 + g) * l2[1]
        val c1p = sqrt(a1 * a1 + l1[2] * l1[2])
        val c2p = sqrt(a2 * a2 + l2[2] * l2[2])
        val h1 = if (c1p == 0.0) 0.0 else (Math.toDegrees(atan2(l1[2], a1)) + 360) % 360
        val h2 = if (c2p == 0.0) 0.0 else (Math.toDegrees(atan2(l2[2], a2)) + 360) % 360
        val dL = l2[0] - l1[0]
        val dC = c2p - c1p
        var dh = h2 - h1
        if (c1p * c2p == 0.0) {
            dh = 0.0
        } else if (dh > 180) {
            dh -= 360
        } else if (dh < -180) {
            dh += 360
        }
        val dH = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dh / 2))
        val lb = (l1[0] + l2[0]) / 2
        val cbp = (c1p + c2p) / 2
        val hb = when {
            c1p * c2p == 0.0 -> h1 + h2
            abs(h1 - h2) <= 180 -> (h1 + h2) / 2
            h1 + h2 < 360 -> (h1 + h2 + 360) / 2
            else -> (h1 + h2 - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hb - 30)) + 0.24 * cos(Math.toRadians(2 * hb)) +
            0.32 * cos(Math.toRadians(3 * hb + 6)) - 0.20 * cos(Math.toRadians(4 * hb - 63))
        val dTheta = 30 * exp(-((hb - 275) / 25) * ((hb - 275) / 25))
        val cbp7 = StrictMath.pow(cbp, 7.0)
        val rc = 2 * sqrt(cbp7 / (cbp7 + 6103515625.0))
        val sl = 1 + 0.015 * (lb - 50) * (lb - 50) / sqrt(20 + (lb - 50) * (lb - 50))
        val sc = 1 + 0.045 * cbp
        val sh = 1 + 0.015 * cbp * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        val a = dL / sl
        val b = dC / sc
        val c = dH / sh
        return sqrt(a * a + b * b + c * c + rt * b * c)
    }
}

/** Double-precision mirror of the profile maths (`ToneCurve`, `HueTreatment`, `OkLab`), for fitting only. */
object ModelMaths {
    private const val LN2 = 0.6931471805599453
    val r2s: Mat3 = CameraMatchZvE10.R2S
    val s2r: Mat3 = r2s.inverse()
    private val r2020ToSrgb = r2s
    private val m1 = Mat3(
        0.8189330101, 0.3618667424, -0.1288597137,
        0.0329845436, 0.9293118715, 0.0361456387,
        0.0482003018, 0.2643662691, 0.6338517070,
    ) * ColorSpaces.REC2020.toXyz
    private val m2 = Mat3(
        0.2104542553, 0.7936177850, -0.0040720468,
        1.9779984951, -2.4285922050, 0.4505937099,
        0.0259040371, 0.7827717662, -0.8086757660,
    )
    private val m1i = m1.inverse()
    private val m2i = m2.inverse()

    /** Spline evaluation, same formula as [ToneCurve.eval]. */
    fun curve(x: Double, y: DoubleArray, m: DoubleArray): Double {
        val x0 = ToneCurve.knotX(0)
        if (x <= x0) return x * y[0] / x0
        val t = StrictMath.log(minOf(x, ToneCurve.knotX(ToneCurve.KNOTS - 1))) / LN2 - ToneCurve.FIRST_STOP
        val i = minOf(kotlin.math.floor(t).toInt(), ToneCurve.KNOTS - 2)
        val f = t - i
        val f2 = f * f
        val f3 = f2 * f
        return (2 * f3 - 3 * f2 + 1) * y[i] + (f3 - 2 * f2 + f) * m[i] + (-2 * f3 + 3 * f2) * y[i + 1] +
            (f3 - f2) * m[i + 1]
    }

    /** Fritsch-Carlson tangents as [ToneCurve.monotone] (before its limiter, which a fitted curve never needs). */
    fun tangents(y: DoubleArray): DoubleArray {
        val m = DoubleArray(y.size)
        for (i in 1 until y.size - 1) {
            val a = y[i] - y[i - 1]
            val b = y[i + 1] - y[i]
            m[i] = if (a > 0 && b > 0) 2.0 / (1.0 / a + 1.0 / b) else 0.0
        }
        m[0] = y[0] * LN2
        return m
    }

    /** Knots and tangents of a [ProfileLooks.Filmic] (as [ProfileLooks.Filmic.curve], without the limiter). */
    fun filmic(f: ProfileLooks.Filmic): Pair<DoubleArray, DoubleArray> {
        val lg = StrictMath.log(ProfileLooks.GREY) / LN2
        val y = DoubleArray(ToneCurve.KNOTS) {
            ProfileLooks.GREY *
                StrictMath.pow(2.0, f.stops(ToneCurve.FIRST_STOP + it - lg))
        }
        val m = DoubleArray(ToneCurve.KNOTS) { y[it] * LN2 * f.slope(ToneCurve.FIRST_STOP + it - lg) }
        m[0] = y[0] * LN2
        m[ToneCurve.KNOTS - 1] = 0.0
        return y to m
    }

    fun hue(rgb: DoubleArray, chroma: DoubleArray, shift: DoubleArray): DoubleArray {
        val lms = m1.times(rgb)
        val lab = m2.times(DoubleArray(3) { StrictMath.cbrt(lms[it]) })
        val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        if (c <= 1e-9) return rgb
        val c1 = lab[1] / c
        val s1 = lab[2] / c
        val c2 = c1 * c1 - s1 * s1
        val s2 = 2 * c1 * s1
        val c3 = c2 * c1 - s2 * s1
        val s3 = s2 * c1 + c2 * s1
        val basis = doubleArrayOf(1.0, c1, s1, c2, s2, c3, s3)
        var gain = 1.0
        var dh = 0.0
        for (k in 0 until 7) {
            gain += chroma[k] * basis[k]
            dh += shift[k] * basis[k]
        }
        gain = max(gain, 0.0)
        val a = gain * (lab[1] * cos(dh) - lab[2] * sin(dh))
        val b = gain * (lab[1] * sin(dh) + lab[2] * cos(dh))
        val l = m2i.times(lab[0], a, b)
        return m1i.times(l[0] * l[0] * l[0], l[1] * l[1] * l[1], l[2] * l[2] * l[2])
    }

    /** Rec.2020 display-linear to clipped linear sRGB. */
    fun toSrgb(rgb: DoubleArray): DoubleArray = r2020ToSrgb.times(rgb).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()

    fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))
}

/** Levenberg-Marquardt with a forward-difference Jacobian; residuals are evaluated in parallel by the caller. */
object LevenbergMarquardt {
    fun minimise(p0: DoubleArray, maxIterations: Int, residuals: (DoubleArray) -> DoubleArray): DoubleArray {
        var p = p0.copyOf()
        var r = residuals(p)
        var cost = r.sumOf { it * it }
        var lambda = 1e-3
        val n = p.size
        repeat(maxIterations) {
            val jac = Array(n) { k ->
                val h = 1e-6 * max(1.0, abs(p[k]))
                val q = p.copyOf().also { it[k] += h }
                val rq = residuals(q)
                DoubleArray(r.size) { (rq[it] - r[it]) / h }
            }
            val jtj = Array(n) { a -> DoubleArray(n) { b -> dot(jac[a], jac[b]) } }
            val jtr = DoubleArray(n) { dot(jac[it], r) }
            var improved = false
            while (lambda < 1e10) {
                val a = Array(n) { i ->
                    DoubleArray(n) { j ->
                        jtj[i][j] +
                            if (i == j) lambda * max(jtj[i][i], 1e-12) else 0.0
                    }
                }
                val step = solve(a, DoubleArray(n) { -jtr[it] })
                val q = DoubleArray(n) { p[it] + step[it] }
                val rq = residuals(q)
                val c = rq.sumOf { it * it }
                if (c.isFinite() && c < cost) {
                    val relative = (cost - c) / cost
                    p = q
                    r = rq
                    cost = c
                    lambda = max(lambda / 3, 1e-9)
                    improved = true
                    if (relative < 1e-7) return p
                    break
                }
                lambda *= 4
            }
            if (!improved) return p
        }
        return p
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val x = b.copyOf()
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(m[it][col]) }
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp
            val t = x[col]
            x[col] = x[pivot]
            x[pivot] = t
            for (row in col + 1 until n) {
                val f = m[row][col] / m[col][col]
                for (k in col until n) m[row][k] -= f * m[col][k]
                x[row] -= f * x[col]
            }
        }
        for (row in n - 1 downTo 0) {
            var s = x[row]
            for (k in row + 1 until n) s -= m[row][k] * x[k]
            x[row] = s / m[row][row]
        }
        return x
    }

    /** Parallel map of a per-patch function. */
    fun parallel(n: Int, f: (Int) -> Double): DoubleArray {
        val out = DoubleArray(n)
        IntStream.range(0, n).parallel().forEach { out[it] = f(it) }
        return out
    }
}
