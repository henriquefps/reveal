@file:Suppress("MagicNumber")

package dev.hfps.reveal.feature.export.metadata

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Rewrites the header of a JPEG stream (docs/adr/0009): SOI, then the EXIF APP1, the XMP APP1, then the encoder's
 * segments except its JFIF APP0 and any APP1 (the ICC APP2 and everything else are kept as they are), then the
 * entropy-coded data copied unchanged. Streams: the image data is never held in memory.
 */
object JpegSegments {
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val MAX_SEGMENT = 0xFFFF - 2
    private val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()

    /** Copies [jpeg] to [out] with [exif] (a full `Exif\0\0` payload) and [xmp] (packet text) in front. */
    fun rewrite(jpeg: InputStream, out: OutputStream, exif: ByteArray?, xmp: String?) {
        val input = DataInputStream(jpeg.buffered())
        if (input.readUnsignedByte() != MARKER || input.readUnsignedByte() != SOI) throw IOException("not a JPEG")
        out.write(MARKER)
        out.write(SOI)
        exif?.let { segment(out, APP1, it) }
        xmp?.let { segment(out, APP1, XMP_NS + it.toByteArray()) }
        while (true) {
            var marker = input.readUnsignedByte()
            if (marker != MARKER) throw IOException("bad JPEG marker")
            do {
                marker = input.readUnsignedByte()
            } while (marker == MARKER)
            if (marker == SOS) {
                out.write(MARKER)
                out.write(SOS)
                input.copyTo(out)
                return
            }
            val length = input.readUnsignedShort()
            val body = ByteArray(length - 2)
            input.readFully(body)
            if (marker == APP0 || marker == APP1) continue
            out.write(MARKER)
            out.write(marker)
            out.write(length shr 8)
            out.write(length and 0xFF)
            out.write(body)
        }
    }

    /** True when [payload] fits one segment. */
    fun fits(payload: ByteArray): Boolean = payload.size <= MAX_SEGMENT

    private fun segment(out: OutputStream, marker: Int, payload: ByteArray) {
        if (!fits(payload)) throw IOException("metadata segment too large: ${payload.size} bytes")
        val length = payload.size + 2
        out.write(MARKER)
        out.write(marker)
        out.write(length shr 8)
        out.write(length and 0xFF)
        out.write(payload)
    }
}
