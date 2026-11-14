package dev.hfps.reveal.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
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

    /**
     * The file decoded by the platform (16-bit PNGs come back as RGBA_F16 whatever is asked), drawn into an 8-bit
     * sRGB bitmap (the canvas converts from the file's colour space), as RGBA bytes.
     */
    private fun decodeSrgb(uri: Uri): Pair<Bitmap, ByteArray> {
        val decoded = checkNotNull(context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) })
        val srgb =
            createBitmap(
                decoded.width,
                decoded.height,
                Bitmap.Config.ARGB_8888,
                false,
                ColorSpace.get(ColorSpace.Named.SRGB),
            )
        Canvas(srgb).drawBitmap(decoded, 0f, 0f, Paint().apply { isFilterBitmap = false })
        val pixels = ByteBuffer.allocate(srgb.byteCount)
        srgb.copyPixelsToBuffer(pixels)
        return srgb to pixels.array()
    }

    private fun sourceSpace(uri: Uri): ColorSpace? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        return bounds.outColorSpace
    }

    /** The pixels that differ most (diagnostics for a failing tolerance). */
    private fun logWorst(name: String, a: ByteArray, b: ByteArray, width: Int) {
        val bad = (0 until a.size / 4).map { p ->
            p to (0 until 3).maxOf { c -> abs((a[p * 4 + c].toInt() and 0xFF) - (b[p * 4 + c].toInt() and 0xFF)) }
        }.filter { it.second > 8 }
        val worst = bad.sortedByDescending { it.second }.take(3).joinToString { (p, d) ->
            val rgb = { x: ByteArray -> (0 until 3).map { x[p * 4 + it].toInt() and 0xFF } }
            "(${p % width},${p / width}) $d: ${rgb(a)} vs ${rgb(b)}"
        }
        Log.i(TAG, "$name: ${bad.size} pixels over 8 levels; worst $worst")
    }

    /** Mean, maximum and 99th percentile of the RGB channels' differences, in 8-bit levels. */
    private class Diff(val mean: Double, val max: Int, val p99: Int)

    private fun diff(a: ByteArray, b: ByteArray): Diff {
        val histogram = IntArray(256)
        var sum = 0L
        var n = 0
        for (i in a.indices) {
            if (i % 4 == 3) continue
            val d = abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            histogram[d]++
            sum += d
            n++
        }
        val max = histogram.indexOfLast { it > 0 }
        var seen = 0
        val p99 = histogram.indices.first {
            seen += histogram[it]
            seen >= n * 0.99
        }
        return Diff(sum.toDouble() / n, max, p99)
    }

    @Test
    fun displayP3ExportsRoundTripToTheSrgbExport() {
        val uri = photo()
        val reference = export(uri, ExportOptions(format = ExportFormat.PNG, bitDepth = 16))
        val (refBitmap, ref) = decodeSrgb(reference.uri)
        // 16-bit PNGs decode to F16 in extended sRGB (the platform converts on decode).
        assertThat(sourceSpace(reference.uri)?.name).isIn(SRGB_FAMILY)
        // (format, depth) to (mean, limit): lossless formats are held to their maximum, lossy ones (4:2:0 chroma at
        // saturated edges) to their 99th percentile.
        val cases = buildList {
            add(ExportFormat.PNG to 16 to (0.5 to 3))
            add(ExportFormat.PNG to 8 to (0.8 to 8))
            add(ExportFormat.JPEG to 8 to (2.0 to 16))
            if (support.heifTiles) add(ExportFormat.HEIF to 8 to (2.5 to 16))
            if (support.heif10) add(ExportFormat.HEIF to 10 to (2.5 to 16))
            if (support.avif8) add(ExportFormat.AVIF to 8 to (2.5 to 16))
            if (support.avif10) add(ExportFormat.AVIF to 10 to (2.5 to 16))
        }
        for ((case, tolerance) in cases) {
            val (format, depth) = case
            val options =
                ExportOptions(format = format, bitDepth = depth, colour = ExportColour.DISPLAY_P3, quality = 95)
            val result = export(uri, options)
            val space = sourceSpace(result.uri)
            val (bitmap, pixels) = decodeSrgb(result.uri)
            assertThat(bitmap.width).isEqualTo(refBitmap.width)
            val d = diff(ref, pixels)
            logWorst("$format $depth", ref, pixels, bitmap.width)
            Log.i(
                TAG,
                "P3 $format $depth-bit: ${result.bytes} bytes, encode ${result.encodeMs} ms, decoded as $space, " +
                    "vs sRGB PNG: mean ${d.mean}, p99 ${d.p99}, max ${d.max} levels",
            )
            // Labelled Display P3 (the decoder read the ICC / nclx; a 16-bit decode is converted to extended sRGB on
            // the way) and the same colours once converted.
            val expected = if (format == ExportFormat.PNG && depth == 16) SRGB_FAMILY + P3 else listOf(P3)
            assertThat(space?.name).isIn(expected)
            assertThat(d.mean).isAtMost(tolerance.first)
            assertThat(if (format == ExportFormat.PNG) d.max else d.p99).isAtMost(tolerance.second)
        }
    }

    /**
     * Full size, every format, on the CC0 ZV-E10 ARW (24 MP; skipped without it in `files/samples`): the timings of
     * docs/adr/0009 "W2/E", and files for exiftool with `-e keep true`.
     */
    @Suppress("LongMethod") // one export per format, each logged
    @Test
    fun fullSizeArwInEveryFormat() {
        val file = TestPhotos.sample(ARW)
        assumeTrue("push $ARW into files/samples (CLAUDE.md)", file != null)
        val uri = TestPhotos.insert(ARW, "image/x-sony-arw", checkNotNull(file)).also { inserted += it }
        val sharpen = OutputSharpening(SharpenMedium.GLOSSY, SharpenAmount.STANDARD)
        val cases = buildList {
            add(ExportOptions(format = ExportFormat.JPEG, quality = 92, colour = ExportColour.DISPLAY_P3))
            add(ExportOptions(format = ExportFormat.JPEG, quality = 92, sharpening = sharpen))
            add(ExportOptions(format = ExportFormat.PNG, bitDepth = 16, colour = ExportColour.DISPLAY_P3))
            add(ExportOptions(format = ExportFormat.TIFF, colour = ExportColour.DISPLAY_P3))
            if (support.heif) add(ExportOptions(format = ExportFormat.HEIF, quality = 90))
            if (support.heifTiles) {
                add(
                    ExportOptions(format = ExportFormat.HEIF, quality = 90, colour = ExportColour.DISPLAY_P3),
                )
            }
            if (support.heif10) {
                add(
                    ExportOptions(
                        format = ExportFormat.HEIF,
                        quality = 90,
                        colour = ExportColour.DISPLAY_P3,
                        bitDepth = 10,
                    ),
                )
            }
            if (support.avif8) {
                add(
                    ExportOptions(format = ExportFormat.AVIF, quality = 95, colour = ExportColour.DISPLAY_P3),
                )
            }
            if (support.avif10) {
                add(
                    ExportOptions(
                        format = ExportFormat.AVIF,
                        quality = 85,
                        colour = ExportColour.DISPLAY_P3,
                        bitDepth = 10,
                    ),
                )
            }
        }
        for (options in cases) {
            val result = engine.export(
                ExportItem(uri, ARW, recipe, rating = 4),
                options.copy(destination = destination),
                Authorship("Ana Example", "© 2026 Ana Example"),
                renderer,
                cancel = { false },
            )
            assertThat(result.width).isEqualTo(6024)
            Log.i(
                TAG,
                "24 MP ${options.format} ${options.effectiveDepth}-bit ${options.colour} " +
                    "sharpen ${options.sharpening}: " +
                    "decode ${result.decodeMs} ms, render ${result.renderMs} ms, encode ${result.encodeMs} ms, " +
                    "${result.bytes} bytes",
            )
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
        val d = diff(pa, pb)
        Log.i(TAG, "sharpening screen/high vs off: mean ${d.mean}, max ${d.max} levels")
        assertThat(d.max).isGreaterThan(5)
        // Flat patches do not move: most pixels stay within a level.
        assertThat(d.mean).isLessThan(3.0)
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
        const val ARW = "sony-zv-e10.arw"
        val P3: String = ColorSpace.get(ColorSpace.Named.DISPLAY_P3).name
        val SRGB_FAMILY = listOf(ColorSpace.Named.SRGB, ColorSpace.Named.EXTENDED_SRGB).map { ColorSpace.get(it).name }
    }
}
