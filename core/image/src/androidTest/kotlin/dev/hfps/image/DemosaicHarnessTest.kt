package dev.hfps.image

import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.raw.Demosaic
import dev.hfps.image.raw.RawDecodeOptions
import dev.hfps.image.raw.RawDecoder
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Test

/**
 * Times every demosaic algorithm on the sample RAWs and scores each against LibRaw's half-size decode (2x2 binning, which
 * has no interpolation): the full-size result averaged down 2x2 should match it, and the error is the interpolation
 * error. Also reports detail (mean absolute Laplacian of green) and colour fringing (high-pass of R-G).
 *
 * Names are anonymised with `-e anonymize true` (for the opt-in run on private photos); only aggregates are logged.
 */
class DemosaicHarnessTest {
    private val rawExtensions = arrayOf("arw", "dng", "nef", "cr3", "cr2", "raf", "orf", "rw2")

    private class Scores(val rmse: Double, val detail: Double, val fringing: Double)

    private fun format(scores: Scores?): String {
        if (scores == null) return ""
        return " rmse=${"%.1f".format(scores.rmse)} detail=${"%.1f".format(scores.detail)} " +
            "fringing=${"%.1f".format(scores.fringing)}"
    }

    @Test
    fun compareDemosaicAlgorithms() {
        val algorithms = listOf(Demosaic.PPG, Demosaic.AHD, Demosaic.DCB, Demosaic.DHT, Demosaic.AAHD)
        for ((index, file) in Harness.samples(*rawExtensions).withIndex()) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val half = RawDecoder.decode(fd, RawDecodeOptions(halfSize = true))
                val reference = half.image
                for (algorithm in algorithms) {
                    val runs = mutableListOf<Long>()
                    var scores: Scores? = null
                    var peak = 0L
                    repeat(RUNS) { run ->
                        val (result, p) = Harness.measurePeakNativeMb {
                            RawDecoder.decodeNative(fd, RawDecodeOptions(demosaic = algorithm))
                        }
                        result.image.use { image ->
                            runs += result.stats.totalMs
                            peak = maxOf(peak, p)
                            if (run == 0 && image.width / 2 == reference.width) scores = score(image, reference)
                        }
                    }
                    val median = runs.sorted()[runs.size / 2]
                    Harness.log(
                        "DEMOSAIC ${Harness.label(file, index)} $algorithm: median=${median}ms runs=$runs " +
                            "peakNative=${peak}MB${format(scores)}",
                    )
                    assertThat(runs).isNotEmpty()
                }
            }
        }
    }

    /** Scores a full-size [image] against the binned [reference]. */
    private fun score(image: NativeLinearImage, reference: LinearImage): Scores {
        val w = image.width
        val rowValues = w * 3
        val samples = image.samples
        val rows = Array(3) { ShortArray(rowValues) }
        val reader = samples.duplicate()
        val rowAt = { y: Int, into: ShortArray ->
            reader.position(y * rowValues)
            reader.get(into, 0, rowValues)
        }
        var squared = 0.0
        var count = 0L
        var laplacian = 0.0
        var fringe = 0.0
        var fringeCount = 0L
        val hw = reference.width
        val hh = reference.height
        // Binned comparison: for each half-size pixel, average rows 2y and 2y+1.
        val even = ShortArray(rowValues)
        val odd = ShortArray(rowValues)
        for (y in 0 until hh) {
            rowAt(2 * y, even)
            rowAt(2 * y + 1, odd)
            for (x in 0 until hw) {
                for (c in 0..2) {
                    val i = 6 * x + c
                    val binned =
                        (
                            (even[i].toInt() and 0xFFFF) + (even[i + 3].toInt() and 0xFFFF) +
                                (odd[i].toInt() and 0xFFFF) +
                                (odd[i + 3].toInt() and 0xFFFF)
                            ) /
                            4.0
                    val ref = reference.sample(x, y, c).toDouble()
                    if (ref < SATURATION_GUARD && binned < SATURATION_GUARD) {
                        squared += (binned - ref) * (binned - ref)
                        count++
                    }
                }
            }
        }
        // Detail and fringing on three consecutive rows at a time (rows around the middle third of the image).
        val start = image.height / 3
        val end = start + image.height / 3
        for (y in start until end - 1 step 2) {
            rowAt(y - 1, rows[0])
            rowAt(y, rows[1])
            rowAt(y + 1, rows[2])
            for (x in 1 until w - 1) {
                val i = 3 * x
                val g = (rows[1][i + 1].toInt() and 0xFFFF)
                val lap = 4 * g - (rows[0][i + 1].toInt() and 0xFFFF) - (rows[2][i + 1].toInt() and 0xFFFF) -
                    (rows[1][i - 2].toInt() and 0xFFFF) - (rows[1][i + 4].toInt() and 0xFFFF)
                laplacian += abs(lap)
                val d = (rows[1][i].toInt() and 0xFFFF) - g
                val around = (
                    ((rows[1][i - 3].toInt() and 0xFFFF) - (rows[1][i - 2].toInt() and 0xFFFF)) +
                        ((rows[1][i + 3].toInt() and 0xFFFF) - (rows[1][i + 4].toInt() and 0xFFFF)) +
                        ((rows[0][i].toInt() and 0xFFFF) - (rows[0][i + 1].toInt() and 0xFFFF)) +
                        ((rows[2][i].toInt() and 0xFFFF) - (rows[2][i + 1].toInt() and 0xFFFF))
                    ) / 4.0
                fringe += abs(d - around)
                fringeCount++
            }
        }
        return Scores(sqrt(squared / count), laplacian / fringeCount, fringe / fringeCount)
    }

    private companion object {
        const val RUNS = 3
        const val SATURATION_GUARD = 60000.0
    }
}
