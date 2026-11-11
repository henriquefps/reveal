// Device harness: loops over sample files and algorithms, an explicit GC before measuring memory.
@file:Suppress("NestedBlockDepth", "LongMethod", "ExplicitGarbageCollectionCall")

package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.preview.FirstImageLoader
import dev.hfps.image.preview.FirstImageSource
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.proxy.ProxySource
import org.junit.Test

/** Embedded previews and proxies from the public sample RAWs. */
class ProxyHarnessTest {
    private val rawExtensions = arrayOf("arw", "dng", "nef", "cr3", "cr2", "raf", "orf", "rw2")

    @Test
    fun firstImageFromTheEmbeddedPreview() {
        for ((index, file) in Harness.samples(*rawExtensions).withIndex()) {
            val name = Harness.label(file, index)
            ImageInput.fromFile(file).use { raw ->
                val first = FirstImageLoader.load(raw, null, 1600)
                Harness.log(
                    "FIRST $name: ${first.source} ${first.bitmap.width}x${first.bitmap.height} " +
                        "${first.readBytes} bytes of ${file.length()} in ${first.millis}ms",
                )
                assertThat(first.source).isEqualTo(FirstImageSource.EMBEDDED_PREVIEW)
                assertThat(first.readBytes).isLessThan(file.length() / 5)
                first.bitmap.recycle()
            }
        }
    }

    @Test
    fun proxiesAtSeveralSizes() {
        for ((index, file) in Harness.samples(*rawExtensions).withIndex()) {
            val name = Harness.label(file, index)
            ImageInput.fromFile(file).use { input ->
                for (longEdge in intArrayOf(1024, 2048, 4096)) {
                    val proxy = ProxyBuilder.decodeProxy(input, longEdge)
                    val stats = proxy.stats
                    Harness.log(
                        "PROXY $name @$longEdge: ${proxy.image.width}x${proxy.image.height} ${stats.source} " +
                            "decoded=${stats.decodedWidth}x${stats.decodedHeight} decode=${stats.decodeMs}ms " +
                            "resample=${stats.resampleMs}ms total=${stats.totalMs}ms",
                    )
                    assertThat(
                        maxOf(proxy.image.width, proxy.image.height),
                    ).isEqualTo(minOf(longEdge, maxOf(stats.decodedWidth, stats.decodedHeight)))
                    val area = proxy.info.let { (it as dev.hfps.image.color.RawColorInfo).activeArea }
                    val halfLong = (maxOf(area.width, area.height) + 1) / 2
                    val expected = if (halfLong >= longEdge) ProxySource.RAW_HALF else ProxySource.RAW_FULL
                    assertThat(stats.source).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun jpegProxyUsesSubsampling() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(context.targetContext.cacheDir, "ramps.jpg")
        context.context.assets.open("fixtures/ramps-srgb.jpg").use { i -> file.outputStream().use { i.copyTo(it) } }
        ImageInput.fromFile(file).use { input ->
            val proxy = ProxyBuilder.decodeProxy(input, 100)
            assertThat(proxy.stats.source).isEqualTo(ProxySource.STANDARD)
            assertThat(proxy.image.width).isEqualTo(100)
        }
    }

    @Test
    fun nativeDownscaleMatchesTheReference() {
        val random = java.util.Random(7)
        for (sizes in listOf(listOf(1000, 700, 333, 233), listOf(7, 5, 3, 2), listOf(640, 480, 640, 100))) {
            val (w, h) = sizes
            val (tw, th) = sizes.drop(2)
            val data = ShortArray(w * h * 3) { random.nextInt(65536).toShort() }
            val buffer = java.nio.ByteBuffer.allocateDirect(data.size * 2).order(java.nio.ByteOrder.nativeOrder())
            buffer.asShortBuffer().put(data)
            NativeLinearImage(w, h, buffer) {}.use { native ->
                val fast = dev.hfps.image.proxy.LinearResample.downscale(native, tw, th)
                val reference = dev.hfps.image.proxy.LinearResample.downscale(LinearImage(w, h, data), tw, th)
                var worst = 0
                for (i in fast.data.indices) {
                    worst =
                        maxOf(
                            worst,
                            kotlin.math.abs((fast.data[i].toInt() and 0xFFFF) - (reference.data[i].toInt() and 0xFFFF)),
                        )
                }
                assertThat(worst).isAtMost(1)
            }
        }
    }
}
