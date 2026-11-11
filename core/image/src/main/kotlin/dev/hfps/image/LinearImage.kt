package dev.hfps.image

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * A linear-light RGB image, 16 bits per channel, interleaved `R G B R G B ...`, row-major, top row first,
 * stored (not yet rotated by the orientation of its colour info).
 *
 * Encoding: **linear** (no transfer curve), unsigned 16-bit (read a sample as `data[i].toInt() and 0xFFFF`).
 * 0 is black and 65535 is the image's white (for a RAW: the sensor's white level after black subtraction; for a
 * JPEG/HEIF/PNG/WebP: the colour space's reference white). The primaries are those described by the accompanying
 * colour info (camera RGB for a RAW; the file's own colour space for a standard image); nothing is white balanced.
 *
 * Plain Kotlin on purpose: no Android types, so the pipeline maps it mechanically.
 */
class LinearImage(val width: Int, val height: Int, val data: ShortArray) {
    init {
        require(width > 0 && height > 0) { "Image size must be positive: ${width}x$height" }
        require(data.size.toLong() == width.toLong() * height * CHANNELS) {
            "data has ${data.size} values, expected ${width.toLong() * height * CHANNELS}"
        }
    }

    /** Value (0..65535) of [channel] (0 R, 1 G, 2 B) of the pixel at [x], [y]. */
    fun sample(x: Int, y: Int, channel: Int): Int = data[(y * width + x) * CHANNELS + channel].toInt() and MASK

    override fun toString(): String = "LinearImage(${width}x$height)"

    companion object {
        const val CHANNELS = 3
        const val MAX_VALUE = 65535
        private const val MASK = 0xFFFF
    }
}

/**
 * The same pixels as [LinearImage] in **native memory** owned by the decoder (a zero-copy view of LibRaw's buffer):
 * it does not count against the Java heap (a Java `allocateDirect` buffer does on Android) and uploads to a GL texture
 * without a copy. A full-size 24 MP RAW is 144 MB, more than a default app heap allows as a [ShortArray]: use this
 * for full-size decodes and [LinearImage] for proxies.
 *
 * [buffer] (native byte order, `width * height * 3` unsigned 16-bit samples) is valid until [close]; using it
 * afterwards is a bug. Always close, ideally in `use { }`.
 */
class NativeLinearImage(val width: Int, val height: Int, buffer: ByteBuffer, private val release: () -> Unit) :
    AutoCloseable {
    private var open = true

    /** The samples as bytes; throws once closed. */
    val buffer: ByteBuffer = buffer.order(ByteOrder.nativeOrder())
        get() {
            check(open) { "NativeLinearImage is closed" }
            return field
        }

    init {
        require(buffer.isDirect) { "Buffer must be direct" }
        require(buffer.capacity().toLong() == width.toLong() * height * LinearImage.CHANNELS * Short.SIZE_BYTES) {
            "Buffer has ${buffer.capacity()} bytes for ${width}x$height"
        }
    }

    /** A view of the samples (shares the memory); throws once closed. */
    val samples: ShortBuffer get() = buffer.duplicate().apply { clear() }.order(ByteOrder.nativeOrder()).asShortBuffer()

    /** Copies into a heap [LinearImage] (144 MB at 24 MP: may exceed the Java heap). */
    fun toLinearImage(): LinearImage {
        val data = ShortArray(width * height * LinearImage.CHANNELS)
        samples.get(data)
        return LinearImage(width, height, data)
    }

    /** Frees the native memory. Idempotent. */
    @Synchronized
    override fun close() {
        if (open) {
            open = false
            release()
        }
    }
}
