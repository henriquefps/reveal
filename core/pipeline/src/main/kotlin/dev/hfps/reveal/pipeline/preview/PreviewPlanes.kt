@file:Suppress("MagicNumber", "NestedBlockDepth", "ReturnCount")

package dev.hfps.reveal.pipeline.preview

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val LN2 = ln(2.0)
private const val FLOOR = 1.0 / 65536.0

/** log2 of a sample as a non-negative finite value (NaN, never expected, is 0), with the 2^-16 floor of v1. */
internal fun log2Sample(v: Float): Double {
    val clean = if (v > 0f) minOf(v.toDouble(), Float.MAX_VALUE.toDouble()) else 0.0
    return ln(clean + FLOOR) / LN2
}

internal fun linearOf(log2: Double): Float = (exp(log2 * LN2) - FLOOR).toFloat().coerceAtLeast(0f)

/**
 * Version 1 planes (0.5.0, 0.5.1): green as 16-bit log codes over 20 stops, chroma (`log2 c - log2 g`, 2 x 2 means)
 * as 12-bit codes over 16 stops, each row predicted from its left neighbour, u16 little-endian, one deflate stream.
 * Read for the previews that exist; written only by tests (to prove they still open).
 */
internal object PlanesV1 {
    private const val Y_STOPS = 20.0
    private const val Y_LOW = -16.0
    private const val Y_MAX = 65535.0
    private const val C_STOPS = 16.0
    private const val C_MAX = 4095.0

    private fun lumaLog(code: Int): Double = code * Y_STOPS / Y_MAX + Y_LOW

    fun write(px: FloatArray, w: Int, h: Int, out: OutputStream) {
        val row = ByteArray(w * 2)
        val luma = IntArray(w * h)
        for (y in 0 until h) {
            var prev = 0
            for (x in 0 until w) {
                val i = y * w + x
                val code = ((log2Sample(px[i * 3 + 1]) - Y_LOW) * Y_MAX / Y_STOPS).roundToInt()
                    .coerceIn(0, Y_MAX.toInt())
                luma[i] = code
                putU16(row, x, code - prev)
                prev = code
            }
            out.write(row, 0, w * 2)
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val crow = ByteArray(cw * 2)
        for (channel in intArrayOf(0, 2)) {
            for (cy in 0 until ch) {
                var prev = 0
                for (cx in 0 until cw) {
                    var sum = 0.0
                    var n = 0
                    forBlock(cx, cy, w, h) { i ->
                        sum += log2Sample(px[i * 3 + channel]) - lumaLog(luma[i])
                        n++
                    }
                    val code = ((sum / n + C_STOPS / 2) * C_MAX / C_STOPS).roundToInt().coerceIn(0, C_MAX.toInt())
                    putU16(crow, cx, code - prev)
                    prev = code
                }
                out.write(crow, 0, cw * 2)
            }
        }
    }

    fun read(w: Int, h: Int, input: InputStream): FloatArray {
        val data = DataInputStream(input)
        val out = FloatArray(w * h * 3)
        val lumaLog = FloatArray(w * h)
        val row = ByteArray(w * 2)
        for (y in 0 until h) {
            data.readFully(row)
            var code = 0
            for (x in 0 until w) {
                code = (code + getU16(row, x)) and 0xFFFF
                val i = y * w + x
                out[i * 3 + 1] = linearOf(lumaLog(code))
                lumaLog[i] = lumaLog(code).toFloat()
            }
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val crow = ByteArray(cw * 2)
        for (channel in intArrayOf(0, 2)) {
            for (cy in 0 until ch) {
                data.readFully(crow)
                var code = 0
                for (cx in 0 until cw) {
                    code = (code + getU16(crow, cx)) and 0xFFFF
                    val diff = code * C_STOPS / C_MAX - C_STOPS / 2
                    forBlock(cx, cy, w, h) { i -> out[i * 3 + channel] = linearOf(lumaLog[i] + diff) }
                }
            }
        }
        return out
    }
}

/**
 * The quantisers of a version 2 preview (ADR 0018 "Smart previews", FIX/P): steps in log2 that follow the photo's own
 * noise, so the rounding stays a fraction of the grain the photo already has (and below visibility where it is
 * clean). Levels are bins of [BIN] stop from [LOW] to [HIGH]; bin `i` of the green plane holds [luma]`[i]` codes
 * (a step of `BIN / luma[i]` stops), a chroma block whose green sits in bin `i` is quantised to `1 / chroma[i]`
 * stops. Both tables travel in the header, so the decoder needs nothing else.
 */
internal class Quantisers(val luma: IntArray, val chroma: IntArray) {
    private val starts = IntArray(BINS + 1)
    private val binOfCode: IntArray

    init {
        if (luma.size != BINS || chroma.size != BINS) throw IOException("bad quantiser tables")
        if (luma.any { it !in 1..MAX_LUMA_PER_BIN } || chroma.any { it !in 1..MAX_CHROMA_PER_STOP }) {
            throw IOException("bad quantiser tables")
        }
        for (i in 0 until BINS) starts[i + 1] = starts[i] + luma[i]
        if (starts[BINS] > MAX_CODES) throw IOException("bad quantiser tables")
        binOfCode = IntArray(starts[BINS])
        for (i in 0 until BINS) for (c in starts[i] until starts[i + 1]) binOfCode[c] = i
    }

    val codes: Int get() = starts[BINS]

    /** The green code of a log2 value. */
    fun lumaCode(log2: Double): Int {
        val t = ((log2 - LOW) / BIN).coerceIn(0.0, BINS - 1e-9)
        val bin = t.toInt()
        val code = starts[bin] + floor((t - bin) * luma[bin]).toInt()
        return code.coerceIn(starts[bin], starts[bin + 1] - 1)
    }

    /** The log2 value of a green code: the middle of its step. */
    fun lumaLog(code: Int): Double {
        val bin = binOfCode[code]
        return LOW + (bin + (code - starts[bin] + 0.5) / luma[bin]) * BIN
    }

    /** The level bin of a 2 x 2 block from its green codes' integer mean (the same on every device). */
    fun blockBin(codeSum: Int, n: Int): Int = binOfCode[codeSum / n]

    companion object {
        const val LOW = -16.0
        const val HIGH = 4.0
        const val BIN = 0.125
        val BINS = ((HIGH - LOW) / BIN).roundToInt()
        const val MAX_CODES = 65536
        const val MAX_LUMA_PER_BIN = 400
        const val MAX_CHROMA_PER_STOP = 1024
    }
}

/**
 * Version 2 planes. Green codes ([Quantisers.lumaCode]) at full resolution; chroma (`log2 c - log2 g` of the decoded
 * green, 2 x 2 means) rounded to its block's step, signed. Each plane is predicted with the median edge detector of
 * LOCO-I (`a`, `b`, `c` = left, above, above left), the residual wraps to 16 bits, zigzags, and its low and high
 * bytes go out as two separate runs (the high bytes are mostly zero) inside one deflate stream.
 */
internal object PlanesV2 {
    fun write(px: FloatArray, w: Int, h: Int, q: Quantisers, out: OutputStream, onPlane: () -> Unit = {}) {
        val luma = IntArray(w * h)
        for (i in 0 until w * h) luma[i] = q.lumaCode(log2Sample(px[i * 3 + 1]))
        writePlane(luma, w, h, out)
        onPlane()
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        for (channel in intArrayOf(0, 2)) {
            val plane = IntArray(cw * ch)
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    var sum = 0.0
                    var codes = 0
                    var n = 0
                    forBlock(cx, cy, w, h) { i ->
                        sum += log2Sample(px[i * 3 + channel]) - q.lumaLog(luma[i])
                        codes += luma[i]
                        n++
                    }
                    val perStop = q.chroma[q.blockBin(codes, n)]
                    val limit = CHROMA_RANGE * perStop
                    // Stored with an offset: the predictor must see the same non-negative values on both sides.
                    plane[cy * cw + cx] = CHROMA_OFFSET + (sum / n * perStop).roundToInt().coerceIn(-limit, limit)
                }
            }
            writePlane(plane, cw, ch, out)
            onPlane()
        }
    }

    fun read(w: Int, h: Int, q: Quantisers, input: InputStream): FloatArray {
        val data = DataInputStream(input)
        val luma = readPlane(w, h, data)
        val out = FloatArray(w * h * 3)
        val lumaLog = DoubleArray(w * h)
        for (i in 0 until w * h) {
            if (luma[i] !in 0 until q.codes) throw IOException("bad green code")
            lumaLog[i] = q.lumaLog(luma[i])
            out[i * 3 + 1] = linearOf(lumaLog[i])
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        for (channel in intArrayOf(0, 2)) {
            val plane = readPlane(cw, ch, data)
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    var codes = 0
                    var n = 0
                    forBlock(cx, cy, w, h) { i ->
                        codes += luma[i]
                        n++
                    }
                    val diff = (plane[cy * cw + cx] - CHROMA_OFFSET).toDouble() / q.chroma[q.blockBin(codes, n)]
                    forBlock(cx, cy, w, h) { i -> out[i * 3 + channel] = linearOf(lumaLog[i] + diff) }
                }
            }
        }
        return out
    }

    private const val CHROMA_RANGE = 8
    private const val CHROMA_OFFSET = CHROMA_RANGE * Quantisers.MAX_CHROMA_PER_STOP

    private fun signed16(v: Int): Int = (v shl 16) shr 16

    private fun predict(v: IntArray, w: Int, x: Int, y: Int): Int {
        val i = y * w + x
        if (y == 0) return if (x > 0) v[i - 1] else 0
        if (x == 0) return v[i - w]
        val a = v[i - 1]
        val b = v[i - w]
        val c = v[i - w - 1]
        return when {
            c >= maxOf(a, b) -> minOf(a, b)
            c <= minOf(a, b) -> maxOf(a, b)
            else -> a + b - c
        }
    }

    private fun writePlane(v: IntArray, w: Int, h: Int, out: OutputStream) {
        val n = w * h
        val bytes = ByteArray(n * 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val r = signed16(v[i] - predict(v, w, x, y))
                val z = (r shl 1) xor (r shr 31)
                bytes[i] = z.toByte()
                bytes[n + i] = (z shr 8).toByte()
            }
        }
        out.write(bytes)
    }

    private fun readPlane(w: Int, h: Int, data: DataInputStream): IntArray {
        val n = w * h
        val bytes = ByteArray(n * 2)
        data.readFully(bytes)
        val v = IntArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val z = (bytes[i].toInt() and 0xFF) or ((bytes[n + i].toInt() and 0xFF) shl 8)
                val r = (z ushr 1) xor -(z and 1)
                v[i] = (predict(v, w, x, y) + r) and 0xFFFF
            }
        }
        return v
    }
}

/**
 * The step sizes of a version 2 preview: green rounds to [luma] times the measured relative noise, chroma to [chroma]
 * times it, each clamped (stops). [DEFAULT] is what the app writes (ADR 0018 amendment FIX/P, measurements there).
 */
data class PreviewSteps(
    val luma: Double = 1.0,
    val chroma: Double = 1.0,
    val minLumaStops: Double = 0.0004,
    val maxLumaStops: Double = 0.02,
    val minChromaStops: Double = 0.004,
    val maxChromaStops: Double = 0.015,
) {
    companion object {
        val DEFAULT = PreviewSteps()
    }
}

/**
 * Chooses a version 2 preview's [Quantisers] from the photo itself (encoder only; the tables are stored). The noise
 * of the green plane is measured per [NOISE_BIN]-stop level: for every pixel the difference to the mean of its four
 * neighbours, relative to that mean; the lower quartile of those per level, scaled to a Gaussian's standard deviation,
 * is the level's relative noise (texture raises the upper quantiles, flat areas set the lower ones). A level without
 * enough samples takes the next brighter level's estimate, and the estimate never rises with brightness (a level is
 * at most as noisy as every darker one: a textured bright level cannot coarsen its steps). Then [PreviewSteps].
 */
internal object PreviewNoise {
    private const val NOISE_BIN = 0.5
    private const val MIN_SAMPLES = 2000
    private const val RATIO_BINS = 256
    private const val RATIO_LOW = -16.0
    private const val RATIO_HIGH = 2.0

    /** Lower quartile of |N(0, 1.25)|: a pixel minus the mean of four independent neighbours. */
    private val QUARTILE = 0.31864 * sqrt(1.25)

    fun quantisers(px: FloatArray, w: Int, h: Int, steps: PreviewSteps = PreviewSteps.DEFAULT): Quantisers {
        val relative = relativeNoise(px, w, h)
        val luma = IntArray(Quantisers.BINS)
        val chroma = IntArray(Quantisers.BINS)
        for (bin in 0 until Quantisers.BINS) {
            val level = Quantisers.LOW + (bin + 0.5) * Quantisers.BIN
            val noise = relative[noiseBin(level)]
            val stops = noise / LN2
            val lumaStops = (stops * steps.luma).coerceIn(steps.minLumaStops, steps.maxLumaStops)
            luma[bin] = ceil(Quantisers.BIN / lumaStops).toInt().coerceIn(1, Quantisers.MAX_LUMA_PER_BIN)
            val chromaStops = (stops * steps.chroma).coerceIn(steps.minChromaStops, steps.maxChromaStops)
            chroma[bin] = ceil(1.0 / chromaStops).toInt().coerceIn(1, Quantisers.MAX_CHROMA_PER_STOP)
        }
        return Quantisers(luma, chroma)
    }

    private val NOISE_BINS = ((Quantisers.HIGH - Quantisers.LOW) / NOISE_BIN).roundToInt()

    private fun noiseBin(level: Double): Int =
        ((level - Quantisers.LOW) / NOISE_BIN).toInt().coerceIn(0, NOISE_BINS - 1)

    /** Relative noise (standard deviation over value) per [NOISE_BIN]-stop level, filled and monotone (see above). */
    fun relativeNoise(px: FloatArray, w: Int, h: Int): DoubleArray {
        val histogram = Array(NOISE_BINS) { IntArray(RATIO_BINS) }
        for (y in 1 until h - 1) for (x in 1 until w - 1) sample(px, w, y * w + x, histogram)
        val noise = DoubleArray(NOISE_BINS) { lowerQuartile(histogram[it]) }
        // Unmeasured levels take the next brighter estimate (0 above the brightest: the finest steps).
        var brighter = 0.0
        for (b in NOISE_BINS - 1 downTo 0) {
            if (noise[b] <= 0.0) noise[b] = brighter else brighter = noise[b]
        }
        // Then never rising with brightness: a level is at most as noisy as every darker one.
        for (b in 1 until NOISE_BINS) if (noise[b - 1] > 0) noise[b] = minOf(noise[b], noise[b - 1])
        return noise
    }

    /** Adds pixel [i]'s relative difference to its 4-neighbour mean to its level's histogram (log2 bins). */
    private fun sample(px: FloatArray, w: Int, i: Int, histogram: Array<IntArray>) {
        val mean = (green(px, i - 1) + green(px, i + 1) + green(px, i - w) + green(px, i + w)) / 4
        if (mean <= FLOOR) return
        val level = ln(mean) / LN2
        if (level < Quantisers.LOW || level >= Quantisers.HIGH) return
        val d = kotlin.math.abs(green(px, i) - mean) / mean
        val r = if (d > 0) ln(d) / LN2 else RATIO_LOW
        val rb = ((r - RATIO_LOW) / (RATIO_HIGH - RATIO_LOW) * RATIO_BINS).toInt().coerceIn(0, RATIO_BINS - 1)
        histogram[noiseBin(level)][rb]++
    }

    /** The noise sigma a level's histogram implies (its lower quartile), 0 with too few samples. */
    private fun lowerQuartile(histogram: IntArray): Double {
        val count = histogram.sum()
        if (count < MIN_SAMPLES) return 0.0
        var seen = 0
        for (rb in 0 until RATIO_BINS) {
            seen += histogram[rb]
            if (seen > count / 4) {
                val r = RATIO_LOW + (rb + 0.5) / RATIO_BINS * (RATIO_HIGH - RATIO_LOW)
                return exp(r * LN2) / QUARTILE
            }
        }
        return 0.0
    }

    private fun green(px: FloatArray, i: Int): Double {
        val v = px[i * 3 + 1]
        return if (v > 0f) minOf(v.toDouble(), Float.MAX_VALUE.toDouble()) else 0.0
    }
}

internal inline fun forBlock(cx: Int, cy: Int, w: Int, h: Int, body: (Int) -> Unit) {
    for (dy in 0..1) {
        val y = cy * 2 + dy
        if (y >= h) continue
        for (dx in 0..1) {
            val x = cx * 2 + dx
            if (x >= w) continue
            body(y * w + x)
        }
    }
}

internal fun putU16(buffer: ByteArray, index: Int, value: Int) {
    buffer[index * 2] = (value and 0xFF).toByte()
    buffer[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
}

internal fun getU16(buffer: ByteArray, index: Int): Int =
    (buffer[index * 2].toInt() and 0xFF) or ((buffer[index * 2 + 1].toInt() and 0xFF) shl 8)
