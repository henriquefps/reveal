package dev.hfps.image

import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.color.CfaKind
import dev.hfps.image.raw.Demosaic
import dev.hfps.image.raw.RawDecodeOptions
import dev.hfps.image.raw.RawDecoder
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Decodes the public sample RAWs on a device and checks the linear output and the colour info for sanity. */
class RawHarnessTest {
    private val rawExtensions = arrayOf("arw", "dng", "nef", "cr3", "cr2", "raf", "orf", "rw2")

    @Test
    fun nativeLibraryLoads() {
        assertThat(RawDecoder.isAvailable).isTrue()
        Harness.log("LibRaw ${RawDecoder.libRawVersion}")
    }

    @Test
    fun readInfoReportsColorData() {
        for (file in Harness.samples(*rawExtensions)) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val info = RawDecoder.readInfo(fd)
                Harness.log(
                    "INFO ${file.name}: ${info.make} ${info.model} cfa=${info.cfa}/${info.cfaPattern} " +
                        "sensor=${info.sensorWidth}x${info.sensorHeight} active=${info.activeArea} " +
                        "crop=${info.defaultCrop} orient=${info.orientation} " +
                        "asShot=${info.asShotWhiteBalance} daylight=${info.daylightWhiteBalance} " +
                        "levels=${info.levels} baseline=${info.baselineExposureEv} dng=${info.isDng} iso=${info.iso}",
                )
                Harness.log("MATRIX ${file.name}: ${info.cameraToXyz}")
                assertThat(info.make).isNotNull()
                assertThat(info.activeArea.width).isGreaterThan(1000)
            }
        }
    }

    @Test
    fun fullDecodeIsLinearAndConsistent() {
        for (file in Harness.samples(*rawExtensions)) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val (result, peak) = Harness.measurePeakNativeMb {
                    RawDecoder.decodeNative(fd, RawDecodeOptions(demosaic = Demosaic.AHD))
                }
                result.image.use { image ->
                    val info = result.info
                    val stats = Statistics.of(image.samples)
                    Harness.log(
                        "FULL ${file.name}: ${image.width}x${image.height} ${result.stats} peakNative=${peak}MB " +
                            "min=${stats.min} max=${stats.max} mean=${"%.0f".format(stats.mean)} " +
                            "atMax=${stats.shareAtMax} levels=${info.levels}",
                    )
                    assertThat(image.width).isEqualTo(info.activeArea.width)
                    assertThat(image.height).isEqualTo(info.activeArea.height)
                    assertThat(stats.max).isGreaterThan(20000)
                    assertThat(stats.mean).isGreaterThan(100.0)
                    assertThat(info.levels.black.all { it < info.levels.white }).isTrue()
                    val matrix = info.cameraToXyz
                    assumeTrue("no matrix for ${info.model}", matrix != null)
                    // The daylight white (1/pre_mul) must map to D65 white: X/Y = 0.9505, Z/Y = 1.089.
                    val daylight = info.daylightWhiteBalance
                    if (daylight != null && matrix != null && info.cfa != CfaKind.XTRANS) {
                        val (x, y, z) = matrix.apply(1.0 / daylight.red, 1.0, 1.0 / daylight.blue)
                        assertThat(x / y).isWithin(0.01).of(0.9505)
                        assertThat(z / y).isWithin(0.01).of(1.089)
                    }
                }
            }
        }
    }

    @Test
    fun halfSizeIsAboutAQuarterOfThePixels() {
        for (file in Harness.samples(*rawExtensions)) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val result = RawDecoder.decode(fd, RawDecodeOptions(halfSize = true))
                val area = result.info.activeArea
                Harness.log(
                    "HALF ${file.name}: ${result.image.width}x${result.image.height} (active ${area.width}x${area.height}) ${result.stats}",
                )
                if (result.info.cfa == CfaKind.BAYER) {
                    assertThat(result.image.width).isAtMost((area.width + 1) / 2 + 1)
                }
            }
        }
    }

    /** Sums over a LinearImage, to compare decodes without keeping pixels. */
    private object Statistics {
        class Stats(val min: Int, val max: Int, val mean: Double, val shareAtMax: Double)

        fun of(samples: java.nio.ShortBuffer): Stats {
            var min = 65535
            var max = 0
            var sum = 0L
            var atMax = 0L
            val count = samples.remaining()
            while (samples.hasRemaining()) {
                val x = samples.get().toInt() and 0xFFFF
                if (x < min) min = x
                if (x > max) max = x
                if (x == 65535) atMax++
                sum += x
            }
            return Stats(min, max, sum.toDouble() / count, atMax.toDouble() / count)
        }
    }
}
