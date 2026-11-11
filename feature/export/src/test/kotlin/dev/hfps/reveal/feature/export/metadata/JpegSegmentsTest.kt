package dev.hfps.reveal.feature.export.metadata

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.ByteArraySource
import dev.hfps.image.tiff.JpegMarkers
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.feature.export.model.MetadataLevel
import dev.hfps.reveal.feature.export.model.MetadataOptions
import java.io.ByteArrayOutputStream
import org.junit.Test

class JpegSegmentsTest {
    /** SOI, JFIF APP0, an old APP1, ICC APP2, DQT, SOS + scan data, EOI. */
    private val encoded: ByteArray = bytes(
        0xFF, 0xD8,
        0xFF, 0xE0, 0x00, 0x06, 'J', 'F', 'I', 'F',
        0xFF, 0xE1, 0x00, 0x04, 0x01, 0x02,
        0xFF, 0xE2, 0x00, 0x05, 'I', 'C', 'C',
        0xFF, 0xDB, 0x00, 0x03, 0x07,
        0xFF, 0xDA, 0x00, 0x02, 0x11, 0x22, 0xFF, 0x00, 0x33,
        0xFF, 0xD9,
    )

    @Test
    fun `exif and xmp go first, jfif and old app1 go, icc and the scan stay byte for byte`() {
        val exif = ExifWriter.app1Payload(
            ExifWriter.write(listOf(ExifWriter.short(0x0112, 1)), emptyList(), emptyList()),
        )
        val xmp = XmpPacket.build(MetadataOptions(), Authorship("Ana", "© Ana"), 3, "Reveal")
        val out = ByteArrayOutputStream()
        JpegSegments.rewrite(encoded.inputStream(), out, exif, xmp)
        val result = out.toByteArray()
        assertThat(result.copyOfRange(0, 4)).isEqualTo(bytes(0xFF, 0xD8, 0xFF, 0xE1))
        assertThat(result.copyOfRange(6, 12)).isEqualTo("Exif\u0000\u0000".toByteArray())
        val text = result.decodeToString()
        assertThat(text).doesNotContain("JFIF")
        assertThat(text).contains("ICC")
        assertThat(text).contains("xmp:Rating=\"3\"")
        assertThat(text).contains("<rdf:li xml:lang=\"x-default\">© Ana</rdf:li>")
        val tail = encoded.copyOfRange(encoded.size - 11, encoded.size)
        assertThat(result.copyOfRange(result.size - 11, result.size)).isEqualTo(tail)
        // The image inspector finds the new EXIF and XMP.
        val header = checkNotNull(JpegMarkers.read(ByteArraySource(result), 0))
        assertThat(header.exifTiffOffset).isAtLeast(0L)
    }

    @Test
    fun `xmp of level none is absent and copyright only has no rating`() {
        assertThat(XmpPacket.build(MetadataOptions(MetadataLevel.NONE), Authorship("A", "B"), 5, "R")).isNull()
        assertThat(XmpPacket.build(MetadataOptions(MetadataLevel.COPYRIGHT), Authorship("A", "B"), 5, "R"))
            .doesNotContain("Rating")
    }

    private fun bytes(vararg v: Any): ByteArray =
        v.map { if (it is Char) it.code.toByte() else (it as Int).toByte() }.toByteArray()
}
