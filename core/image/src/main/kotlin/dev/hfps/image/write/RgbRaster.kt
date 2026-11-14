// Sample sizes (bytes per sample, bits) are the formats' constants.
@file:Suppress("MagicNumber")

package dev.hfps.image.write

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** An RGB image read row by row by the encoders: samples are `0..2^bits - 1`, `width * 3` per row. */
interface RgbRows {
    val width: Int
    val height: Int

    /** 8 or 16. */
    val bits: Int

    /** Row [y] (top first) into [out] (`width * 3` samples, R G B per pixel). */
    fun row(y: Int, out: IntArray)

    /** Pixels [x] until `x + w` of row [y] into [out] (`w * 3` samples). */
    fun row(y: Int, x: Int, w: Int, out: IntArray) {
        val full = IntArray(width * 3)
        row(y, full)
        System.arraycopy(full, x * 3, out, 0, w * 3)
    }
}

/**
 * The developed export as 8- or 16-bit RGB in a memory-mapped file (docs/adr/0009 "High bit depth"): written band by
 * band by the tiled render (any order), read row by row by the encoders. The pages live in the page cache, never on
 * the Java heap (a 24 MP export is 144 MB at 16 bits, a 61 MP one 366 MB). [close] deletes the file; the mapping is
 * released with the buffer (the platform has no explicit unmap).
 */
class RgbRaster(override val width: Int, override val height: Int, override val bits: Int, private val file: File) :
    RgbRows,
    Closeable {
    private val sampleBytes = bits / 8
    private val pixelBytes = sampleBytes * 3
    private val channel: FileChannel
    private val buffer: MappedByteBuffer

    init {
        require(width > 0 && height > 0) { "empty raster" }
        require(bits == 8 || bits == BITS) { "8 or 16 bits" }
        val bytes = width.toLong() * height * pixelBytes
        require(bytes <= Int.MAX_VALUE) { "raster of $bytes bytes is too large" }
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(bytes)
        channel = raf.channel
        buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
        buffer.order(ByteOrder.nativeOrder())
    }

    /**
     * Stores encoded RGBA floats in [0, 1] (`w * 4` per row, [h] rows, alpha ignored) at ([x], [y]) as 16-bit,
     * rounded to the nearest level (16-bit rasters).
     */
    fun putEncoded(x: Int, y: Int, w: Int, h: Int, rgba: FloatBuffer) {
        check(bits == BITS) { "putEncoded needs a 16-bit raster" }
        // Bulk copies (one row in, one row out): per-sample buffer calls cost seconds at 24 MP.
        val floats = rowFloats.takeIf { it.size >= w * RGBA } ?: FloatArray(w * RGBA).also { rowFloats = it }
        val shorts = rowShorts.takeIf { it.size >= w * 3 } ?: ShortArray(w * 3).also { rowShorts = it }
        val target = buffer.duplicate().order(buffer.order()).asShortBuffer()
        for (r in 0 until h) {
            rgba.position(r * w * RGBA)
            rgba.get(floats, 0, w * RGBA)
            var i = 0
            for (c in 0 until w * 3 step 3) {
                shorts[c] = level(floats[i])
                shorts[c + 1] = level(floats[i + 1])
                shorts[c + 2] = level(floats[i + 2])
                i += RGBA
            }
            target.position(((y + r) * width + x) * 3)
            target.put(shorts, 0, w * 3)
        }
        rgba.position(0)
    }

    private var rowFloats = FloatArray(0)
    private var rowShorts = ShortArray(0)

    /** Stores 8-bit RGBA bytes (`w * 4` per row, alpha ignored) at ([x], [y]) (8-bit rasters). */
    fun putRgba8(x: Int, y: Int, w: Int, h: Int, rgba: ByteBuffer) {
        check(bits == 8) { "putRgba8 needs an 8-bit raster" }
        for (r in 0 until h) {
            var at = ((y + r).toLong() * width + x).toInt() * pixelBytes
            var i = r * w * RGBA
            for (c in 0 until w) {
                buffer.put(at, rgba.get(i))
                buffer.put(at + 1, rgba.get(i + 1))
                buffer.put(at + 2, rgba.get(i + 2))
                at += pixelBytes
                i += RGBA
            }
        }
    }

    /** Stores samples (`w * 3` per row) at ([x], [y]). */
    fun put(x: Int, y: Int, w: Int, h: Int, rgb: IntArray) {
        for (r in 0 until h) {
            var at = ((y + r).toLong() * width + x).toInt() * pixelBytes
            for (i in r * w * 3 until (r + 1) * w * 3) {
                if (sampleBytes == 2) buffer.putShort(at, rgb[i].toShort()) else buffer.put(at, rgb[i].toByte())
                at += sampleBytes
            }
        }
    }

    override fun row(y: Int, out: IntArray) = row(y, 0, width, out)

    override fun row(y: Int, x: Int, w: Int, out: IntArray) {
        val start = (y * width + x) * 3
        if (sampleBytes == 2) {
            val shorts = readShorts.takeIf { it.size >= w * 3 } ?: ShortArray(w * 3).also { readShorts = it }
            val source = buffer.duplicate().order(buffer.order()).asShortBuffer()
            source.position(start)
            source.get(shorts, 0, w * 3)
            for (i in 0 until w * 3) out[i] = shorts[i].toInt() and MAX
        } else {
            val bytes = readBytes.takeIf { it.size >= w * 3 } ?: ByteArray(w * 3).also { readBytes = it }
            val source = buffer.duplicate()
            source.position(start)
            source.get(bytes, 0, w * 3)
            for (i in 0 until w * 3) out[i] = bytes[i].toInt() and MAX8
        }
    }

    private var readShorts = ShortArray(0)
    private var readBytes = ByteArray(0)

    /** Pixel ([x], [y]) channel [c] (tests). */
    fun sample(x: Int, y: Int, c: Int): Int {
        val at = (y * width + x) * pixelBytes + c * sampleBytes
        return if (sampleBytes == 2) buffer.getShort(at).toInt() and MAX else buffer.get(at).toInt() and MAX8
    }

    override fun close() {
        channel.close()
        file.delete()
    }

    companion object {
        const val BITS = 16
        const val MAX = 0xFFFF
        private const val MAX8 = 0xFF
        private const val RGBA = 4

        /** The 16-bit level of an encoded value in [0, 1]. */
        fun level(v: Float): Short = (v.coerceIn(0f, 1f) * MAX + 0.5f).toInt().toShort()
    }
}

/** A view of [source] at 8 bits (`round(v * 255 / 65535)`); 8-bit sources pass through. */
class Rgb8View(private val source: RgbRows) : RgbRows {
    override val width: Int get() = source.width
    override val height: Int get() = source.height
    override val bits: Int get() = BITS

    override fun row(y: Int, out: IntArray) {
        source.row(y, out)
        if (source.bits == BITS) return
        val max = (1 shl source.bits) - 1
        for (i in 0 until width * 3) out[i] = (out[i] * MAX8 + max / 2) / max
    }

    private companion object {
        const val BITS = 8
        const val MAX8 = 255
    }
}
