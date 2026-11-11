// Device harness: loops over sample files and algorithms, an explicit GC before measuring memory.
@file:Suppress("NestedBlockDepth", "LongMethod", "ExplicitGarbageCollectionCall")

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
        for ((index, file) in Harness.samples(*rawExtensions).withIndex()) {
            val name = Harness.label(file, index)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val info = RawDecoder.readInfo(fd)
                Harness.log(
                    "INFO $name: ${info.make} ${info.model} cfa=${info.cfa}/${info.cfaPattern} " +
                        "sensor=${info.sensorWidth}x${info.sensorHeight} active=${info.activeArea} " +
                        "crop=${info.defaultCrop} orient=${info.orientation} " +
                        "asShot=${info.asShotWhiteBalance} daylight=${info.daylightWhiteBalance} " +
                        "levels=${info.levels} baseline=${info.baselineExposureEv} dng=${info.isDng} iso=${info.iso}",
                )
                Harness.log("MATRIX $name: ${info.cameraToXyz}")
                assertThat(info.make).isNotNull()
                if (file.name.startsWith("sony-zv-e10")) {
                    // Values read from the public sample (a ZV-E10 ARW): knot count first, then the knots.
                    val lens = info.lensCorrection
                    assertThat(lens?.vignetting?.take(4)).containsExactly(11, 0, 0, 128).inOrder()
                    assertThat(lens?.distortion?.take(4)).containsExactly(11, 21, 2, -22).inOrder()
                    assertThat(lens?.chromaticAberration?.take(3)).containsExactly(22, -512, -512).inOrder()
                    assertThat(info.levels.black).containsExactly(512, 512, 512, 512)
                    assertThat(info.levels.white).isEqualTo(16383)
                    assertThat(info.activeArea.width).isEqualTo(6024)
                }
                assertThat(info.activeArea.width).isGreaterThan(1000)
            }
        }
    }

    @Test
    fun fullDecodeIsLinearAndConsistent() {
        for ((index, file) in Harness.samples(*rawExtensions).withIndex()) {
            val name = Harness.label(file, index)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val (result, peak) = Harness.measurePeakNativeMb {
                    RawDecoder.decodeNative(fd, RawDecodeOptions(demosaic = Demosaic.AHD))
                }
                result.image.use { image ->
                    val info = result.info
                    val stats = Statistics.of(image.samples)
                    Harness.log(
                        "FULL $name: ${image.width}x${image.height} ${result.stats} peakNative=${peak}MB " +
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
        for ((index, file) in Harness.samples(*rawExtensions).withIndex()) {
            val name = Harness.label(file, index)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val result = RawDecoder.decode(fd, RawDecodeOptions(halfSize = true))
                val area = result.info.activeArea
                val label = Harness.label(file, index)
                Harness.log(
                    "HALF $label: ${result.image.width}x${result.image.height} " +
                        "(active ${area.width}x${area.height}) ${result.stats}",
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

    /**
     * A descriptor served by a `ProxyFileDescriptorCallback` is what a DocumentsProvider (SAF) hands to apps for
     * non-local storage: reads arrive at the callback with an offset. The decode must work on it and match a direct
     * decode of the same file.
     */
    @Test
    fun decodesFromAProxyDescriptorLikeASafDocument() {
        val file = Harness.samples("arw").firstOrNull() ?: Harness.samples(*rawExtensions).first()
        val reads = java.util.concurrent.atomic.AtomicLong()
        val random = java.io.RandomAccessFile(file, "r")
        val callback = object : android.os.ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = random.length()

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                reads.incrementAndGet()
                random.seek(offset)
                return random.read(data, 0, size)
            }

            override fun onRelease() = random.close()
        }
        val thread = android.os.HandlerThread("proxy-fd").also { it.start() }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
        val proxy = storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            callback,
            android.os.Handler(thread.looper),
        )
        try {
            val viaProxy = RawDecoder.decode(proxy, RawDecodeOptions(halfSize = true))
            val direct = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                RawDecoder.decode(it, RawDecodeOptions(halfSize = true))
            }
            Harness.log("PROXYFD decode ${viaProxy.stats} reads=${reads.get()}")
            assertThat(viaProxy.image.data).isEqualTo(direct.image.data)
            assertThat(viaProxy.info.levels).isEqualTo(direct.info.levels)
            assertThat(reads.get()).isGreaterThan(0)
        } finally {
            proxy.close()
            thread.quitSafely()
        }
    }
}
