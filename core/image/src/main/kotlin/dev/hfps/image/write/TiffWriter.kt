// TIFF 6.0 / Adobe TIFF technical notes / EXIF 2.32: tag numbers and type codes are the specifications'.
@file:Suppress("MagicNumber")

package dev.hfps.image.write

import dev.hfps.image.format.ExifField
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.zip.Deflater

/** The EXIF fields a TIFF carries in its own IFD0 and sub-IFDs (big-endian values, as `ExifWriter` builds them). */
class TiffFields(
    val ifd0: List<ExifField> = emptyList(),
    val exif: List<ExifField> = emptyList(),
    val gps: List<ExifField> = emptyList(),
)

/**
 * A TIFF writer for further editing (docs/adr/0009 "TIFF"): baseline RGB, 8 or 16 bits per sample, chunky, Adobe
 * Deflate (compression 8) with the horizontal predictor (2) in strips of about [STRIP_BYTES], big-endian ("MM"). The
 * ICC profile in tag 34675, XMP in tag 700, EXIF in IFD0 (camera, author, copyright, dates) plus the EXIF and GPS
 * sub-IFDs. Strips are streamed to the file first, the IFDs written after them and the header's IFD offset patched,
 * so memory stays one strip whatever the image size. Pure Kotlin, JVM-tested (read back with `TiffReader` and
 * `ImageIO`) and checked with exiftool on device exports.
 */
object TiffWriter {
    const val TYPE_BYTE = 1
    const val TYPE_ASCII = 2
    const val TYPE_SHORT = 3
    const val TYPE_LONG = 4
    const val TYPE_RATIONAL = 5
    const val TYPE_UNDEFINED = 7

    const val TAG_WIDTH = 256
    const val TAG_HEIGHT = 257
    const val TAG_BITS = 258
    const val TAG_COMPRESSION = 259
    const val TAG_PHOTOMETRIC = 262
    const val TAG_STRIP_OFFSETS = 273
    const val TAG_ORIENTATION = 274
    const val TAG_SAMPLES = 277
    const val TAG_ROWS_PER_STRIP = 278
    const val TAG_STRIP_BYTES = 279
    const val TAG_X_RESOLUTION = 282
    const val TAG_Y_RESOLUTION = 283
    const val TAG_PLANAR = 284
    const val TAG_RESOLUTION_UNIT = 296
    const val TAG_PREDICTOR = 317
    const val TAG_XMP = 700
    const val TAG_EXIF_IFD = 34665
    const val TAG_GPS_IFD = 34853
    const val TAG_ICC = 34675

    const val COMPRESSION_DEFLATE = 8
    const val PREDICTOR_HORIZONTAL = 2

    /** Uncompressed bytes per strip (Adobe's recommendation: about 8 KB-1 MB). */
    const val STRIP_BYTES = 256 * 1024

    /** IFD0 tags an image writes itself; EXIF fields with these numbers are dropped (and JPEG-only ones). */
    private val OWN_TAGS = setOf(
        TAG_WIDTH, TAG_HEIGHT, TAG_BITS, TAG_COMPRESSION, TAG_PHOTOMETRIC, TAG_STRIP_OFFSETS, TAG_ORIENTATION,
        TAG_SAMPLES, TAG_ROWS_PER_STRIP, TAG_STRIP_BYTES, TAG_X_RESOLUTION, TAG_Y_RESOLUTION, TAG_PLANAR,
        TAG_RESOLUTION_UNIT, TAG_PREDICTOR, TAG_XMP, TAG_EXIF_IFD, TAG_GPS_IFD, TAG_ICC,
        // YCbCrPositioning and the thumbnail pointers belong to JPEG/YCbCr images.
        0x0213, 0x0201, 0x0202,
    )

    /**
     * EXIF sub-IFD fields a TIFF must not carry (exiftool -validate): components configuration (compressed YCbCr
     * data only) and the pixel dimensions (IFD0's ImageWidth/ImageLength say it).
     */
    private val JPEG_ONLY_EXIF = setOf(0x9101, 0xA002, 0xA003)

    /** FlashpixVersion "0100", required in a TIFF's EXIF IFD. */
    private const val TAG_FLASHPIX_VERSION = 0xA000

    /** Writes [rows] with [fields] and [metadata] (ICC, XMP; its EXIF stream is ignored here) to [target]. */
    @Suppress("NestedBlockDepth")
    fun write(
        rows: RgbRows,
        fields: TiffFields,
        metadata: ImageMetadata,
        target: File,
        level: Int = Deflater.DEFAULT_COMPRESSION,
        cancel: () -> Boolean = { false },
    ) {
        require(rows.bits == 8 || rows.bits == 16) { "TIFF writes 8 or 16 bits" }
        val bytesPerSample = rows.bits / 8
        val stride = rows.width * 3 * bytesPerSample
        val rowsPerStrip = maxOf(1, minOf(rows.height, STRIP_BYTES / stride))
        val strips = (rows.height + rowsPerStrip - 1) / rowsPerStrip
        val offsets = LongArray(strips)
        val counts = LongArray(strips)
        RandomAccessFile(target, "rw").use { file ->
            file.setLength(0)
            // Header; the IFD0 offset is patched at the end.
            file.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 0))
            val deflater = Deflater(level)
            try {
                val raw = ByteArray(stride * rowsPerStrip)
                val compressed = ByteArray(raw.size + raw.size / 100 + 1024)
                val samples = IntArray(rows.width * 3)
                for (s in 0 until strips) {
                    if (cancel()) throw CancellationException("TIFF cancelled")
                    val first = s * rowsPerStrip
                    val n = minOf(rowsPerStrip, rows.height - first)
                    for (r in 0 until n) {
                        rows.row(first + r, samples)
                        predict(samples, rows.width)
                        val base = r * stride
                        if (bytesPerSample == 2) {
                            for (i in samples.indices) {
                                raw[base + 2 * i] = (samples[i] shr 8).toByte()
                                raw[base + 2 * i + 1] = samples[i].toByte()
                            }
                        } else {
                            for (i in samples.indices) raw[base + i] = samples[i].toByte()
                        }
                    }
                    deflater.reset()
                    deflater.setInput(raw, 0, n * stride)
                    deflater.finish()
                    offsets[s] = file.filePointer
                    var written = 0L
                    while (!deflater.finished()) {
                        val k = deflater.deflate(compressed)
                        file.write(compressed, 0, k)
                        written += k
                    }
                    counts[s] = written
                    if (file.filePointer % 2 == 1L) file.write(0)
                }
            } finally {
                deflater.end()
            }
            val ifd0Offset = file.filePointer
            file.write(directories(rows, fields, metadata, rowsPerStrip, offsets, counts, ifd0Offset))
            file.seek(4)
            file.writeInt(ifd0Offset.toInt())
        }
    }

    /** TIFF predictor 2: each sample minus the same channel of the pixel before it, modulo the sample range. */
    private fun predict(samples: IntArray, width: Int) {
        for (x in width - 1 downTo 1) {
            for (c in 0 until 3) {
                val i = x * 3 + c
                samples[i] = (samples[i] - samples[i - 3]) and 0xFFFF
            }
        }
    }

    @Suppress("LongParameterList")
    private fun directories(
        rows: RgbRows,
        fields: TiffFields,
        metadata: ImageMetadata,
        rowsPerStrip: Int,
        offsets: LongArray,
        counts: LongArray,
        start: Long,
    ): ByteArray {
        val bits = ByteBuffer.allocate(6).putShort(rows.bits.toShort()).putShort(rows.bits.toShort())
            .putShort(rows.bits.toShort()).array()
        val own = mutableListOf(
            long(TAG_WIDTH, rows.width.toLong()),
            long(TAG_HEIGHT, rows.height.toLong()),
            ExifField(TAG_BITS, TYPE_SHORT, 3, bits),
            short(TAG_COMPRESSION, COMPRESSION_DEFLATE),
            short(TAG_PHOTOMETRIC, 2),
            longs(TAG_STRIP_OFFSETS, offsets),
            short(TAG_ORIENTATION, 1),
            short(TAG_SAMPLES, 3),
            long(TAG_ROWS_PER_STRIP, rowsPerStrip.toLong()),
            longs(TAG_STRIP_BYTES, counts),
            rational(TAG_X_RESOLUTION, 300, 1),
            rational(TAG_Y_RESOLUTION, 300, 1),
            short(TAG_PLANAR, 1),
            short(TAG_RESOLUTION_UNIT, 2),
            short(TAG_PREDICTOR, PREDICTOR_HORIZONTAL),
        )
        metadata.xmp?.let {
            val b = it.toByteArray(Charsets.UTF_8)
            own += ExifField(TAG_XMP, TYPE_BYTE, b.size, b)
        }
        metadata.icc?.let { own += ExifField(TAG_ICC, TYPE_UNDEFINED, it.size, it) }
        val exif = fields.exif.filter { it.tag !in JPEG_ONLY_EXIF && it.tag != TAG_FLASHPIX_VERSION }.let { list ->
            if (list.isEmpty()) {
                list
            } else {
                list +
                    ExifField(TAG_FLASHPIX_VERSION, TYPE_UNDEFINED, 4, "0100".toByteArray())
            }
        }
        if (exif.isNotEmpty()) own += long(TAG_EXIF_IFD, 0)
        if (fields.gps.isNotEmpty()) own += long(TAG_GPS_IFD, 0)
        val ifd0 = (fields.ifd0.filter { it.tag !in OWN_TAGS } + own).sortedBy { it.tag }
        val ifd0Size = ifdSize(ifd0)
        val exifAt = start + ifd0Size
        val gpsAt = exifAt + if (exif.isNotEmpty()) ifdSize(exif) else 0
        val resolved = ifd0.map {
            when (it.tag) {
                TAG_EXIF_IFD -> long(TAG_EXIF_IFD, exifAt)
                TAG_GPS_IFD -> long(TAG_GPS_IFD, gpsAt)
                else -> it
            }
        }
        val out = ByteArrayOutputStream()
        writeIfd(out, resolved, start)
        if (exif.isNotEmpty()) writeIfd(out, exif.sortedBy { it.tag }, exifAt)
        if (fields.gps.isNotEmpty()) writeIfd(out, fields.gps.sortedBy { it.tag }, gpsAt)
        return out.toByteArray()
    }

    private fun ifdSize(fields: List<ExifField>): Long =
        2L + fields.size * 12 + 4 + fields.sumOf { external(it).toLong() }

    private fun external(field: ExifField): Int = if (field.data.size <= 4) 0 else (field.data.size + 1) and 1.inv()

    private fun writeIfd(out: ByteArrayOutputStream, fields: List<ExifField>, start: Long) {
        var valueOffset = start + 2 + fields.size * 12 + 4
        val values = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(2 + fields.size * 12 + 4)
        head.putShort(fields.size.toShort())
        for (f in fields) {
            head.putShort(f.tag.toShort()).putShort(f.type.toShort()).putInt(f.count)
            if (f.data.size <= 4) {
                head.put(f.data.copyOf(4))
            } else {
                head.putInt(valueOffset.toInt())
                values.write(f.data)
                if (f.data.size % 2 == 1) values.write(0)
                valueOffset += external(f)
            }
        }
        head.putInt(0)
        out.write(head.array())
        out.write(values.toByteArray())
    }

    private fun short(tag: Int, v: Int) = ExifField(tag, TYPE_SHORT, 1, byteArrayOf((v shr 8).toByte(), v.toByte()))

    private fun long(tag: Int, v: Long) = ExifField(tag, TYPE_LONG, 1, ByteBuffer.allocate(4).putInt(v.toInt()).array())

    private fun longs(tag: Int, v: LongArray): ExifField {
        val b = ByteBuffer.allocate(v.size * 4)
        v.forEach { b.putInt(it.toInt()) }
        return ExifField(tag, TYPE_LONG, v.size, b.array())
    }

    private fun rational(tag: Int, n: Int, d: Int) =
        ExifField(tag, TYPE_RATIONAL, 1, ByteBuffer.allocate(8).putInt(n).putInt(d).array())
}
