package dev.hfps.image.write

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Test

/**
 * hvcC / av1C from real encoder output (fixtures made by ffmpeg with x265 and libaom: 64 x 64 test patterns) and the
 * HEIF/AVIF container. The files are also written to `build/heif-samples/` for a decode check with libavif and
 * ffmpeg on the host (docs/adr/0009 "HEIF and AVIF").
 */
class HeifContainerTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("codec/$name")).use { it.readBytes() }

    @Test
    fun `hvcC carries the SPS's profile, level, chroma and bit depth`() {
        for ((name, depth, profile) in listOf(
            // x265 marks a single frame Main Still Picture (4, 8-bit) or RExt (4, the Main 10 Still profile).
            Triple("hevc-main8-64.hevc", 8, 4),
            Triple("hevc-main10-64.hevc", 10, 4),
        )) {
            val stream = fixture(name)
            val nals = HevcConfig.nalUnits(stream)
            assertThat(nals.map(HevcConfig::type)).containsAtLeast(32, 33, 34)
            val sps = HevcConfig.parseSps(nals.first { HevcConfig.type(it) == 33 })
            assertThat(sps.width).isEqualTo(64)
            assertThat(sps.height).isEqualTo(64)
            assertThat(sps.chromaFormat).isEqualTo(1)
            assertThat(sps.bitDepthLuma).isEqualTo(depth)
            assertThat(sps.profileIdc).isEqualTo(profile)
            val hvcC = HevcConfig.hvcC(stream)
            assertThat(hvcC[0].toInt()).isEqualTo(1)
            assertThat(hvcC[1].toInt() and 0x1F).isEqualTo(profile)
            assertThat(hvcC[16].toInt() and 3).isEqualTo(1) // chroma 4:2:0
            assertThat(hvcC[17].toInt() and 7).isEqualTo(depth - 8)
            assertThat(hvcC[22].toInt()).isEqualTo(3) // three arrays
            // The sample keeps the slice NALs, length-prefixed, without parameter sets.
            val sample = HevcConfig.sample(stream)
            val first = ByteBuffer.wrap(sample).int
            assertThat(HevcConfig.type(sample.copyOfRange(4, 4 + first))).isNotIn(32..34)
        }
    }

    @Test
    fun `av1C comes from the sequence header`() {
        val stream = fixture("av1-main10-64.obu")
        val obus = Av1Config.obus(stream)
        assertThat(obus.first().type).isEqualTo(Av1Config.OBU_TEMPORAL_DELIMITER)
        val info = Av1Config.parseSequenceHeader(obus.first { it.type == Av1Config.OBU_SEQUENCE_HEADER })
        assertThat(info.profile).isEqualTo(0)
        assertThat(info.bitDepth).isEqualTo(10)
        assertThat(info.subsamplingX).isEqualTo(1)
        assertThat(info.subsamplingY).isEqualTo(1)
        val av1C = Av1Config.av1C(stream)
        assertThat(av1C[0].toInt() and 0xFF).isEqualTo(0x81)
        assertThat((av1C[2].toInt() ushr 6) and 1).isEqualTo(1) // high_bitdepth
        assertThat(Av1Config.av1C(av1C)).isEqualTo(av1C)
        assertThat(Av1Config.obus(Av1Config.sample(stream)).map { it.type })
            .doesNotContain(Av1Config.OBU_TEMPORAL_DELIMITER)
    }

    @Test
    fun `a two-tile grid with ICC, Exif and XMP is laid out as HEIF and AVIF`() {
        val out = File("build/heif-samples").apply { mkdirs() }
        val exif = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val xmp = "<x:xmpmeta xmlns:x='adobe:ns:meta/'/>"
        val colour = ColourInfo.of(OutputProfile.DISPLAY_P3)
        for ((codec, name) in listOf(ImageCodec.HEVC to "hevc-main10-64.hevc", ImageCodec.AV1 to "av1-main10-64.obu")) {
            val stream = fixture(name)
            val config = if (codec == ImageCodec.HEVC) HevcConfig.hvcC(stream) else Av1Config.av1C(stream)
            val tile = if (codec == ImageCodec.HEVC) HevcConfig.sample(stream) else Av1Config.sample(stream)
            val image = CodedImage(codec, config, 120, 64, 64, 64, 2, 1, 10, listOf(tile, tile))
            val bytes = ByteArrayOutputStream().also { HeifContainer.write(image, colour, exif, xmp, it) }.toByteArray()
            File(out, "grid-p3." + if (codec == ImageCodec.HEVC) "heic" else "avif").writeBytes(bytes)
            val top = boxes(bytes, 0, bytes.size)
            assertThat(top.map { it.first }).containsExactly("ftyp", "meta", "mdat").inOrder()
            assertThat(String(bytes, 8, 4)).isEqualTo(codec.brand)
            val text = String(bytes, Charsets.ISO_8859_1)
            val boxes = listOf("hdlr", "pitm", "iinf", "iref", "dimg", "cdsc", "iprp", "ipco", "ipma", "iloc", "pixi")
            for (type in boxes) {
                assertThat(text).contains(type)
            }
            assertThat(text).contains("nclx")
            assertThat(text).contains("prof")
            assertThat(text).contains(codec.configBox)
            assertThat(text).contains("application/rdf+xml")
            // nclx: primaries 12 (P3), transfer 13, matrix 1, full range.
            val n = text.indexOf("nclx") + 4
            assertThat(ByteBuffer.wrap(bytes, n, 7).short.toInt()).isEqualTo(12)
            assertThat(ByteBuffer.wrap(bytes, n + 2, 2).short.toInt()).isEqualTo(13)
            assertThat(bytes[n + 6].toInt() and 0x80).isEqualTo(0x80)
            // The ICC profile is embedded whole.
            val icc = IccProfiles.of(OutputProfile.DISPLAY_P3)
            val p = text.indexOf("prof") + 4
            assertThat(bytes.copyOfRange(p, p + icc.size)).isEqualTo(icc)
            // iloc points at the tiles and the Exif item inside mdat.
            val mdat = top.first { it.first == "mdat" }
            val tileAt = indexOf(bytes, tile, mdat.second)
            assertThat(tileAt).isGreaterThan(mdat.second)
            assertThat(indexOf(bytes, exif, mdat.second)).isGreaterThan(tileAt)
        }
    }

    /** (type, start, size) of the boxes in [from, to). */
    private fun boxes(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var at = from
        while (at < to) {
            val size = ByteBuffer.wrap(b, at, 4).int
            out += Triple(String(b, at + 4, 4), at, size)
            at += size
        }
        assertThat(at).isEqualTo(to)
        return out
    }

    private fun indexOf(b: ByteArray, part: ByteArray, from: Int): Int {
        outer@ for (i in from..b.size - part.size) {
            for (j in part.indices) if (b[i + j] != part[j]) continue@outer
            return i
        }
        return -1
    }
}
