// Binary format parsing: offsets, tag ids and bit widths are the format; early returns are the validation.
@file:Suppress("MagicNumber", "ReturnCount", "LoopWithTooManyJumpStatements")

package dev.hfps.image.format

import dev.hfps.image.color.SonyLensCorrection
import dev.hfps.image.source.RandomAccessSource
import dev.hfps.image.source.readRange
import dev.hfps.image.tiff.TiffException
import dev.hfps.image.tiff.TiffReader
import java.io.IOException

/**
 * Reads the lens-correction parameters of a Sony ARW from its SR2 private data (what Imaging Edge and darktable's
 * `exiftool` list as `DistortionCorrParams`, `VignettingCorrParams` and `ChromaticAberrationCorrParams`).
 *
 * Layout (verified on a ZV-E10 ARW): IFD0 tag `0xC634` holds the offset of the SR2 private IFD, which has `0x7200`
 * (offset of the SR2 sub-IFD), `0x7201` (its length) and `0x7221` (the decryption key). The sub-IFD is encrypted with
 * Sony's keystream ([decrypt], the algorithm of dcraw's `sony_decrypt`); decrypted it is a bare TIFF IFD (no header)
 * whose value offsets are absolute positions in the file. The three parameter blocks are `SSHORT` arrays:
 *
 * | tag | block | values |
 * | --- | --- | --- |
 * | `0x797D` | vignetting | 17: the knot count `n` (11), `n` shading values, zero padding |
 * | `0x7980` | chromatic aberration | 33: the knot count `2n` (22), `n` red then `n` blue values, zero padding |
 * | `0x7982` | distortion | 17: the knot count `n` (11), `n` distortion values, zero padding |
 *
 * The values are returned exactly as stored; turning them into a correction (they are camera-specific polynomial
 * knots over the image radius) is M2's job.
 */
object SonyLensCorrectionReader {
    private const val TAG_SR2_PRIVATE = 0xC634
    private const val TAG_SUB_IFD_OFFSET = 0x7200
    private const val TAG_SUB_IFD_LENGTH = 0x7201
    private const val TAG_SUB_IFD_KEY = 0x7221
    private const val TAG_VIGNETTING = 0x797D
    private const val TAG_CHROMATIC_ABERRATION = 0x7980
    private const val TAG_DISTORTION = 0x7982
    private const val MAX_BLOCK = 256 * 1024
    private const val MAX_ENTRIES = 1000
    private const val KEYSTREAM_SIZE = 128
    private const val KEY_MULTIPLIER = 48828125
    private const val FIRST_POSITION = 127

    /** The three blocks, or null when [source] is no ARW with SR2 data or carries none. Never throws on bad data. */
    fun read(source: RandomAccessSource): SonyLensCorrection? = try {
        readOrThrow(source)
    } catch (_: TiffException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun readOrThrow(source: RandomAccessSource): SonyLensCorrection? {
        val tiff = TiffReader.open(source)
        val ifd0 = tiff.ifd(tiff.firstIfd) ?: return null
        val pointer = ifd0[TAG_SR2_PRIVATE] ?: return null
        // The pointer is stored as BYTE[4]: the four inline bytes are the offset of the SR2 private IFD.
        val sr2Offset = tiff.u32(source.readRange(pointer.valueOffset, 4), 0)
        val sr2 = tiff.ifd(sr2Offset) ?: return null
        val start = tiff.int(sr2[TAG_SUB_IFD_OFFSET]) ?: return null
        val length = tiff.int(sr2[TAG_SUB_IFD_LENGTH]) ?: return null
        val keyEntry = sr2[TAG_SUB_IFD_KEY] ?: return null
        if (length < 2 || length > MAX_BLOCK || start + length > source.size) return null
        val key = tiff.u32(source.readRange(keyEntry.valueOffset, 4), 0)
        val block = source.readRange(start, length.toInt())
        val decrypted = decrypt(block, key)
        val entries = parseBareIfd(decrypted, start, tiff) ?: return null
        val vignetting = entries[TAG_VIGNETTING]
        val chromatic = entries[TAG_CHROMATIC_ABERRATION]
        val distortion = entries[TAG_DISTORTION]
        if (vignetting == null && chromatic == null && distortion == null) return null
        return SonyLensCorrection(distortion, vignetting, chromatic)
    }

    /**
     * Sony's SR2 keystream cipher (dcraw `sony_decrypt`): symmetric, so it also encrypts. [data] length should be a
     * multiple of 4; a trailing partial word is left as is.
     */
    internal fun decrypt(data: ByteArray, key: Long): ByteArray {
        val pad = LongArray(KEYSTREAM_SIZE)
        var k = key and MASK
        for (p in 0 until 4) {
            k = (k * KEY_MULTIPLIER + 1) and MASK
            pad[p] = k
        }
        pad[3] = ((pad[3] shl 1) or ((pad[0] xor pad[2]) shr 31)) and MASK
        for (p in 4 until KEYSTREAM_SIZE - 1) {
            pad[p] = (((pad[p - 4] xor pad[p - 2]) shl 1) or ((pad[p - 3] xor pad[p - 1]) shr 31)) and MASK
        }
        for (p in 0 until KEYSTREAM_SIZE - 1) pad[p] = swap(pad[p])
        val out = data.copyOf()
        var position = FIRST_POSITION
        var i = 0
        while (i + 4 <= out.size) {
            val next = pad[(position + 1) and (KEYSTREAM_SIZE - 1)] xor pad[(position + 65) and (KEYSTREAM_SIZE - 1)]
            pad[position and (KEYSTREAM_SIZE - 1)] = next
            val word = (out[i].toLong() and 0xFF) or ((out[i + 1].toLong() and 0xFF) shl 8) or
                ((out[i + 2].toLong() and 0xFF) shl 16) or ((out[i + 3].toLong() and 0xFF) shl 24)
            val value = word xor next
            out[i] = value.toByte()
            out[i + 1] = (value shr 8).toByte()
            out[i + 2] = (value shr 16).toByte()
            out[i + 3] = (value shr 24).toByte()
            position++
            i += 4
        }
        return out
    }

    private fun swap(v: Long): Long =
        ((v and 0xFF) shl 24) or ((v and 0xFF00) shl 8) or ((v shr 8) and 0xFF00) or ((v shr 24) and 0xFF)

    /** `SSHORT` arrays of the bare IFD at the start of [block]; offsets are absolute, [blockStart] is subtracted. */
    private fun parseBareIfd(block: ByteArray, blockStart: Long, tiff: TiffReader): Map<Int, List<Int>>? {
        if (block.size < 2) return null
        val count = tiff.u16(block, 0)
        if (count == 0 || count > MAX_ENTRIES || 2 + count * 12 > block.size) return null
        val out = HashMap<Int, List<Int>>()
        for (i in 0 until count) {
            val e = 2 + i * 12
            val tag = tiff.u16(block, e)
            val type = tiff.u16(block, e + 2)
            val n = tiff.u32(block, e + 4)
            if (type != TiffReader.TYPE_SSHORT || n <= 0 || n > MAX_BLOCK) continue
            val bytes = n * 2
            val offset = if (bytes <= 4) e + 8L else tiff.u32(block, e + 8) - blockStart
            if (offset < 0 || offset + bytes > block.size) continue
            out[tag] = List(n.toInt()) { j -> tiff.u16(block, (offset + 2 * j).toInt()).toShort().toInt() }
        }
        return out
    }

    private const val MASK = 0xFFFFFFFFL
}
