package dev.hfps.image.write

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
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
}

/**
 * The developed export as 16-bit RGB in a memory-mapped file (docs/adr/0009 "High bit depth"): written band by band
 * by the tiled render (any order), read row by row by the encoders. The pages live in the page cache, never on the
 * Java heap (a 24 MP export is 144 MB, a 61 MP one 366 MB). [close] unmaps nothing explicitly (the platform has no
 * unmap) but deletes the file; the mapping is released with the buffer.
 */
class RgbRaster16(override val width: Int, override val height: Int, private val file: File) : RgbRows, Closeable {
    override val bits: Int get() = BITS

    private val channel: FileChannel
    private val buffer: MappedByteBuffer

    init {
        require(width > 0 && height > 0) { "empty raster" }
        val bytes = width.toLong() * height * BYTES_PER_PIXEL
        require(bytes <= Int.MAX_VALUE) { "raster of $bytes bytes is too large" }
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(bytes)
        channel = raf.channel
        buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
        buffer.order(ByteOrder.nativeOrder())
    }

    /**
     * Stores encoded RGBA floats in [0, 1] (`w * 4` per row, [h] rows, alpha ignored) at ([x], [y]) as 16-bit,
     * rounded to the nearest level.
     */
    fun putEncoded(x: Int, y: Int, w: Int, h: Int, rgba: FloatArray, offset: Int = 0) {
        for (r in 0 until h) {
            var at = ((y + r).toLong() * width + x).toInt() * BYTES_PER_PIXEL
            var i = offset + r * w * RGBA
            for (c in 0 until w) {
                buffer.putShort(at, level(rgba[i]))
                buffer.putShort(at + 2, level(rgba[i + 1]))
                buffer.putShort(at + 4, level(rgba[i + 2]))
                at += BYTES_PER_PIXEL
                i += RGBA
            }
        }
    }

    /** Stores 16-bit samples (`w * 3` per row) at ([x], [y]). */
    fun put(x: Int, y: Int, w: Int, h: Int, rgb: IntArray) {
        for (r in 0 until h) {
            var at = ((y + r).toLong() * width + x).toInt() * BYTES_PER_PIXEL
            for (i in r * w * 3 until (r + 1) * w * 3) {
                buffer.putShort(at, rgb[i].toShort())
                at += 2
            }
        }
    }

    override fun row(y: Int, out: IntArray) {
        var at = y * width * BYTES_PER_PIXEL
        for (i in 0 until width * 3) {
            out[i] = buffer.getShort(at).toInt() and MAX
            at += 2
        }
    }

    /** Pixel ([x], [y]) channel [c] (tests). */
    fun sample(x: Int, y: Int, c: Int): Int = buffer.getShort((y * width + x) * BYTES_PER_PIXEL + c * 2).toInt() and MAX

    override fun close() {
        channel.close()
        file.delete()
    }

    companion object {
        const val BITS = 16
        const val MAX = 0xFFFF
        private const val BYTES_PER_PIXEL = 6
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
