// Tag numbers and TIFF type codes are the EXIF 2.32 / TIFF 6.0 constants.
@file:Suppress("MagicNumber")

package dev.hfps.reveal.feature.export.metadata

import dev.hfps.image.format.ExifField
import dev.hfps.image.format.ExifSnapshot
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.MetadataLevel
import dev.hfps.reveal.feature.export.model.MetadataOptions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/** Everything an export knows about its photo's metadata. */
data class SourceMetadata(
    /** The original's EXIF fields (JPEG and TIFF-based RAWs), else null. */
    val snapshot: ExifSnapshot?,
    /** What the inspector read (fills the gaps for CR3/RAF and a RAW whose pair JPEG has more). */
    val fallback: PhotoMetadata?,
    val rating: Int = 0,
)

/** What the written file is. */
data class OutputFacts(val width: Int, val height: Int, val colour: ExportColour, val software: String)

/**
 * A small EXIF writer (docs/adr/0009, "Metadata"): builds a big-endian TIFF stream (IFD0, EXIF IFD, GPS IFD) from the
 * original's whitelisted fields ([ExifSnapshot]), the author/copyright of Settings and what the export is. Pure
 * Kotlin, JVM-tested; read back by `TiffReader` in tests and by exiftool on real files.
 *
 * The orientation is always 1 (the pixels are rotated upright), the pixel dimensions and colour space are the
 * export's, maker notes and thumbnails are never written.
 */
object ExifWriter {
    const val TYPE_BYTE = 1
    const val TYPE_ASCII = 2
    const val TYPE_SHORT = 3
    const val TYPE_LONG = 4
    const val TYPE_RATIONAL = 5
    const val TYPE_UNDEFINED = 7

    const val TAG_ORIENTATION = 0x0112
    const val TAG_SOFTWARE = 0x0131
    const val TAG_ARTIST = 0x013B
    const val TAG_COPYRIGHT = 0x8298
    const val TAG_RATING = 0x4746
    const val TAG_EXIF_IFD = 0x8769
    const val TAG_GPS_IFD = ExifSnapshot.GPS_IFD
    const val TAG_EXIF_VERSION = 0x9000
    const val TAG_COMPONENTS = 0x9101
    const val TAG_YCBCR_POSITIONING = 0x0213
    const val TAG_COLOR_SPACE = 0xA001
    const val TAG_PIXEL_X = 0xA002
    const val TAG_PIXEL_Y = 0xA003

    private const val COLOR_SPACE_SRGB = 1
    private const val COLOR_SPACE_UNCALIBRATED = 0xFFFF

    /**
     * The TIFF stream for [source] exported as [facts] under [options], or null when nothing is to be written
     * ([MetadataLevel.NONE] without the capture time).
     */
    @Suppress("CyclomaticComplexMethod")
    fun build(
        source: SourceMetadata,
        facts: OutputFacts,
        options: MetadataOptions,
        authorship: Authorship,
    ): ByteArray? {
        val snapshot = source.snapshot ?: source.fallback?.let(::fromMetadata)
        val keepTime = options.keepCaptureTime
        val captureFields = if (keepTime) {
            (snapshot?.ifd0.orEmpty() + snapshot?.exif.orEmpty()).filter { it.tag in ExifSnapshot.CAPTURE_TIME_TAGS }
        } else {
            emptyList()
        }
        if (options.level == MetadataLevel.NONE && captureFields.isEmpty()) return null
        val ifd0 = sortedMapOf<Int, ExifField>()
        val exif = sortedMapOf<Int, ExifField>()
        val gps = sortedMapOf<Int, ExifField>()
        if (options.level == MetadataLevel.ALL && snapshot != null) {
            snapshot.ifd0.filter { it.tag !in ExifSnapshot.CAPTURE_TIME_TAGS }.forEach { ifd0[it.tag] = it }
            snapshot.exif.filter { it.tag !in ExifSnapshot.CAPTURE_TIME_TAGS }.forEach { exif[it.tag] = it }
            if (!options.removeLocation) snapshot.gps.forEach { gps[it.tag] = it }
        }
        for (field in captureFields) {
            if (field.tag == DATE_TIME) ifd0[field.tag] = field else exif[field.tag] = field
        }
        if (options.level != MetadataLevel.NONE) {
            val author = authorship.author.ifBlank { snapshot?.field(TAG_ARTIST)?.text.orEmpty() }
            val copyright = authorship.copyright.ifBlank { snapshot?.field(TAG_COPYRIGHT)?.text.orEmpty() }
            if (author.isNotBlank()) ifd0[TAG_ARTIST] = ascii(TAG_ARTIST, author)
            if (copyright.isNotBlank()) ifd0[TAG_COPYRIGHT] = ascii(TAG_COPYRIGHT, copyright)
            ifd0[TAG_SOFTWARE] = ascii(TAG_SOFTWARE, facts.software)
        }
        if (options.level == MetadataLevel.ALL && source.rating in 1..MAX_RATING) {
            ifd0[TAG_RATING] = short(TAG_RATING, source.rating)
        }
        ifd0[TAG_ORIENTATION] = short(TAG_ORIENTATION, 1)
        // Required by EXIF 2.32 for YCbCr-compressed images (exiftool -validate): centred chroma, Y Cb Cr order.
        ifd0[TAG_YCBCR_POSITIONING] = short(TAG_YCBCR_POSITIONING, 1)
        exif[TAG_COMPONENTS] = ExifField(TAG_COMPONENTS, TYPE_UNDEFINED, 4, byteArrayOf(1, 2, 3, 0))
        exif[TAG_EXIF_VERSION] = ExifField(TAG_EXIF_VERSION, TYPE_UNDEFINED, 4, "0232".toByteArray())
        val space = if (facts.colour == ExportColour.SRGB) COLOR_SPACE_SRGB else COLOR_SPACE_UNCALIBRATED
        exif[TAG_COLOR_SPACE] = short(TAG_COLOR_SPACE, space)
        exif[TAG_PIXEL_X] = long(TAG_PIXEL_X, facts.width.toLong())
        exif[TAG_PIXEL_Y] = long(TAG_PIXEL_Y, facts.height.toLong())
        return write(ifd0.values.toList(), exif.values.toList(), gps.values.toList())
    }

    /** The EXIF payload of a JPEG APP1 / HEIF `Exif` item: `Exif\0\0` + the TIFF stream. */
    fun app1Payload(tiff: ByteArray): ByteArray = EXIF_HEADER + tiff

    /** Lays out IFD0 (with pointers), the EXIF IFD and the GPS IFD, values after each IFD, word aligned. */
    fun write(ifd0: List<ExifField>, exif: List<ExifField>, gps: List<ExifField>): ByteArray {
        // Pointer placeholders so IFD0's size is known before the sub-IFD offsets are.
        val withPointers = (
            ifd0.filter { it.tag != TAG_EXIF_IFD && it.tag != TAG_GPS_IFD } +
                long(TAG_EXIF_IFD, 0) + listOfNotNull(if (gps.isNotEmpty()) long(TAG_GPS_IFD, 0) else null)
            )
            .sortedBy { it.tag }
        val ifd0Size = ifdSize(withPointers)
        val exifOffset = HEADER_SIZE + ifd0Size
        val exifSize = ifdSize(exif)
        val gpsOffset = exifOffset + exifSize
        val resolved = withPointers.map {
            when (it.tag) {
                TAG_EXIF_IFD -> long(TAG_EXIF_IFD, exifOffset.toLong())
                TAG_GPS_IFD -> long(TAG_GPS_IFD, gpsOffset.toLong())
                else -> it
            }
        }
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, TIFF_MAGIC.toByte(), 0, 0, 0, HEADER_SIZE.toByte()),
        )
        writeIfd(out, resolved, HEADER_SIZE)
        writeIfd(out, exif.sortedBy { it.tag }, exifOffset)
        if (gps.isNotEmpty()) writeIfd(out, gps.sortedBy { it.tag }, gpsOffset)
        return out.toByteArray()
    }

    private fun ifdSize(fields: List<ExifField>): Int =
        IFD_COUNT + fields.size * ENTRY_SIZE + IFD_NEXT + fields.sumOf { external(it) }

    private fun external(field: ExifField): Int = if (field.data.size <=
        INLINE
    ) {
        0
    } else {
        (field.data.size + 1) and 1.inv()
    }

    private fun writeIfd(out: ByteArrayOutputStream, fields: List<ExifField>, start: Int) {
        var valueOffset = start + IFD_COUNT + fields.size * ENTRY_SIZE + IFD_NEXT
        val values = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(IFD_COUNT + fields.size * ENTRY_SIZE + IFD_NEXT)
        head.putShort(fields.size.toShort())
        for (f in fields) {
            head.putShort(f.tag.toShort()).putShort(f.type.toShort()).putInt(f.count)
            if (f.data.size <= INLINE) {
                head.put(f.data.copyOf(INLINE))
            } else {
                head.putInt(valueOffset)
                values.write(f.data)
                if (f.data.size % 2 == 1) values.write(0)
                valueOffset += external(f)
            }
        }
        head.putInt(0)
        out.write(head.array())
        out.write(values.toByteArray())
    }

    fun ascii(tag: Int, text: String): ExifField {
        // EXIF ASCII is 7-bit; other characters become '?' (Settings also writes them in XMP, which is UTF-8).
        val bytes =
            text.replace("©", "(C)").map {
                if (it.code in
                    ASCII_PRINTABLE
                ) {
                    it.code.toByte()
                } else {
                    '?'.code.toByte()
                }
            }.toByteArray() +
                0.toByte()
        return ExifField(tag, TYPE_ASCII, bytes.size, bytes)
    }

    fun short(tag: Int, value: Int): ExifField =
        ExifField(tag, TYPE_SHORT, 1, byteArrayOf((value shr 8).toByte(), value.toByte()))

    fun long(tag: Int, value: Long): ExifField =
        ExifField(tag, TYPE_LONG, 1, ByteBuffer.allocate(4).putInt(value.toInt()).array())

    fun rational(tag: Int, numerator: Long, denominator: Long): ExifField = ExifField(
        tag,
        TYPE_RATIONAL,
        1,
        ByteBuffer.allocate(8).putInt(numerator.toInt()).putInt(denominator.toInt()).array(),
    )

    /** The fields an inspector-only container (CR3, RAF) can still give. */
    private fun fromMetadata(m: PhotoMetadata): ExifSnapshot {
        val ifd0 = listOfNotNull(m.make?.let { ascii(0x010F, it) }, m.model?.let { ascii(0x0110, it) })
        val exif = listOfNotNull(
            m.exposureTime?.let { rational(0x829A, it.numerator, it.denominator) },
            m.fNumber?.let { rational(0x829D, (it * RATIONAL_SCALE).roundToLong(), RATIONAL_SCALE.toLong()) },
            m.iso?.let { short(0x8827, it.coerceAtMost(MAX_SHORT)) },
            m.captureTime?.let { ascii(0x9003, it) },
            m.offsetTime?.let { ascii(0x9011, it) },
            m.focalLength?.let { rational(0x920A, (it * RATIONAL_SCALE).roundToLong(), RATIONAL_SCALE.toLong()) },
            m.subSecond?.let { ascii(0x9291, it) },
            m.focalLength35?.let { short(0xA405, it.coerceAtMost(MAX_SHORT)) },
            m.lens?.let { ascii(0xA434, it) },
        )
        return ExifSnapshot(ifd0, exif, emptyList())
    }

    private const val DATE_TIME = 0x0132
    private const val MAX_RATING = 5
    private const val MAX_SHORT = 65535
    private const val RATIONAL_SCALE = 100
    private const val HEADER_SIZE = 8
    private const val TIFF_MAGIC = 42
    private const val IFD_COUNT = 2
    private const val IFD_NEXT = 4
    private const val ENTRY_SIZE = 12
    private const val INLINE = 4
    private val ASCII_PRINTABLE = 0x20..0x7E
    private val EXIF_HEADER =
        byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
}
