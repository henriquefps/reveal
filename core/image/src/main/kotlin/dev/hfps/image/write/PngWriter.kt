// PNG (ISO/IEC 15948, W3C PNG 3rd edition) chunk layout and filter types are the specification's.
@file:Suppress("MagicNumber")

package dev.hfps.image.write

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/** What a PNG or TIFF carries besides its pixels: the ICC profile, the EXIF TIFF stream (big-endian) and XMP. */
class ImageMetadata(val icc: ByteArray? = null, val exifTiff: ByteArray? = null, val xmp: String? = null)

/**
 * A streaming PNG writer (docs/adr/0009 "PNG"): truecolour RGB at 8 or 16 bits, the ICC profile in `iCCP`, EXIF in
 * `eXIf` (the TIFF stream, no `Exif\0\0` header), XMP in an `iTXt` chunk keyed `XML:com.adobe.xmp`; every metadata
 * chunk precedes `IDAT`. Rows are filtered adaptively (the filter of least sum of absolute differences, the usual
 * heuristic) and deflated at [level]. Pure Kotlin, JVM-tested and read back by `ImageIO` and exiftool.
 */
object PngWriter {
    private val SIGNATURE = byteArrayOf(-119, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private const val IDAT_CHUNK = 64 * 1024

    /** Writes [rows] with [metadata] to [out] (not closed); [cancel] is polled every 64 rows. */
    fun write(
        rows: RgbRows,
        metadata: ImageMetadata,
        out: OutputStream,
        level: Int = Deflater.DEFAULT_COMPRESSION,
        cancel: () -> Boolean = { false },
    ) {
        require(rows.bits == 8 || rows.bits == 16) { "PNG writes 8 or 16 bits" }
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream()
        header.writeInt(rows.width)
        header.writeInt(rows.height)
        header.write(rows.bits)
        header.write(2) // truecolour
        header.write(0) // deflate
        header.write(0) // adaptive filtering
        header.write(0) // no interlace
        chunk(out, "IHDR", header.toByteArray())
        metadata.icc?.let { icc ->
            val body = ByteArrayOutputStream()
            body.write("ICC profile".toByteArray(Charsets.ISO_8859_1))
            body.write(0)
            body.write(0) // deflate
            body.write(deflate(icc))
            chunk(out, "iCCP", body.toByteArray())
        }
        metadata.exifTiff?.let { chunk(out, "eXIf", it) }
        metadata.xmp?.let { xmp ->
            val body = ByteArrayOutputStream()
            body.write("XML:com.adobe.xmp".toByteArray(Charsets.ISO_8859_1))
            body.write(byteArrayOf(0, 0, 0, 0, 0)) // keyword end, uncompressed, method, empty language, empty name
            body.write(xmp.toByteArray(Charsets.UTF_8))
            chunk(out, "iTXt", body.toByteArray())
        }
        val idat = ChunkStream(out, "IDAT", IDAT_CHUNK)
        val deflater = Deflater(level)
        try {
            DeflaterOutputStream(idat, deflater, IDAT_CHUNK).use { z -> filterRows(rows, z, cancel) }
        } finally {
            deflater.end()
        }
        chunk(out, "IEND", ByteArray(0))
        out.flush()
    }

    private fun filterRows(rows: RgbRows, z: OutputStream, cancel: () -> Boolean) {
        val bpp = if (rows.bits == 16) 6 else 3
        val stride = rows.width * bpp
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        val samples = IntArray(rows.width * 3)
        val candidates = Array(5) { ByteArray(stride + 1) }
        for (y in 0 until rows.height) {
            if (y % 64 == 0 && cancel()) throw java.util.concurrent.CancellationException("PNG cancelled")
            rows.row(y, samples)
            if (rows.bits == 16) {
                for (i in samples.indices) {
                    cur[2 * i] = (samples[i] shr 8).toByte()
                    cur[2 * i + 1] = samples[i].toByte()
                }
            } else {
                for (i in samples.indices) cur[i] = samples[i].toByte()
            }
            z.write(bestFilter(cur, prev, bpp, candidates))
            val t = prev
            prev = cur
            cur = t
        }
    }

    /** The filtered row (type byte first) of least sum of absolute (signed) bytes. */
    internal fun bestFilter(cur: ByteArray, prev: ByteArray, bpp: Int, candidates: Array<ByteArray>): ByteArray {
        var best = 0
        var bestScore = Long.MAX_VALUE
        for (type in 0 until 5) {
            val f = candidates[type]
            f[0] = type.toByte()
            var score = 0L
            for (i in cur.indices) {
                val x = cur[i].toInt() and 0xFF
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val predicted = when (type) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) ushr 1
                    else -> paeth(a, b, c)
                }
                val v = (x - predicted).toByte()
                f[i + 1] = v
                score += abs(v.toInt())
            }
            if (score < bestScore) {
                bestScore = score
                best = type
            }
        }
        return candidates[best]
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    internal fun chunk(out: OutputStream, type: String, data: ByteArray, length: Int = data.size) {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.writeInt(length)
        out.write(t)
        out.write(data, 0, length)
        val crc = CRC32()
        crc.update(t)
        crc.update(data, 0, length)
        out.writeInt(crc.value.toInt())
    }

    /** Buffers bytes into chunks of [type] of at most [size] bytes. */
    private class ChunkStream(val out: OutputStream, val type: String, size: Int) : OutputStream() {
        private val buffer = ByteArray(size)
        private var n = 0

        override fun write(b: Int) {
            buffer[n++] = b.toByte()
            if (n == buffer.size) flushChunk()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                val k = minOf(left, buffer.size - n)
                System.arraycopy(b, o, buffer, n, k)
                n += k
                o += k
                left -= k
                if (n == buffer.size) flushChunk()
            }
        }

        private fun flushChunk() {
            if (n > 0) chunk(out, type, buffer, n)
            n = 0
        }

        override fun close() = flushChunk()
    }
}

internal fun OutputStream.writeInt(v: Int) {
    write(v ushr 24)
    write(v ushr 16)
    write(v ushr 8)
    write(v)
}
