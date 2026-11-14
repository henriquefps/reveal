package dev.hfps.image.write

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.format.ExifField
import java.awt.color.ColorSpace
import java.awt.color.ICC_ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The PNG and TIFF writers and the ICC profiles (docs/adr/0009 "Formats"), read back by the JDK's own decoders. */
class ImageWritersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A 16-bit gradient with a distinct value in every channel of every pixel. */
    private class Gradient(override val width: Int, override val height: Int, override val bits: Int = 16) : RgbRows {
        private val max = (1 shl bits) - 1

        fun value(x: Int, y: Int, c: Int): Int = ((x * 997 + y * 7919 + c * 31337) * 13) % (max + 1)

        override fun row(y: Int, out: IntArray) {
            for (x in 0 until width) for (c in 0 until 3) out[x * 3 + c] = value(x, y, c)
        }
    }

    @Test
    fun `the ICC profiles parse and map their primaries like the colour maths`() {
        for (profile in OutputProfile.entries) {
            val bytes = IccProfiles.of(profile)
            assertThat(ByteBuffer.wrap(bytes).int).isEqualTo(bytes.size)
            assertThat(String(bytes, 36, 4)).isEqualTo("acsp")
            val icc = ICC_Profile.getInstance(bytes)
            assertThat(icc.majorVersion).isEqualTo(4)
            val space = ICC_ColorSpace(icc)
            // Pure red to PCS XYZ (D50) equals the colorant column.
            val xyz = space.toCIEXYZ(floatArrayOf(1f, 0f, 0f))
            val m = IccProfiles.colorants(profile)
            assertThat(xyz[0].toDouble()).isWithin(2e-3).of(m[0])
            assertThat(xyz[1].toDouble()).isWithin(2e-3).of(m[3])
            assertThat(xyz[2].toDouble()).isWithin(2e-3).of(m[6])
            // White maps to D50.
            val white = space.toCIEXYZ(floatArrayOf(1f, 1f, 1f))
            assertThat(white[1].toDouble()).isWithin(2e-3).of(1.0)
        }
        // sRGB's colorants are the well-known D50 matrix (ICC sRGB profiles: 0.4361 0.2225 0.0139 for red).
        val srgb = IccProfiles.colorants(OutputProfile.SRGB)
        assertThat(srgb[0]).isWithin(5e-4).of(0.4361)
        assertThat(srgb[3]).isWithin(5e-4).of(0.2225)
        assertThat(srgb[6]).isWithin(5e-4).of(0.0139)
        // Display P3 red is outside sRGB: converting it to the JDK's sRGB clips a channel.
        val p3 = ICC_ColorSpace(ICC_Profile.getInstance(IccProfiles.of(OutputProfile.DISPLAY_P3)))
        val inSrgb = ColorSpace.getInstance(ColorSpace.CS_sRGB).fromCIEXYZ(p3.toCIEXYZ(floatArrayOf(1f, 0f, 0f)))
        assertThat(inSrgb[1]).isAtMost(0.01f)
    }

    @Test
    fun `a 16-bit PNG decodes to the same samples with its ICC, EXIF and XMP chunks`() {
        val rows = Gradient(37, 23)
        val exif = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val xmp = "<x:xmpmeta xmlns:x='adobe:ns:meta/'>Ünïcode</x:xmpmeta>"
        val icc = IccProfiles.of(OutputProfile.DISPLAY_P3)
        val out = ByteArrayOutputStream()
        PngWriter.write(rows, ImageMetadata(icc, exif, xmp), out)
        val bytes = out.toByteArray()
        val chunks = chunks(bytes)
        assertThat(chunks.map { it.first }).containsAtLeast("IHDR", "iCCP", "eXIf", "iTXt", "IDAT", "IEND").inOrder()
        assertThat(chunks.first { it.first == "eXIf" }.second).isEqualTo(exif)
        assertThat(String(chunks.first { it.first == "iTXt" }.second, Charsets.UTF_8)).endsWith(xmp)
        val iccp = chunks.first { it.first == "iCCP" }.second
        val z = iccp.copyOfRange(iccp.indexOf(0) + 2, iccp.size)
        assertThat(inflate(z)).isEqualTo(icc)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        val raster = image.raster
        assertThat(raster.sampleModel.getSampleSize(0)).isEqualTo(16)
        for (y in 0 until rows.height) {
            for (x in 0 until rows.width) {
                for (c in 0 until 3) assertThat(raster.getSample(x, y, c)).isEqualTo(rows.value(x, y, c))
            }
        }
    }

    @Test
    fun `an 8-bit PNG decodes to the same samples`() {
        val rows = Gradient(64, 9, bits = 8)
        val out = ByteArrayOutputStream()
        PngWriter.write(rows, ImageMetadata(IccProfiles.of(OutputProfile.SRGB)), out)
        val raster = ImageIO.read(ByteArrayInputStream(out.toByteArray())).raster
        for (y in 0 until rows.height) {
            for (x in 0 until rows.width) {
                for (c in 0 until 3) assertThat(raster.getSample(x, y, c)).isEqualTo(rows.value(x, y, c))
            }
        }
    }

    @Test
    fun `a 16-bit TIFF decodes to the same samples, with its tags in place`() {
        // Tall enough for several strips.
        val rows = Gradient(300, 700)
        val target = tmp.newFile("out.tif")
        val icc = IccProfiles.of(OutputProfile.SRGB)
        val artist = ascii(0x013B, "A. Photographer")
        val make = ascii(0x010F, "Sony")
        val positioning = ExifField(0x0213, TiffWriter.TYPE_SHORT, 1, byteArrayOf(0, 1))
        val exposure =
            ExifField(0x829A, TiffWriter.TYPE_RATIONAL, 1, ByteBuffer.allocate(8).putInt(1).putInt(250).array())
        val components = ExifField(0x9101, TiffWriter.TYPE_UNDEFINED, 4, byteArrayOf(1, 2, 3, 0))
        val gps = ExifField(0x0001, TiffWriter.TYPE_ASCII, 2, byteArrayOf('N'.code.toByte(), 0))
        TiffWriter.write(
            rows,
            TiffFields(
                ifd0 = listOf(make, artist, positioning),
                exif = listOf(exposure, components),
                gps = listOf(gps),
            ),
            ImageMetadata(icc = icc, xmp = "<x:xmpmeta/>"),
            target,
        )
        val samples = decode16(target, rows.width, rows.height)
        for (y in 0 until rows.height) {
            for (x in 0 until rows.width) {
                for (c in 0 until 3) assertThat(samples[(y * rows.width + x) * 3 + c]).isEqualTo(rows.value(x, y, c))
            }
        }
        val tags = readTags(target)
        assertThat(tags.keys).containsAtLeast(
            TiffWriter.TAG_ICC,
            TiffWriter.TAG_XMP,
            TiffWriter.TAG_EXIF_IFD,
            TiffWriter.TAG_GPS_IFD,
            0x013B,
            0x010F,
        )
        assertThat(tags.keys).doesNotContain(0x0213)
        // The EXIF IFD: FlashpixVersion added, the JPEG-only components configuration dropped.
        val exifTags = readTags(target, tags.getValue(TiffWriter.TAG_EXIF_IFD).toInt())
        assertThat(exifTags.keys).containsAtLeast(0x829A, 0xA000)
        assertThat(exifTags.keys).doesNotContain(0x9101)
        assertThat(tags[TiffWriter.TAG_COMPRESSION]).isEqualTo(TiffWriter.COMPRESSION_DEFLATE.toLong())
        assertThat(tags[TiffWriter.TAG_PREDICTOR]).isEqualTo(TiffWriter.PREDICTOR_HORIZONTAL.toLong())
        // The deflated predicted data is smaller than the raw samples (gradients compress).
        assertThat(target.length()).isLessThan(300L * 700 * 6)
    }

    @Test
    fun `the 16-bit raster stores encoded floats rounded to the nearest level`() {
        val raster = RgbRaster(4, 3, 16, File(tmp.root, "raster"))
        raster.use {
            val band = FloatArray(2 * 2 * 4) { i -> if (i % 4 == 3) 1f else (i / 4) / 3f }
            it.putEncoded(1, 1, 2, 2, java.nio.FloatBuffer.wrap(band))
            assertThat(it.sample(1, 1, 0)).isEqualTo(0)
            assertThat(it.sample(2, 1, 0)).isEqualTo((65535 / 3f + 0.5f).toInt())
            assertThat(it.sample(2, 2, 2)).isEqualTo(65535)
            assertThat(it.sample(0, 0, 0)).isEqualTo(0)
            val row = IntArray(12)
            Rgb8View(it).row(2, row)
            assertThat(row[2 * 3]).isEqualTo(255)
            val part = IntArray(6)
            it.row(1, 1, 2, part)
            assertThat(part[3]).isEqualTo(it.sample(2, 1, 0))
        }
        RgbRaster(3, 2, 8, File(tmp.root, "raster8")).use {
            val rgba = java.nio.ByteBuffer.wrap(byteArrayOf(10, 20, 30, -1, 40, 50, 60, -1))
            it.putRgba8(1, 1, 2, 1, rgba)
            assertThat(it.sample(2, 1, 2)).isEqualTo(60)
            val row = IntArray(9)
            it.row(1, row)
            assertThat(row.toList()).containsExactly(0, 0, 0, 10, 20, 30, 40, 50, 60).inOrder()
        }
    }

    private fun ascii(tag: Int, text: String): ExifField {
        val b = text.toByteArray() + 0
        return ExifField(tag, TiffWriter.TYPE_ASCII, b.size, b)
    }

    /** IFD0's tags with their first value (SHORT/LONG inline), big-endian. */
    private fun readTags(file: File, at: Int? = null): Map<Int, Long> {
        val b = ByteBuffer.wrap(file.readBytes())
        val ifd = at ?: b.getInt(4)
        val n = b.getShort(ifd).toInt()
        val out = HashMap<Int, Long>()
        for (i in 0 until n) {
            val at = ifd + 2 + i * 12
            val tag = b.getShort(at).toInt() and 0xFFFF
            val type = b.getShort(at + 2).toInt()
            out[tag] = if (type == TiffWriter.TYPE_SHORT) {
                (b.getShort(at + 8).toInt() and 0xFFFF).toLong()
            } else {
                b.getInt(at + 8).toLong()
            }
        }
        return out
    }

    /** A minimal reader of what the writer makes: deflated strips, horizontal predictor, 16-bit big-endian RGB. */
    @Suppress("NestedBlockDepth")
    private fun decode16(file: File, width: Int, height: Int): IntArray {
        val b = ByteBuffer.wrap(file.readBytes())
        val ifd = b.getInt(4)
        fun entry(tag: Int): Pair<Int, Int> {
            val n = b.getShort(ifd).toInt()
            for (i in 0 until n) {
                val at = ifd + 2 + i * 12
                if ((b.getShort(at).toInt() and 0xFFFF) == tag) return b.getInt(at + 4) to b.getInt(at + 8)
            }
            error("tag $tag missing")
        }
        fun longs(tag: Int): IntArray {
            val (count, value) = entry(tag)
            return if (count == 1) intArrayOf(value) else IntArray(count) { b.getInt(value + it * 4) }
        }
        val offsets = longs(TiffWriter.TAG_STRIP_OFFSETS)
        val counts = longs(TiffWriter.TAG_STRIP_BYTES)
        val rowsPerStrip = entry(TiffWriter.TAG_ROWS_PER_STRIP).second
        assertThat(offsets.size).isGreaterThan(1)
        val out = IntArray(width * height * 3)
        for (s in offsets.indices) {
            val raw = inflate(file.readBytes().copyOfRange(offsets[s], offsets[s] + counts[s]))
            val rowsHere = raw.size / (width * 6)
            for (r in 0 until rowsHere) {
                val y = s * rowsPerStrip + r
                for (i in 0 until width * 3) {
                    val v = ((raw[(r * width * 3 + i) * 2].toInt() and 0xFF) shl 8) or
                        (raw[(r * width * 3 + i) * 2 + 1].toInt() and 0xFF)
                    out[y * width * 3 + i] = if (i >= 3) (v + out[y * width * 3 + i - 3]) and 0xFFFF else v
                }
            }
        }
        return out
    }

    private fun chunks(png: ByteArray): List<Pair<String, ByteArray>> {
        val input = DataInputStream(ByteArrayInputStream(png, 8, png.size - 8))
        val out = ArrayList<Pair<String, ByteArray>>()
        while (input.available() > 0) {
            val length = input.readInt()
            val type = ByteArray(4).also(input::readFully)
            val data = ByteArray(length).also(input::readFully)
            val crc = input.readInt()
            val check = CRC32().apply {
                update(type)
                update(data)
            }
            assertThat(crc).isEqualTo(check.value.toInt())
            out += String(type) to data
        }
        return out
    }

    private fun inflate(z: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(z)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) out.write(buf, 0, inflater.inflate(buf))
        inflater.end()
        return out.toByteArray()
    }
}
