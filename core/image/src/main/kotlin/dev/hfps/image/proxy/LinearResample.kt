package dev.hfps.image.proxy

import dev.hfps.image.ImageSize
import dev.hfps.image.LinearImage
import dev.hfps.image.NativeLinearImage
import dev.hfps.image.raw.RawDecoder
import dev.hfps.image.raw.RawNative
import java.nio.ShortBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Resampling of linear images. Averaging happens in linear light (the physically right place to do it). */
object LinearResample {

    /** Size of [source] after scaling to [longEdge] (never up): same rule as [ImageSize.fitLongEdge]. */
    fun targetSize(width: Int, height: Int, longEdge: Int): ImageSize = ImageSize(width, height).fitLongEdge(longEdge)

    /**
     * Area-average downscale to [targetWidth] x [targetHeight] (each output pixel is the exact coverage-weighted mean
     * of the source rectangle it covers). Returns [source] when the size is unchanged; never upsamples.
     */
    fun downscale(source: LinearImage, targetWidth: Int, targetHeight: Int): LinearImage {
        if (targetWidth == source.width && targetHeight == source.height) return source
        return downscale(ShortBuffer.wrap(source.data), source.width, source.height, targetWidth, targetHeight)
    }

    /**
     * [downscale] reading from a native image without copying it first (full-size RAW decodes). Uses the native
     * implementation when the library is loaded (about 8x faster than [downscale] over a [ShortBuffer]); both give the
     * same pixels within one code.
     */
    fun downscale(source: NativeLinearImage, targetWidth: Int, targetHeight: Int): LinearImage {
        if (targetWidth == source.width && targetHeight == source.height) return source.toLinearImage()
        if (RawDecoder.isAvailable) {
            val out = ShortArray(targetWidth * targetHeight * LinearImage.CHANNELS)
            RawNative.nativeDownscale(source.buffer, source.width, source.height, targetWidth, targetHeight, out)
            return LinearImage(targetWidth, targetHeight, out)
        }
        return downscale(source.samples, source.width, source.height, targetWidth, targetHeight)
    }

    /** [downscale] for the samples in [source] (read through a duplicate: its own position is untouched). */
    fun downscale(source: ShortBuffer, width: Int, height: Int, targetWidth: Int, targetHeight: Int): LinearImage {
        require(targetWidth in 1..width && targetHeight in 1..height) {
            "Target ${targetWidth}x$targetHeight must fit in ${width}x$height"
        }
        val rowValues = width * LinearImage.CHANNELS
        val xScale = width.toDouble() / targetWidth
        val yScale = height.toDouble() / targetHeight
        val columnWeights = Array(targetWidth) { weights(it, xScale, width) }
        val out = ShortArray(targetWidth * targetHeight * LinearImage.CHANNELS)
        val rowSum = DoubleArray(rowValues)
        val row = ShortArray(rowValues)
        val reader = source.duplicate()
        var o = 0
        for (ty in 0 until targetHeight) {
            val rows = weights(ty, yScale, height)
            rowSum.fill(0.0)
            var rowTotal = 0.0
            for (r in rows.indices) {
                val wy = rows.values[r]
                reader.position((rows.start + r) * rowValues)
                reader.get(row, 0, rowValues)
                for (i in 0 until rowValues) rowSum[i] += wy * (row[i].toInt() and MASK)
                rowTotal += wy
            }
            for (tx in 0 until targetWidth) {
                val cols = columnWeights[tx]
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var colTotal = 0.0
                for (c in cols.indices) {
                    val w = cols.values[c]
                    val i = (cols.start + c) * LinearImage.CHANNELS
                    r += w * rowSum[i]
                    g += w * rowSum[i + 1]
                    b += w * rowSum[i + 2]
                    colTotal += w
                }
                val norm = rowTotal * colTotal
                out[o++] = (r / norm).roundToInt().coerceIn(0, MAX).toShort()
                out[o++] = (g / norm).roundToInt().coerceIn(0, MAX).toShort()
                out[o++] = (b / norm).roundToInt().coerceIn(0, MAX).toShort()
            }
        }
        return LinearImage(targetWidth, targetHeight, out)
    }

    /** Downscale so the long edge is at most [longEdge]. */
    fun fitLongEdge(source: LinearImage, longEdge: Int): LinearImage {
        val size = targetSize(source.width, source.height, longEdge)
        return downscale(source, size.width, size.height)
    }

    private class Span(val start: Int, val values: DoubleArray) {
        val indices: IntRange get() = values.indices
    }

    /** Source pixels covered by output index [index] and their fractional coverage. */
    private fun weights(index: Int, scale: Double, sourceSize: Int): Span {
        val from = index * scale
        val to = min((index + 1) * scale, sourceSize.toDouble())
        val first = floor(from).toInt()
        val last = min(ceil(to).toInt(), sourceSize) - 1
        return Span(
            first,
            DoubleArray(last - first + 1) { k ->
                min(first + k + 1.0, to) -
                    maxOf(first + k.toDouble(), from)
            },
        )
    }

    private const val MASK = 0xFFFF
    private const val MAX = 65535
}
