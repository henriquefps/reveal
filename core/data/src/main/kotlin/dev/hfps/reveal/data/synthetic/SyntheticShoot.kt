package dev.hfps.reveal.data.synthetic

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** EXIF of a synthetic shot. */
data class SyntheticExif(
    val make: String = "SONY",
    val model: String = "ZV-E10",
    val lens: String = "E 16-50mm F3.5-5.6 OSS",
    /** "YYYY:MM:DD HH:MM:SS". */
    val dateTime: String = "2026:08:07 10:15:30",
    val subSecond: String = "250",
    val exposureDenominator: Int = 250,
    val fNumberTenths: Int = 40,
    val iso: Int = 400,
    val focalLengthMm: Int = 24,
    val orientation: Int = 1,
    val width: Int = 6000,
    val height: Int = 4000,
)

/**
 * Synthetic camera files for tests and the debug document provider (no private photo, nothing downloaded): a
 * JPEG with an EXIF block spliced in, a TIFF-based "RAW" (`.ARW`) that carries the same EXIF and an embedded JPEG
 * preview (what the library reads; LibRaw cannot decode it, the editor shows "corrupt"), and XMP sidecars. The
 * base JPEG is supplied by the caller (`Bitmap.compress` on a device, a marker-only JPEG on the JVM).
 */
@Suppress("MagicNumber") // Byte layouts of TIFF and JPEG: the numbers are the formats' own.
object SyntheticShoot {
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5

    /** [jpeg] with an APP1 EXIF segment for [exif] inserted after SOI. */
    fun jpeg(jpeg: ByteArray, exif: SyntheticExif): ByteArray {
        require(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff(exif, preview = null)
        val segment = ByteArrayOutputStream().apply {
            write(0xFF)
            write(0xE1)
            val length = payload.size + 2
            write(length shr 8 and 0xFF)
            write(length and 0xFF)
            write(payload)
        }.toByteArray()
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    /** A TIFF-based RAW stand-in: IFD0 (make, model, orientation, preview), the EXIF IFD, then [preview]. */
    fun raw(preview: ByteArray, exif: SyntheticExif): ByteArray = tiff(exif, preview)

    /** A minimal baseline JPEG header (SOI, SOF0 of [width] x [height], EOI): enough for header parsers, not pixels. */
    fun markerOnlyJpeg(width: Int, height: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(), 0, 11, 8,
        (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 1, 1, 0x11, 0,
        0xFF.toByte(), 0xD9.toByte(),
    )

    /** A Hoard/Lightroom-style sidecar: `xmp:Rating` (-1 = rejected), optional label and digiKam pick label. */
    fun sidecar(
        rating: Int,
        label: String? = null,
        pickLabel: Int? = null,
        creatorTool: String = "Hoard 1.7.2",
    ): String {
        val attributes = listOfNotNull(
            "xmp:Rating=\"$rating\"",
            label?.let { "xmp:Label=\"$it\"" },
            pickLabel?.let { "digiKam:PickLabel=\"$it\"" },
            "xmp:CreatorTool=\"$creatorTool\"",
        ).joinToString("\n    ")
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="$creatorTool">
            | <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            |  <rdf:Description rdf:about=""
            |    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
            |    xmlns:digiKam="http://www.digikam.org/ns/1.0/"
            |    $attributes/>
            | </rdf:RDF>
            |</x:xmpmeta>
            |
        """.trimMargin()
    }

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private fun ascii(tag: Int, s: String): Entry {
        val bytes = (s + "\u0000").toByteArray(Charsets.ISO_8859_1)
        return Entry(tag, TYPE_ASCII, bytes.size, bytes)
    }

    private fun short(tag: Int, v: Int) = Entry(tag, TYPE_SHORT, 1, le(2) { putShort(v.toShort()) })

    private fun long(tag: Int, v: Int) = Entry(tag, TYPE_LONG, 1, le(4) { putInt(v) })

    private fun rational(tag: Int, n: Int, d: Int) = Entry(tag, TYPE_RATIONAL, 1, le(8) { putInt(n).putInt(d) })

    private fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

    @Suppress("MagicNumber")
    private fun tiff(exif: SyntheticExif, preview: ByteArray?): ByteArray {
        val exifEntries = listOf(
            rational(0x829A, 1, exif.exposureDenominator),
            rational(0x829D, exif.fNumberTenths, 10),
            short(0x8827, exif.iso),
            ascii(0x9003, exif.dateTime),
            rational(0x920A, exif.focalLengthMm, 1),
            ascii(0x9291, exif.subSecond),
            long(0xA002, exif.width),
            long(0xA003, exif.height),
            ascii(0xA434, exif.lens),
        )
        // Layout: header (8) | IFD0 | EXIF IFD | values of both | preview.
        val ifd0Count = if (preview != null) 6 else 4
        val ifd0Size = 2 + ifd0Count * 12 + 4
        val exifOffset = 8 + ifd0Size
        val exifSize = 2 + exifEntries.size * 12 + 4
        var dataOffset = exifOffset + exifSize
        val ifd0Base = listOf(ascii(0x010F, exif.make), ascii(0x0110, exif.model), short(0x0112, exif.orientation))
        val valuesSize = (ifd0Base + exifEntries).sumOf {
            if (it.data.size >
                4
            ) {
                it.data.size + (it.data.size and 1)
            } else {
                0
            }
        }
        val previewOffset = dataOffset + valuesSize
        val ifd0 = buildList {
            addAll(ifd0Base)
            if (preview != null) {
                add(long(0x0201, previewOffset))
                add(long(0x0202, preview.size))
            }
            add(long(0x8769, exifOffset))
        }.sortedBy { it.tag }
        val out = ByteBuffer.allocate(previewOffset + (preview?.size ?: 0)).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        val values = ByteArrayOutputStream()
        fun writeIfd(entries: List<Entry>) {
            out.putShort(entries.size.toShort())
            for (e in entries) {
                out.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
                if (e.data.size <= 4) {
                    out.put(e.data.copyOf(4))
                } else {
                    out.putInt(dataOffset + values.size())
                    values.write(e.data)
                    if (e.data.size and 1 == 1) values.write(0)
                }
            }
            out.putInt(0)
        }
        writeIfd(ifd0)
        writeIfd(exifEntries)
        out.put(values.toByteArray())
        dataOffset = out.position()
        check(dataOffset == previewOffset) { "layout $dataOffset != $previewOffset" }
        preview?.let(out::put)
        return out.array()
    }
}
