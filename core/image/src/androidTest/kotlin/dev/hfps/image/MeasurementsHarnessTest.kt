// Device harness: loops over sample files and algorithms, an explicit GC before measuring memory.
@file:Suppress("NestedBlockDepth", "LongMethod", "ExplicitGarbageCollectionCall")

package dev.hfps.image

import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.preview.FirstImageLoader
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.raw.Demosaic
import dev.hfps.image.raw.RawDecodeOptions
import dev.hfps.image.raw.RawDecoder
import org.junit.Test

/**
 * The numbers of ADR 0004: time to the embedded preview, header read, half-size and full linear decode, proxies and the
 * peak native memory, per file. With `-e anonymize true` only `file#N` is logged (private photos).
 */
class MeasurementsHarnessTest {
    private val rawExtensions = arrayOf("arw", "dng", "nef", "cr3", "cr2", "raf", "orf", "rw2")

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private inline fun timed(runs: Int, block: () -> Unit): List<Long> = List(runs) {
        val start = System.nanoTime()
        block()
        (System.nanoTime() - start) / NANOS_PER_MILLI
    }

    @Test
    fun measure() {
        for ((index, file) in Harness.samples(*rawExtensions).withIndex()) {
            val name = Harness.label(file, index)
            ImageInput.fromFile(file).use { input ->
                // Cold-ish first numbers, then medians over warm runs.
                val firstBytes = FirstImageLoader.load(input, null, 1600).also { it.bitmap.recycle() }.readBytes
                val preview = median(timed(5) { FirstImageLoader.load(input, null, 1600).bitmap.recycle() })
                val header = median(timed(5) { RawDecoder.readInfo(input.descriptor) })
                val halfRuns = timed(3) { RawDecoder.decode(input.descriptor, RawDecodeOptions(halfSize = true)) }
                val (full, peak) = Harness.measurePeakNativeMb {
                    RawDecoder.decodeNative(input.descriptor, RawDecodeOptions(demosaic = Demosaic.AHD))
                }
                val fullStages = full.stats.stageMs
                full.image.close()
                val fullRuns = timed(3) { RawDecoder.decodeNative(input.descriptor).image.close() }
                val proxy1600 = ProxyBuilder.decodeProxy(input, 1600).stats
                val proxy3000 = ProxyBuilder.decodeProxy(input, 3000).stats
                Harness.log(
                    "MEASURE $name (${file.length() / MB} MB): previewBytes=$firstBytes previewMs=$preview " +
                        "headerMs=$header halfMs=${median(halfRuns)} fullAhdMs=${median(fullRuns)} " +
                        "fullStagesMs=$fullStages peakNativeMb=$peak " +
                        "proxy1600=${proxy1600.source}/${proxy1600.totalMs}ms(decode ${proxy1600.decodeMs}, " +
                        "resample ${proxy1600.resampleMs}) proxy3000=${proxy3000.source}/${proxy3000.totalMs}ms",
                )
                assertThat(preview).isLessThan(2000)
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MB = 1024L * 1024L
    }
}
