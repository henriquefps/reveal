package dev.hfps.reveal.pipeline.heal

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.input.TileSource

/**
 * What the healing engine reads (docs/adr/0023): the upright input image of one resolution in the [PipelineInput]
 * encoding, by rectangles. Pixels are RGB floats, row by row.
 */
interface PixelReader {
    val width: Int
    val height: Int

    /** True when [read] may be called from any thread (the Remove fills read the original on their worker). */
    val threadSafe: Boolean get() = true

    /** Writes the pixels of the rectangle ([x], [y], [w] x [h]), inside the image, into [out] (`w * h * 3`). */
    fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray)

    companion object {
        /** A reader over [input]'s pixels (proxies, tests). */
        fun of(input: PipelineInput): PixelReader = InputReader(input)

        /** A reader over [source] (full-resolution originals); reads are serialised on [source]. */
        fun of(source: TileSource): PixelReader = TileReader(source)
    }
}

private class InputReader(private val input: PipelineInput) : PixelReader {
    override val width: Int get() = input.width
    override val height: Int get() = input.height

    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
        val c = PipelineInput.CHANNELS
        for (row in 0 until h) {
            System.arraycopy(input.linearRgb, ((y + row) * input.width + x) * c, out, row * w * c, w * c)
        }
    }
}

private class TileReader(private val source: TileSource) : PixelReader {
    override val width: Int get() = source.width
    override val height: Int get() = source.height
    private var band = FloatArray(0)

    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) = synchronized(source) {
        var row = 0
        while (row < h) {
            val rows = minOf(BAND_ROWS, h - row)
            val n = w * rows * RGBA
            if (band.size < n) band = FloatArray(n)
            source.read(x, y + row, w, rows, band)
            var s = 0
            var d = row * w * RGB
            repeat(w * rows) {
                out[d] = band[s]
                out[d + 1] = band[s + 1]
                out[d + 2] = band[s + 2]
                s += RGBA
                d += RGB
            }
            row += rows
        }
    }

    private companion object {
        const val BAND_ROWS = 64
        const val RGBA = 4
        const val RGB = 3
    }
}

/**
 * The pixels of a rectangle of an image, read once, with edge-clamped access: [at] of a pixel outside the image gives
 * the nearest pixel inside it (the rectangle is the requested one clamped to the image).
 */
internal class Region private constructor(
    val x0: Int,
    val y0: Int,
    val width: Int,
    val height: Int,
    val rgb: FloatArray,
) {
    /** Index into [rgb] of image pixel ([x], [y]), clamped into the region. */
    fun index(x: Int, y: Int): Int {
        val cx = (x - x0).coerceIn(0, width - 1)
        val cy = (y - y0).coerceIn(0, height - 1)
        return (cy * width + cx) * RGB
    }

    companion object {
        private const val RGB = 3

        /** A region over pixels already read: [rgb] covers ([x0], [y0], [width] x [height]). */
        fun of(x0: Int, y0: Int, width: Int, height: Int, rgb: FloatArray): Region = Region(x0, y0, width, height, rgb)

        /** Reads ([x0], [y0])..([x1], [y1]) exclusive of [x1], [y1], clamped to the [reader]'s image. */
        @Suppress("LongParameterList")
        fun read(
            reader: (Int, Int, Int, Int, FloatArray) -> Unit,
            imageW: Int,
            imageH: Int,
            x0: Int,
            y0: Int,
            x1: Int,
            y1: Int,
        ): Region {
            val ax = x0.coerceIn(0, imageW - 1)
            val ay = y0.coerceIn(0, imageH - 1)
            val bx = x1.coerceIn(ax + 1, imageW)
            val by = y1.coerceIn(ay + 1, imageH)
            val w = bx - ax
            val h = by - ay
            val rgb = FloatArray(w * h * RGB)
            reader(ax, ay, w, h, rgb)
            return Region(ax, ay, w, h, rgb)
        }
    }
}
