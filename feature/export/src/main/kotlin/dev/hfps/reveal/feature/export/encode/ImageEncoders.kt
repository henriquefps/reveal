// Bit depths (8, 10) are the formats' own.
@file:Suppress("MagicNumber")

package dev.hfps.reveal.feature.export.encode

import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.heifwriter.HeifWriter
import dev.hfps.image.write.ImageCodec
import dev.hfps.reveal.feature.export.metadata.ExifWriter
import dev.hfps.reveal.feature.export.metadata.JpegSegments
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.ExportFormat
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** The metadata blocks an encoder embeds: the EXIF TIFF stream and the XMP packet (either may be absent). */
class EmbeddedMetadata(val exifTiff: ByteArray?, val xmp: String?)

/** Encodes a developed export (an ARGB_8888 bitmap whose colour space is the output's) to a file. */
interface ImageEncoder {
    /** Writes [bitmap] at [quality] (1..100) with [metadata] into [target] (a new file in the app's storage). */
    fun encode(bitmap: Bitmap, quality: Int, metadata: EmbeddedMetadata, target: File)
}

/**
 * JPEG through the platform encoder (`Bitmap.compress`, Skia's libjpeg-turbo; docs/adr/0009 "JPEG"): it embeds the
 * ICC profile of the bitmap's colour space (sRGB or Display P3, checked with exiftool) and subsamples chroma 4:2:0.
 * The encoder's header is then rewritten to put EXIF and XMP first ([JpegSegments]).
 */
object JpegEncoder : ImageEncoder {
    override fun encode(bitmap: Bitmap, quality: Int, metadata: EmbeddedMetadata, target: File) {
        val raw = File(target.parentFile, target.name + ".raw")
        try {
            raw.outputStream().buffered().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("JPEG encode failed")
            }
            val exif = metadata.exifTiff?.let(ExifWriter::app1Payload)?.takeIf(JpegSegments::fits)
            raw.inputStream().use { input ->
                target.outputStream().buffered().use { out -> JpegSegments.rewrite(input, out, exif, metadata.xmp) }
            }
        } finally {
            raw.delete()
        }
    }

    /** Size in bytes of [bitmap] encoded at [quality] (for the estimate; no metadata). */
    fun encodedSize(bitmap: Bitmap, quality: Int): Long {
        val counter = CountingStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, counter)
        return counter.count
    }
}

/**
 * HEIF through `androidx.heifwriter` (HEVC from MediaCodec, the HEIC tile codec when the device has one; docs/adr/0009
 * "HEIF"): 8-bit, sRGB, EXIF in the `Exif` item. XMP is not supported by the writer (the rating is in EXIF instead).
 */
object HeifEncoder : ImageEncoder {
    private const val TIMEOUT_MS = 60_000L

    override fun encode(bitmap: Bitmap, quality: Int, metadata: EmbeddedMetadata, target: File) {
        val writer = HeifWriter.Builder(target.path, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(quality)
            .setGridEnabled(true)
            .build()
        try {
            writer.start()
            writer.addBitmap(bitmap)
            metadata.exifTiff?.let { ExifWriter.app1Payload(it) }?.let { writer.addExifData(0, it, 0, it.size) }
            writer.stop(TIMEOUT_MS)
        } finally {
            // HeifWriter is AutoCloseable; its close() is restricted on the subclass, not on the interface.
            (writer as AutoCloseable).close()
        }
    }
}

/**
 * What the device's encoders can do (docs/adr/0009 "Formats"): the sheet hides what is false, and presets from another
 * device fall back ([dev.hfps.reveal.feature.export.model.ExportPresets.resolve]).
 */
data class EncoderSupport(
    /** An HEVC or HEIC encoder exists: HEIF export is offered. */
    val heif: Boolean,
    /** The device has the HEIC image codec (else HEIF goes through the HEVC video encoder in tiles). */
    val heicCodec: Boolean,
    /** An HEVC encoder has the Main10 profile (whatever its input formats). */
    val hevcMain10: Boolean,
    /** An HEVC encoder takes 512 px YUV420 tiles: HEIF in Display P3 (Reveal's container). */
    val heifTiles: Boolean = false,
    /** An HEVC encoder takes 10-bit P010 tiles in Main10: 10-bit HEIF. */
    val heif10: Boolean = false,
    /** An AV1 encoder takes 8-bit tiles. */
    val avif8: Boolean = false,
    /** An AV1 encoder takes 10-bit P010 tiles in Main10. */
    val avif10: Boolean = false,
) {
    /** AVIF is offered (at whichever depth the device has). */
    val avif: Boolean get() = avif8 || avif10

    /** Whether [format] can be written at [depth] bits in [colour] here. */
    fun supports(format: ExportFormat, depth: Int, colour: ExportColour): Boolean = when (format) {
        ExportFormat.HEIF -> when {
            depth == 10 -> heif10
            colour == ExportColour.SRGB -> heif
            else -> heifTiles
        }

        ExportFormat.AVIF -> if (depth == 10) avif10 else avif8

        else -> true
    }

    /** The bit depths of [format] this device can write. */
    fun depths(format: ExportFormat, colour: ExportColour): List<Int> =
        format.depths.filter { supports(format, it, colour) }

    /** Whether [format] is offered at all. */
    fun offers(format: ExportFormat): Boolean = when (format) {
        ExportFormat.HEIF -> heif
        ExportFormat.AVIF -> avif
        else -> true
    }

    companion object {
        /** Queries MediaCodec once per call (a few ms). */
        fun detect(): EncoderSupport {
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
            fun has(mime: String) = encoders.any { info -> info.supportedTypes.any { it.equals(mime, true) } }
            val main10 = encoders.any { info ->
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    }
            }
            val heic = has(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC)
            return EncoderSupport(
                heif = heic || has(MediaFormat.MIMETYPE_VIDEO_HEVC),
                heicCodec = heic,
                hevcMain10 = main10,
                heifTiles = TileCodecEncoder.find(ImageCodec.HEVC, 8) != null,
                heif10 = TileCodecEncoder.find(ImageCodec.HEVC, 10) != null,
                avif8 = TileCodecEncoder.find(ImageCodec.AV1, 8) != null,
                avif10 = TileCodecEncoder.find(ImageCodec.AV1, 10) != null,
            )
        }
    }
}

/** The bitmap encoders (8-bit, from an output bitmap): JPEG, and HEIF in sRGB through the platform's HeifWriter. */
object ImageEncoders {
    fun of(format: ExportFormat): ImageEncoder = when (format) {
        ExportFormat.JPEG -> JpegEncoder
        ExportFormat.HEIF -> HeifEncoder
        else -> error("$format is not written from a bitmap")
    }
}

private class CountingStream : OutputStream() {
    var count = 0L

    override fun write(b: Int) {
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        count += len
    }
}
