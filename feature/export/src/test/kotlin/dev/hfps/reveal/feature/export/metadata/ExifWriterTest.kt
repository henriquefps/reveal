package dev.hfps.reveal.feature.export.metadata

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.format.ExifSnapshot
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.image.source.ByteArraySource
import dev.hfps.image.tiff.Rational
import dev.hfps.image.tiff.Tags
import dev.hfps.image.tiff.TiffReader
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.MetadataLevel
import dev.hfps.reveal.feature.export.model.MetadataOptions
import org.junit.Test

class ExifWriterTest {
    private val gpsLatitude = ExifWriter.rational(0x0002, 4807, 100)
    private val snapshot = ExifSnapshot(
        ifd0 = listOf(
            ExifWriter.ascii(Tags.MAKE, "SONY"),
            ExifWriter.ascii(Tags.MODEL, "ZV-E10"),
            ExifWriter.ascii(Tags.DATE_TIME, "2026:08:07 14:31:00"),
            ExifWriter.ascii(ExifWriter.TAG_ARTIST, "Camera Owner"),
        ),
        exif = listOf(
            ExifWriter.rational(Tags.EXPOSURE_TIME, 1, 250),
            ExifWriter.ascii(Tags.DATE_TIME_ORIGINAL, "2026:08:07 14:30:12"),
            ExifWriter.ascii(Tags.OFFSET_TIME_ORIGINAL, "-03:00"),
            ExifWriter.ascii(Tags.LENS_MAKE, "Sony"),
        ),
        gps = listOf(ExifWriter.ascii(0x0001, "N"), gpsLatitude),
    )
    private val source = SourceMetadata(snapshot, null, rating = 4)
    private val facts = OutputFacts(2048, 1365, ExportColour.SRGB, "Reveal 1.0")

    private fun read(tiff: ByteArray): Triple<TiffReader, Map<Int, Any?>, Map<Int, Any?>> {
        val reader = TiffReader.open(ByteArraySource(tiff), 0)
        val ifd0 = checkNotNull(reader.ifd(reader.firstIfd))
        val exif = checkNotNull(reader.ifd(checkNotNull(reader.int(ifd0[ExifWriter.TAG_EXIF_IFD]))))
        return Triple(reader, ifd0.entries, exif.entries)
    }

    @Test
    fun `all keeps camera, exposure, gps, rating and writes orientation 1 and the output facts`() {
        val tiff =
            checkNotNull(ExifWriter.build(source, facts, MetadataOptions(), Authorship("Ana Example", "© 2026 Ana")))
        val reader = TiffReader.open(ByteArraySource(tiff), 0)
        val ifd0 = checkNotNull(reader.ifd(reader.firstIfd))
        assertThat(reader.string(ifd0[Tags.MAKE])).isEqualTo("SONY")
        assertThat(reader.int(ifd0[Tags.ORIENTATION])).isEqualTo(1)
        assertThat(reader.int(ifd0[Tags.RATING])).isEqualTo(4)
        // Settings win over the file's artist; EXIF is ASCII, so the copyright sign is spelled out.
        assertThat(reader.string(ifd0[ExifWriter.TAG_ARTIST])).isEqualTo("Ana Example")
        assertThat(reader.string(ifd0[ExifWriter.TAG_COPYRIGHT])).isEqualTo("(C) 2026 Ana")
        assertThat(reader.string(ifd0[ExifWriter.TAG_SOFTWARE])).isEqualTo("Reveal 1.0")
        val exif = checkNotNull(reader.ifd(checkNotNull(reader.int(ifd0[ExifWriter.TAG_EXIF_IFD]))))
        assertThat(reader.rational(exif[Tags.EXPOSURE_TIME])).isEqualTo(Rational(1, 250))
        assertThat(reader.string(exif[Tags.DATE_TIME_ORIGINAL])).isEqualTo("2026:08:07 14:30:12")
        assertThat(reader.int(exif[ExifWriter.TAG_PIXEL_X])).isEqualTo(2048)
        assertThat(reader.int(exif[ExifWriter.TAG_COLOR_SPACE])).isEqualTo(1)
        val gps = checkNotNull(reader.ifd(checkNotNull(reader.int(ifd0[ExifWriter.TAG_GPS_IFD]))))
        assertThat(reader.rational(gps[0x0002])).isEqualTo(Rational(4807, 100))
    }

    @Test
    fun `remove location drops the gps ifd`() {
        val tiff = checkNotNull(ExifWriter.build(source, facts, MetadataOptions(removeLocation = true), Authorship()))
        val (_, ifd0, _) = read(tiff)
        assertThat(ifd0).doesNotContainKey(ExifWriter.TAG_GPS_IFD)
    }

    @Test
    fun `copyright only keeps author, copyright and capture time, nothing about the camera`() {
        val tiff = checkNotNull(
            ExifWriter.build(source, facts, MetadataOptions(MetadataLevel.COPYRIGHT), Authorship("", "Ana")),
        )
        val (reader, ifd0, exif) = read(tiff)
        assertThat(ifd0).doesNotContainKey(Tags.MAKE)
        assertThat(ifd0).doesNotContainKey(ExifWriter.TAG_GPS_IFD)
        assertThat(ifd0).doesNotContainKey(Tags.RATING)
        // No author in Settings: the file's artist is kept.
        assertThat(
            reader.string(ifd0[ExifWriter.TAG_ARTIST] as dev.hfps.image.tiff.TiffEntry),
        ).isEqualTo("Camera Owner")
        assertThat(exif).containsKey(Tags.DATE_TIME_ORIGINAL)
        assertThat(exif).doesNotContainKey(Tags.EXPOSURE_TIME)
    }

    @Test
    fun `none writes nothing, or only the capture time when kept`() {
        assertThat(
            ExifWriter.build(
                source,
                facts,
                MetadataOptions(MetadataLevel.NONE, keepCaptureTime = false),
                Authorship("A", "B"),
            ),
        )
            .isNull()
        val tiff =
            checkNotNull(ExifWriter.build(source, facts, MetadataOptions(MetadataLevel.NONE), Authorship("A", "B")))
        val (_, ifd0, exif) = read(tiff)
        assertThat(ifd0).doesNotContainKey(ExifWriter.TAG_ARTIST)
        assertThat(ifd0).doesNotContainKey(ExifWriter.TAG_SOFTWARE)
        assertThat(exif).containsKey(Tags.DATE_TIME_ORIGINAL)
    }

    @Test
    fun `capture time can be dropped from all`() {
        val tiff = checkNotNull(ExifWriter.build(source, facts, MetadataOptions(keepCaptureTime = false), Authorship()))
        val (_, ifd0, exif) = read(tiff)
        assertThat(ifd0).doesNotContainKey(Tags.DATE_TIME)
        assertThat(exif).doesNotContainKey(Tags.DATE_TIME_ORIGINAL)
        assertThat(exif).doesNotContainKey(Tags.OFFSET_TIME_ORIGINAL)
        assertThat(exif).containsKey(Tags.EXPOSURE_TIME)
    }

    @Test
    fun `display p3 is uncalibrated colour space`() {
        val tiff =
            checkNotNull(
                ExifWriter.build(source, facts.copy(colour = ExportColour.DISPLAY_P3), MetadataOptions(), Authorship()),
            )
        val reader = TiffReader.open(ByteArraySource(tiff), 0)
        val ifd0 = checkNotNull(reader.ifd(reader.firstIfd))
        val exif = checkNotNull(reader.ifd(checkNotNull(reader.int(ifd0[ExifWriter.TAG_EXIF_IFD]))))
        assertThat(reader.int(exif[ExifWriter.TAG_COLOR_SPACE])).isEqualTo(0xFFFF)
    }

    @Test
    fun `containers without a tiff fall back to the inspected metadata`() {
        val fallback = PhotoMetadata(make = "Canon", model = "EOS R6", captureTime = "2026:01:02 03:04:05", iso = 400)
        val tiff =
            checkNotNull(ExifWriter.build(SourceMetadata(null, fallback), facts, MetadataOptions(), Authorship()))
        val reader = TiffReader.open(ByteArraySource(tiff), 0)
        val ifd0 = checkNotNull(reader.ifd(reader.firstIfd))
        assertThat(reader.string(ifd0[Tags.MODEL])).isEqualTo("EOS R6")
        val exif = checkNotNull(reader.ifd(checkNotNull(reader.int(ifd0[ExifWriter.TAG_EXIF_IFD]))))
        assertThat(reader.int(exif[Tags.ISO])).isEqualTo(400)
        assertThat(reader.string(exif[Tags.DATE_TIME_ORIGINAL])).isEqualTo("2026:01:02 03:04:05")
    }

    @Test
    fun `little endian snapshot values are swapped to big endian`() {
        val le = byteArrayOf(0xFA.toByte(), 0, 0, 0, 1, 0, 0, 0)
        assertThat(
            ExifSnapshot.bigEndian(le, 5, littleEndian = true),
        ).isEqualTo(byteArrayOf(0, 0, 0, 0xFA.toByte(), 0, 0, 0, 1))
        assertThat(ExifSnapshot.bigEndian("ab".toByteArray(), 2, littleEndian = true)).isEqualTo("ab".toByteArray())
    }
}
