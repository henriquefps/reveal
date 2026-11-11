// Tag numbers are the EXIF 2.32 / TIFF 6.0 constants.
@file:Suppress("MagicNumber")

package dev.hfps.image.format

import dev.hfps.image.source.RandomAccessSource
import dev.hfps.image.tiff.Ifd
import dev.hfps.image.tiff.JpegMarkers
import dev.hfps.image.tiff.Tags
import dev.hfps.image.tiff.TiffReader

/**
 * One EXIF field copied from a file: TIFF [type] and [count] as stored, [data] the value bytes in **big-endian**
 * (multi-byte elements swapped from a little-endian file), so a writer can copy it into any big-endian TIFF.
 */
class ExifField(val tag: Int, val type: Int, val count: Int, val data: ByteArray) {
    /** The value as text for ASCII fields (up to the first NUL). */
    val text: String?
        get() = if (type == TiffReader.TYPE_ASCII) data.decodeToString().substringBefore('\u0000') else null

    override fun toString(): String = "ExifField(0x${tag.toString(HEX)}, type $type, count $count)"

    private companion object {
        const val HEX = 16
    }
}

/**
 * The EXIF fields of a photo that an export can carry over (docs/adr/0009, "Metadata"): a whitelist of IFD0 (camera,
 * author), EXIF (exposure, lens, capture time) and the whole GPS IFD. Maker notes, thumbnails, offsets into the
 * original file, serial numbers and the original's orientation and sizes are never copied: the export writes its own.
 */
class ExifSnapshot(val ifd0: List<ExifField>, val exif: List<ExifField>, val gps: List<ExifField>) {
    fun field(tag: Int): ExifField? = (ifd0 + exif + gps).firstOrNull { it.tag == tag }

    companion object {
        /** IFD0 tags copied: description, make, model, artist, copyright, the modification date. */
        val IFD0_TAGS: Set<Int> = setOf(0x010E, Tags.MAKE, Tags.MODEL, Tags.DATE_TIME, 0x013B, 0x8298)

        /** EXIF IFD tags copied (exposure, lens, capture time). */
        val EXIF_TAGS: Set<Int> = setOf(
            Tags.EXPOSURE_TIME, Tags.F_NUMBER, 0x8822, Tags.ISO, 0x8830, Tags.RECOMMENDED_EXPOSURE_INDEX,
            Tags.DATE_TIME_ORIGINAL, 0x9004, 0x9010, Tags.OFFSET_TIME_ORIGINAL, 0x9012,
            0x9201, 0x9202, 0x9204, 0x9205, 0x9207, 0x9208, 0x9209, Tags.FOCAL_LENGTH,
            0x9290, Tags.SUB_SEC_TIME_ORIGINAL, 0x9292,
            0xA402, 0xA403, Tags.FOCAL_LENGTH_35, 0xA406, 0xA432, Tags.LENS_MAKE, 0xA434,
        )

        /** Tags that describe when the photo was taken (kept or dropped together). */
        val CAPTURE_TIME_TAGS: Set<Int> = setOf(
            Tags.DATE_TIME, Tags.DATE_TIME_ORIGINAL, 0x9004, 0x9010, Tags.OFFSET_TIME_ORIGINAL, 0x9012,
            0x9290, Tags.SUB_SEC_TIME_ORIGINAL, 0x9292,
        )

        /** GPS IFD pointer. */
        const val GPS_IFD: Int = 0x8825

        /** Largest value copied (a GPS processing method or a long description); bigger fields are skipped. */
        private const val MAX_BYTES = 4096

        /**
         * Reads the snapshot of a JPEG (APP1 EXIF) or a TIFF-based RAW (ARW, DNG, NEF, ...) from [source]; `null` for
         * other containers or a damaged header.
         */
        fun read(source: RandomAccessSource, type: ImageType): ExifSnapshot? = runCatching {
            val tiff = when {
                type.kind == ImageKind.JPEG -> {
                    val header = JpegMarkers.read(source, 0) ?: return null
                    if (header.exifTiffOffset < 0) return null
                    TiffReader.open(source, header.exifTiffOffset)
                }

                type.isRaw && type.raw != RawFormat.CR3 && type.raw != RawFormat.RAF -> TiffReader.open(source, 0)

                else -> return null
            }
            val ifd0 = tiff.ifd(tiff.firstIfd) ?: return null
            val exif = tiff.int(ifd0[Tags.EXIF_IFD])?.let { tiff.ifd(it) }
            val gps = tiff.int(ifd0[GPS_IFD])?.let { tiff.ifd(it) }
            ExifSnapshot(
                fields(tiff, ifd0) { it in IFD0_TAGS },
                exif?.let { fields(tiff, it) { tag -> tag in EXIF_TAGS } }.orEmpty(),
                gps?.let { fields(tiff, it) { tag -> tag <= MAX_GPS_TAG } }.orEmpty(),
            )
        }.getOrNull()

        private const val MAX_GPS_TAG = 0x001F

        private fun fields(tiff: TiffReader, ifd: Ifd, keep: (Int) -> Boolean): List<ExifField> =
            ifd.entries.values.filter { keep(it.tag) && it.count in 1..MAX_BYTES.toLong() }.mapNotNull { entry ->
                val unit = TiffReader.typeSize(entry.type)
                if (unit <= 0 || entry.byteLength > MAX_BYTES) return@mapNotNull null
                val raw = tiff.bytes(entry, MAX_BYTES)
                if (raw.size.toLong() != entry.byteLength) return@mapNotNull null
                ExifField(entry.tag, entry.type, entry.count.toInt(), bigEndian(raw, entry.type, tiff.littleEndian))
            }

        /** Swaps every multi-byte element of a little-endian value (rationals are two 4-byte elements). */
        @Suppress("ReturnCount")
        fun bigEndian(raw: ByteArray, type: Int, littleEndian: Boolean): ByteArray {
            if (!littleEndian) return raw
            val element = when (type) {
                TYPE_SHORT, TYPE_SSHORT -> 2
                TYPE_LONG, TYPE_SLONG, TYPE_FLOAT, TYPE_RATIONAL, TYPE_SRATIONAL -> 4
                TYPE_DOUBLE -> 8
                else -> 1
            }
            if (element == 1) return raw
            val out = raw.copyOf()
            var i = 0
            while (i + element <= out.size) {
                for (k in 0 until element) out[i + k] = raw[i + element - 1 - k]
                i += element
            }
            return out
        }

        private const val TYPE_SHORT = 3
        private const val TYPE_LONG = 4
        private const val TYPE_RATIONAL = 5
        private const val TYPE_SSHORT = 8
        private const val TYPE_SLONG = 9
        private const val TYPE_SRATIONAL = 10
        private const val TYPE_FLOAT = 11
        private const val TYPE_DOUBLE = 12
    }
}
