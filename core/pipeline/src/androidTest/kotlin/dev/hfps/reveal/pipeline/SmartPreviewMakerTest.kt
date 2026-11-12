package dev.hfps.reveal.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import androidx.heifwriter.HeifWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The smart preview maker on every kind of file a library holds (0.5.0 logged "No smart preview for photo N:
 * IllegalArgumentException" for folder photos): JPEG-only at odd sizes, rotated, PNG, WebP, HEIC, a wide-gamut and an
 * HDR (BT.2020 HLG) image, the public CC0 RAWs in `files/samples` (RAW-only; a RAW+JPEG shot is made from its RAW) and
 * an unreadable file. Every readable one gives a preview that decodes; nothing throws an `IllegalArgumentException`.
 */
@RunWith(AndroidJUnit4::class)
class SmartPreviewMakerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "smart-preview-test").apply { mkdirs() }
    private val maker = SmartPreviewMaker(File(dir, "out"))

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun bitmap(width: Int, height: Int, space: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, space)
        Canvas(bitmap).drawPaint(
            Paint().apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    0xffd08040.toInt(),
                    0xff2050a0.toInt(),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        return bitmap
    }

    private fun write(name: String, bitmap: Bitmap, format: Bitmap.CompressFormat): File =
        File(dir, name).apply { outputStream().use { bitmap.compress(format, 90, it) } }

    /** Makes the preview of [file] and checks it decodes at the expected upright size. */
    private fun check(file: File, width: Int, height: Int) {
        val made = ImageInput.fromFile(file).use { maker.make(it) }
        val decoded = made.file.inputStream().use(SmartPreviewCodec::decode)
        Log.i(TAG, "${file.name}: ${decoded.input.width}x${decoded.input.height} ${decoded.input.kind}")
        assertThat(decoded.input.width).isEqualTo(width)
        assertThat(decoded.input.height).isEqualTo(height)
        assertThat(decoded.input.linearRgb.all { it.isFinite() }).isTrue()
        assertThat(decoded.jpeg).isNotEmpty()
    }

    @Test
    fun jpegsOfOddSizesAndOrientations() {
        check(write("one.jpg", bitmap(1, 1), Bitmap.CompressFormat.JPEG), 1, 1)
        check(write("thin.jpg", bitmap(3, 1001), Bitmap.CompressFormat.JPEG), 3, 1001)
        check(write("odd.jpg", bitmap(2561, 1707), Bitmap.CompressFormat.JPEG), 2560, 1706)
        val rotated = write("rotated.jpg", bitmap(600, 400), Bitmap.CompressFormat.JPEG)
        ExifInterface(rotated.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        check(rotated, 400, 600)
    }

    @Test
    fun pngWebpAndHeic() {
        check(write("a.png", bitmap(500, 333), Bitmap.CompressFormat.PNG), 500, 333)
        check(write("a.webp", bitmap(480, 320), Bitmap.CompressFormat.WEBP_LOSSY), 480, 320)
        val heic = File(dir, "a.heic")
        val image = bitmap(512, 384)
        val writer = runCatching {
            HeifWriter.Builder(heic.path, image.width, image.height, HeifWriter.INPUT_MODE_BITMAP).build()
        }.getOrNull()
        assumeTrue("no HEIF encoder on this device", writer != null)
        try {
            checkNotNull(writer).start()
            writer.addBitmap(image)
            writer.stop(TIMEOUT_MS)
        } finally {
            (writer as AutoCloseable).close()
        }
        check(heic, 512, 384)
    }

    /** A wide-gamut PNG, and (Android 14+) one in BT.2020 HLG, which the platform cannot decode "in its own space". */
    @Test
    fun wideGamutAndHdrImages() {
        check(
            write("p3.png", bitmap(400, 300, ColorSpace.get(ColorSpace.Named.DISPLAY_P3)), Bitmap.CompressFormat.PNG),
            400,
            300,
        )
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val hlg = ColorSpace.get(ColorSpace.Named.BT2020_HLG)
        val file = write("hlg.png", bitmap(400, 300, hlg), Bitmap.CompressFormat.PNG)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        Log.i(TAG, "hlg.png decodes in ${bounds.outColorSpace}")
        // The platform refuses its own HDR space as the preferred one: what made previews fail before.
        val refused = runCatching {
            android.graphics.BitmapFactory.decodeFile(
                file.path,
                android.graphics.BitmapFactory.Options().apply { inPreferredColorSpace = bounds.outColorSpace },
            )
        }.exceptionOrNull()
        Log.i(TAG, "decoding it in its own space: ${refused?.javaClass?.simpleName ?: "fine"}")
        check(file, 400, 300)
    }

    /** The CC0 RAWs (`scripts/fetch-samples.sh`, pushed to this test app's `files/samples`), when present. */
    @Test
    fun publicSampleRaws() {
        val samples = File(context.filesDir, "samples").listFiles().orEmpty().filter { it.isFile }
        assumeTrue("no samples in files/samples", samples.isNotEmpty())
        for (file in samples.sortedBy { it.name }) {
            val made = ImageInput.fromFile(file).use { maker.make(it) }
            val decoded = made.file.inputStream().use(SmartPreviewCodec::decode)
            Log.i(
                TAG,
                "${file.name}: ${decoded.input.width}x${decoded.input.height} lens ${decoded.input.lens?.name}, " +
                    "${made.size / 1000} KB (display JPEG ${decoded.jpeg.size / 1000} KB), format " +
                    made.file.inputStream().use(SmartPreviewCodec::version),
            )
            assertThat(maxOf(decoded.input.width, decoded.input.height)).isAtMost(SmartPreviewCodec.LONG_EDGE)
            // Format 2 (ADR 0018 amendment FIX/P): well under the ~9 MB of format 1 for these 12-24 MP RAWs.
            assertThat(made.file.inputStream().use(SmartPreviewCodec::version)).isEqualTo(SmartPreviewCodec.VERSION)
            assertThat(made.size).isLessThan(MAX_PREVIEW_BYTES)
        }
    }

    @Test
    fun anUnreadableOrDamagedFileIsAnIoFailureNotAnIllegalArgument() {
        val gone = File(dir, "gone.jpg")
        assertThrows(FileNotFoundException::class.java) { ImageInput.fromFile(gone) }
        val damaged = File(dir, "damaged.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 0, 1, 2, 3)) }
        assertThrows(IOException::class.java) { ImageInput.fromFile(damaged).use { maker.make(it) } }
    }

    private companion object {
        const val MAX_PREVIEW_BYTES = 6_000_000L
        const val TAG = "REVEAL_PREVIEWS"
        const val TIMEOUT_MS = 30_000L
    }
}
