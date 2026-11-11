package dev.hfps.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.color.ColorMath
import dev.hfps.image.color.Matrix3
import dev.hfps.image.color.PixelRect
import dev.hfps.image.color.SourceColorSpace
import dev.hfps.image.standard.StandardDecodeOptions
import dev.hfps.image.standard.StandardDecoder
import java.io.File
import java.io.FileOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Standard formats (JPEG, HEIF, PNG, WebP) to linear 16-bit, on the platform decoders. Synthetic ramps only. */
class StandardHarnessTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val cache get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun fixture(name: String): File {
        val out = File(cache, name)
        context.assets.open("fixtures/$name").use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        return out
    }

    private fun decode(file: File, options: StandardDecodeOptions = StandardDecodeOptions()) =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { StandardDecoder.decode(it, options) }

    private fun expectedGray(x: Int): Double = ColorMath.srgbToLinear(x / 255.0) * 65535.0

    @Test
    fun srgbJpegIsLinearised() {
        val result = decode(fixture("ramps-srgb.jpg"))
        val image = result.image
        Harness.log(
            "JPEG sRGB ${image.width}x${image.height} ${result.info.colorSpace} sample=${result.sampleSize} ${result.decodeMs}ms",
        )
        assertThat(result.info.colorSpace).isEqualTo(SourceColorSpace.SRGB)
        assertThat(result.info.toXyz.isCloseTo(ColorMath.rgbToXyzD65(ColorMath.SRGB), 1e-4)).isTrue()
        assertThat(result.info.asShotWhiteBalance).isNull()
        // Grey ramp (top band): value = x. JPEG error is a couple of codes; compare in linear light with a tolerance.
        for (x in intArrayOf(16, 64, 128, 200, 255)) {
            val expected = expectedGray(x)
            val tolerance = 40.0 + expected * 0.03
            for (c in 0..2) assertThat(image.sample(x, 10, c).toDouble()).isWithin(tolerance).of(expected)
        }
        assertThat(image.sample(0, 10, 0)).isAtMost(80)
        // Red ramp band: only red.
        assertThat(image.sample(255, 60, 0)).isAtLeast(64000)
        assertThat(image.sample(255, 60, 1)).isAtMost(200)
    }

    @Test
    fun displayP3JpegKeepsItsPrimaries() {
        val result = decode(fixture("ramps-p3.jpg"))
        Harness.log("JPEG P3 colorSpace=${result.info.colorSpace} matrix=${result.info.toXyz}")
        assertThat(result.info.colorSpace).isEqualTo(SourceColorSpace.DISPLAY_P3)
        val p3: Matrix3 = ColorMath.rgbToXyzD65(ColorMath.DISPLAY_P3)
        assertThat(result.info.toXyz.isCloseTo(p3, 1e-4)).isTrue()
        // Grey stays grey in P3 (same transfer function): the same linear ramp as sRGB.
        assertThat(result.image.sample(128, 10, 1).toDouble()).isWithin(60.0).of(expectedGray(128))
    }

    @Test
    fun heifDecodes() {
        val result = decode(fixture("ramps-srgb.heic"))
        Harness.log("HEIC ${result.image.width}x${result.image.height} ${result.info.colorSpace} ${result.decodeMs}ms")
        assertThat(result.image.width).isEqualTo(256)
        assertThat(result.image.sample(128, 10, 1).toDouble()).isWithin(150.0).of(expectedGray(128))
    }

    @Test
    fun pngAndWebpDecode() {
        val png = decode(fixture("ramps-srgb.png"))
        assertThat(png.image.sample(128, 10, 0)).isWithin(2).of(expectedGray(128).toInt())
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val bitmap = BitmapFactory.decodeFile(fixture("ramps-srgb.png").path)
        val webp = File(cache, "ramps.webp")
        FileOutputStream(webp).use {
            assertThat(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)).isTrue()
        }
        val decoded = decode(webp)
        assertThat(decoded.image.sample(128, 10, 0)).isWithin(2).of(expectedGray(128).toInt())
    }

    @Test
    fun regionAndSubsampleDecode() {
        val file = fixture("ramps-srgb.png")
        val full = decode(file)
        val region = decode(file, StandardDecodeOptions(region = PixelRect(100, 0, 64, 48)))
        assertThat(region.image.width).isEqualTo(64)
        assertThat(region.image.height).isEqualTo(48)
        assertThat(region.sourceWidth).isEqualTo(256)
        assertThat(region.image.sample(10, 10, 0)).isEqualTo(full.image.sample(110, 10, 0))
        val sub = decode(file, StandardDecodeOptions(minLongEdge = 100))
        assertThat(sub.sampleSize).isEqualTo(2)
        assertThat(sub.image.width).isEqualTo(128)
    }
}
