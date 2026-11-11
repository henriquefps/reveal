package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.format.SonyLensCorrectionReader
import dev.hfps.image.source.ByteArraySource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class SonyLensCorrectionReaderTest {
    private val vignetting = listOf(11, 0, 0, 128, 352, 736, 1184, 1920, 3200, 4768, 6528, 8384, 0, 0, 0, 0, 0)
    private val chromatic = listOf(22) + List(11) { -512 + it * 100 } + List(11) { 300 - it * 20 } + List(10) { 0 }
    private val distortion = listOf(11, 21, 2, -22, -49, -84, -124, -168, -208, -247, -279, -305, 0, 0, 0, 0, 0)

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * An ARW-shaped file: TIFF header, IFD0 with the SR2 pointer (0xC634), the SR2 private IFD (offset, length, key),
     * and the encrypted sub-IFD at [subStart] whose three SSHORT arrays are addressed by absolute file offsets.
     */
    private fun arw(
        key: Long,
        arrays: Map<Int, List<Int>> = mapOf(
            0x797D to vignetting,
            0x7980 to chromatic,
            0x7982 to distortion,
        ),
    ): ByteArray {
        val sr2Offset = 8 + 2 + 12 + 4
        val subStart = 256
        val tags = arrays.keys.sorted()
        val ifdSize = 2 + tags.size * 12 + 4
        var cursor = subStart + ifdSize
        val sub = le(ifdSize + arrays.values.sumOf { it.size * 2 })
        sub.putShort(tags.size.toShort())
        val data = le(arrays.values.sumOf { it.size * 2 })
        for (tag in tags) {
            val values = arrays.getValue(tag)
            sub.putShort(tag.toShort()).putShort(8).putInt(values.size).putInt(cursor)
            for (v in values) data.putShort(v.toShort())
            cursor += values.size * 2
        }
        sub.putInt(0)
        sub.put(data.array())
        val encrypted = SonyLensCorrectionReader.decrypt(sub.array(), key)

        val file = le(subStart + encrypted.size)
        file.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        file.putShort(1).putShort(0xC634.toShort()).putShort(1).putInt(4).putInt(sr2Offset).putInt(0)
        // SR2 private IFD at sr2Offset
        file.position(sr2Offset)
        file.putShort(3)
        file.putShort(0x7200).putShort(4).putInt(1).putInt(subStart)
        file.putShort(0x7201).putShort(4).putInt(1).putInt(encrypted.size)
        file.putShort(0x7221).putShort(7).putInt(4).putInt(key.toInt())
        file.putInt(0)
        file.position(subStart)
        file.put(encrypted)
        return file.array()
    }

    @Test
    fun `reads the three parameter blocks from an encrypted SR2 sub-IFD`() {
        val result = SonyLensCorrectionReader.read(ByteArraySource(arw(0x44332211L)))!!
        assertThat(result.vignetting).isEqualTo(vignetting)
        assertThat(result.chromaticAberration).isEqualTo(chromatic)
        assertThat(result.distortion).isEqualTo(distortion)
        assertThat(result.distortion!![0]).isEqualTo(11)
    }

    @Test
    fun `a block that is absent stays null`() {
        val result = SonyLensCorrectionReader.read(ByteArraySource(arw(0x01020304L, mapOf(0x7982 to distortion))))!!
        assertThat(result.vignetting).isNull()
        assertThat(result.chromaticAberration).isNull()
        assertThat(result.distortion).isEqualTo(distortion)
    }

    @Test
    fun `the cipher is its own inverse`() {
        val data = ByteArray(64) { (it * 7).toByte() }
        val once = SonyLensCorrectionReader.decrypt(data, 0xDEADBEEFL)
        assertThat(once).isNotEqualTo(data)
        assertThat(SonyLensCorrectionReader.decrypt(once, 0xDEADBEEFL)).isEqualTo(data)
    }

    @Test
    fun `a wrong key or a file without SR2 data gives null and never throws`() {
        val file = arw(0x44332211L)
        // Wrong key: garbage IFD (the entry count is implausible or the tags are not found).
        val wrong = file.copyOf()
        wrong[8 + 2 + 12 + 4 + 2 + 12 + 12 + 8] = 0x55 // key byte inside the 0x7221 entry
        assertThat(SonyLensCorrectionReader.read(ByteArraySource(wrong))).isNull()
        assertThat(SonyLensCorrectionReader.read(ByteArraySource(ByteArray(0)))).isNull()
        assertThat(SonyLensCorrectionReader.read(ByteArraySource(ByteArray(100) { 7 }))).isNull()
        assertThat(SonyLensCorrectionReader.read(ByteArraySource(file.copyOf(120)))).isNull()
    }
}
