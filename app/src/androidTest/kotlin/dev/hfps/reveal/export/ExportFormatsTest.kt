package dev.hfps.reveal.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.write.TiffWriter
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.export.encode.EncoderSupport
import dev.hfps.reveal.feature.export.engine.ExportEngine
import dev.hfps.reveal.feature.export.engine.ExportItem
import dev.hfps.reveal.feature.export.engine.ExportResult
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportFormat
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.pipeline.export.OutputSharpening
import dev.hfps.reveal.pipeline.export.SharpenAmount
import dev.hfps.reveal.pipeline.export.SharpenMedium
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The W2/E formats on a device (docs/adr/0009 "Formats", "Colour management"): a synthetic photo (saturated patches
 * and gradients) exported as PNG 8/16, TIFF 16, HEIF and AVIF in Display P3 (where the encoders exist), each decoded
 * by the platform into sRGB and compared with the lossless sRGB export; output sharpening changes the file; the
 * original copy with its sidecar. Numbers under `REVEAL_EXPORT`. `-e keep true` leaves the files in
 * `files/saf-test/formats` for exiftool.
 */
@RunWith(AndroidJUnit4::class)
class ExportFormatsTest {
    private val context = TestPhotos.context
    private val keep = InstrumentationRegistry.getArguments().getString("keep") == "true"
    private val inserted = mutableListOf<Uri>()
    private lateinit var renderer: TiledRenderer
    private val engine = ExportEngine(context)
    private val support = EncoderSupport.detect()
    private val folder = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/formats")
    private val destination = ExportDestination.Folder(SyntheticDocumentsProvider.treeUri("formats").toString(), "f")
    private val recipe = Recipe(light = Light(0.2f, contrast = 10f))

    @Before
    fun setUp() {
        folder.deleteRecursively()
        folder.mkdirs()
        renderer = TiledRenderer.create()
        Log.i(TAG, "encoders: $support")
    }

    @After
    fun tearDown() {
        renderer.close()
        inserted.forEach(TestPhotos::delete)
        if (!keep) folder.deleteRecursively()
    }

    private fun photo(): Uri =
        TestPhotos.insert("patches.jpg", "image/jpeg", TestPhotos.syntheticJpeg()).also { inserted += it }

    private fun export(uri: Uri, options: ExportOptions, sidecar: ByteArray? = null): ExportResult = engine.export(
        ExportItem(uri, "patches.jpg", recipe, rating = 4, sidecar = sidecar),
        options.copy(destination = options.destination.takeIf { it != ExportDestination.Pictures } ?: destination),
        Authorship("Ana Example", "© 2026 Ana Example"),
        renderer,
        cancel = { false },
    )

    /** The file decoded by the platform, converted to sRGB 8-bit RGBA. */
    private fun decodeSrgb(uri: Uri): Pair<Bitmap, ByteArray> {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap =
            checkNotNull(
                context.contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it, null, options)
                },
            )
        val pixels = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(pixels)
        return bitmap to pixels.array()
    }

    private fun sourceSpace(uri: Uri): ColorSpace? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        return bounds.outColorSpace
    }

    /** Mean and maximum difference of the RGB channels, in 8-bit levels. */
    private fun diff(a: ByteArray, b: ByteArray): Pair<Double, Int> {
        var sum = 0L
        var max = 0
        var n = 0
        for (i in a.indices) {
            if (i % 4 == 3) continue
            val d = abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            sum += d
            max = maxOf(max, d)
            n++
        }
        return sum.toDouble() / n to max
    }

    @Test
    fun displayP3ExportsRoundTripToTheSrgbExport() {
        val uri = photo()
        val reference = export(uri, ExportOptions(format = ExportFormat.PNG, bitDepth = 16))
        val (refBitmap, ref) = decodeSrgb(reference.uri)
        assertThat(sourceSpace(reference.uri)).isEqualTo(ColorSpace.get(ColorSpace.Named.SRGB))
        val cases = buildList {
            add(Triple(ExportFormat.PNG, 16, 1.0) to (0.5 to 3))
            add(Triple(ExportFormat.PNG, 8, 1.0) to (0.8 to 3))
            add(Triple(ExportFormat.JPEG, 8, 1.0) to (2.0 to 40))
            if (support.heifTiles) add(Triple(ExportFormat.HEIF, 8, 1.0) to (2.5 to 60))
            if (support.heif10) add(Triple(ExportFormat.HEIF, 10, 1.0) to (2.5 to 60))
            if (support.avif8) add(Triple(ExportFormat.AVIF, 8, 1.0) to (2.5 to 60))
            if (support.avif10) add(Triple(ExportFormat.AVIF, 10, 1.0) to (2.5 to 60))
        }
        for ((case, tolerance) in cases) {
            val (format, depth) = case
            val options =
                ExportOptions(format = format, bitDepth = depth, colour = ExportColour.DISPLAY_P3, quality = 95)
            val result = export(uri, options)
            val space = sourceSpace(result.uri)
            val (bitmap, pixels) = decodeSrgb(result.uri)
            assertThat(bitmap.width).isEqualTo(refBitmap.width)
            val (mean, max) = diff(ref, pixels)
            Log.i(
                TAG,
                "P3 $format $depth-bit: ${result.bytes} bytes, encode ${result.encodeMs} ms, decoded as $space, " +
                    "vs sRGB PNG: mean $mean, max $max levels",
            )
            // Labelled Display P3 (the decoder read the ICC / nclx) and the same colours once converted.
            assertThat(space?.name).isEqualTo(ColorSpace.get(ColorSpace.Named.DISPLAY_P3).name)
            assertThat(mean).isAtMost(tolerance.first)
            assertThat(max).isAtMost(tolerance.second)
        }
    }

    @Test
    fun sixteenBitTiffCarriesItsProfileAndMetadata() {
        val result = export(photo(), ExportOptions(format = ExportFormat.TIFF, colour = ExportColour.DISPLAY_P3))
        val bytes = checkNotNull(context.contentResolver.openInputStream(result.uri)).use { it.readBytes() }
        assertThat(String(bytes, 0, 2)).isEqualTo("MM")
        val b = ByteBuffer.wrap(bytes)
        val ifd = b.getInt(4)
        val tags = (0 until b.getShort(ifd).toInt()).map { b.getShort(ifd + 2 + it * 12).toInt() and 0xFFFF }
        assertThat(tags).containsAtLeast(TiffWriter.TAG_ICC, TiffWriter.TAG_XMP, TiffWriter.TAG_EXIF_IFD, 0x013B)
        // 16 bits per sample.
        val bits = tags.indexOf(TiffWriter.TAG_BITS)
        val bitsOffset = b.getInt(ifd + 2 + bits * 12 + 8)
        assertThat(b.getShort(bitsOffset).toInt()).isEqualTo(16)
        Log.i(
            TAG,
            "TIFF 16-bit P3 ${result.width}x${result.height}: ${result.bytes} bytes, encode ${result.encodeMs} ms",
        )
    }

    @Test
    fun outputSharpeningChangesTheFileAndNotItsSize() {
        val uri = photo()
        val plain = export(uri, ExportOptions(format = ExportFormat.PNG))
        val sharp = export(
            uri,
            ExportOptions(
                format = ExportFormat.PNG,
                sharpening = OutputSharpening(SharpenMedium.SCREEN, SharpenAmount.HIGH),
            ),
        )
        val (a, pa) = decodeSrgb(plain.uri)
        val (b, pb) = decodeSrgb(sharp.uri)
        assertThat(b.width).isEqualTo(a.width)
        val (mean, max) = diff(pa, pb)
        Log.i(TAG, "sharpening screen/high vs off: mean $mean, max $max levels")
        assertThat(max).isGreaterThan(5)
        // Flat patches do not move: most pixels stay within a level.
        assertThat(mean).isLessThan(3.0)
    }

    @Test
    fun theOriginalCopyKeepsItsBytesAndGetsItsSidecar() {
        val data = TestPhotos.syntheticJpeg(320, 200)
        val uri = TestPhotos.insert("orig.jpg", "image/jpeg", data).also { inserted += it }
        val original = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".toByteArray()
        val result = engine.export(
            ExportItem(uri, "orig.jpg", recipe, sidecar = xmp),
            ExportOptions(format = ExportFormat.ORIGINAL, destination = destination),
            Authorship(),
            renderer,
            cancel = { false },
        )
        assertThat(result.fileName).isEqualTo("orig.jpg")
        assertThat(context.contentResolver.openInputStream(result.uri).use { it?.readBytes() }).isEqualTo(original)
        assertThat(result.extra).hasSize(1)
        assertThat(File(folder, "orig.xmp").readBytes()).isEqualTo(xmp)
        // Without the sidecar option, only the file.
        val alone = engine.export(
            ExportItem(uri, "orig.jpg", recipe, sidecar = xmp),
            ExportOptions(format = ExportFormat.ORIGINAL, destination = destination, originalSidecar = false),
            Authorship(),
            renderer,
            cancel = { false },
        )
        assertThat(alone.extra).isEmpty()
    }

    private companion object {
        const val TAG = "REVEAL_EXPORT"
    }
}
