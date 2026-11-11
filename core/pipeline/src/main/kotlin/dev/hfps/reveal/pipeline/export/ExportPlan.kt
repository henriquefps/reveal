package dev.hfps.reveal.pipeline.export

import dev.hfps.reveal.pipeline.cpu.LanczosResampleOp
import dev.hfps.reveal.pipeline.cpu.LowResGrid
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.recipe.Geometry
import kotlin.math.floor

/** A rectangle in pixels: [x], [y] the top-left corner, [width] x [height]. */
data class PixelBox(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * One tile of the main pass: the final [output] pixels it writes, the [geometry] output pixels they resample
 * (the same box without resizing), and the image [input] region developed for those (null when the box lies
 * entirely in an empty corner of an unconstrained straighten: the tile is black).
 */
data class OutputTile(val output: PixelBox, val geometry: PixelBox, val input: PixelBox?)

/**
 * How a full-resolution export is cut into tiles (docs/adr/0009, "Tiles"). Pure arithmetic, JVM-tested.
 *
 * Three spaces: the upright **image** (W x H), the **geometry output** of slot 13 ([mapping]: crop, straighten,
 * quarter turns, flips; the image itself when the recipe has no geometry), and the final **output** (the size option
 * applied to the geometry output; Lanczos when smaller).
 *
 * - **Pre-pass tiles** cover the whole image (the Light group's grids describe the whole photo, as in the preview) and
 *   are aligned to the grid cell, so every row segment of a cell lies inside one tile and the grid sums keep the order
 *   of a whole-image render (ADR 0007, "Tile seam").
 * - **Output tiles** cover the output. Each one's geometry box is what its Lanczos taps reach (the box itself without
 *   resizing), and its input region is what the geometry operator reads for that box
 *   ([GeometryMapping.sourceRegion], filter margin included), grown by the Detail operator's [reach] (slot 11 reads a
 *   neighbourhood: docs/adr/0012, "Tiles") and clamped to the image. Tiles are sized so the input region fits in
 *   [maxTile].
 */
class ExportPlan(
    val imageWidth: Int,
    val imageHeight: Int,
    val mapping: GeometryMapping,
    val output: PixelSize,
    val maxTile: Int = DEFAULT_MAX_TILE,
    val reach: Int = 0,
) {
    /** Size of the geometry operator's output (the crop's size, swapped for odd quarter turns). */
    val geometrySize: PixelSize = PixelSize(mapping.outputWidth, mapping.outputHeight)

    init {
        require(imageWidth > 0 && imageHeight > 0) { "empty image" }
        require(mapping.imageWidth == imageWidth && mapping.imageHeight == imageHeight) { "mapping of another image" }
        require(output.width in 1..geometrySize.width && output.height in 1..geometrySize.height) {
            "exports never upscale"
        }
        require(maxTile >= MIN_TILE) { "tiles below $MIN_TILE px" }
        require(reach >= 0 && maxTile - 2 * reach >= MIN_CORE) { "reach $reach too large for tiles of $maxTile" }
    }

    /** True when the output is smaller than the geometry output (the Lanczos passes run). */
    val resamples: Boolean get() = output.width != geometrySize.width || output.height != geometrySize.height

    /** True when the geometry operator changes anything. */
    val transforms: Boolean get() = !mapping.isIdentity

    /**
     * True when the geometry pass runs: the geometry changes something, or the input regions carry the Detail apron
     * (the identity mapping then cuts the box out of the developed region, an exact texel copy).
     */
    val geometryPass: Boolean get() = transforms || reach > 0

    /** Geometry-output pixels per output pixel along x and y (1 without resampling). */
    val scaleX: Float get() = geometrySize.width.toFloat() / output.width
    val scaleY: Float get() = geometrySize.height.toFloat() / output.height

    /** The pre-pass tiles for a grid of [cell] px: the whole image in cell-aligned squares of at most [maxTile]. */
    fun prepassTiles(cell: Int): List<PixelBox> {
        val side = maxOf(cell, maxTile / cell * cell)
        val out = ArrayList<PixelBox>()
        for (y in 0 until imageHeight step side) {
            for (x in 0 until imageWidth step side) {
                out += PixelBox(x, y, minOf(side, imageWidth - x), minOf(side, imageHeight - y))
            }
        }
        return out
    }

    /** The pre-pass tiles of [grid]. */
    fun prepassTiles(grid: LowResGrid): List<PixelBox> = prepassTiles(grid.cell)

    /**
     * Largest geometry box edge whose input region fits [maxTile]: the box itself without geometry; with quarter
     * turns or flips the box plus the filter margin; with straightening the rotated box's bounds (at most sqrt 2 times
     * the edge) plus the margin.
     */
    val geometryBudget: Int
        get() = when {
            !transforms -> maxTile - 2 * reach
            mapping.axisAligned -> maxTile - 2 * (GeometryMapping.FILTER_MARGIN + reach) - SLACK
            else -> floor((maxTile - 2 * (GeometryMapping.FILTER_MARGIN + reach) - SLACK) / SQRT2).toInt()
        }

    /** Output pixels per tile edge so that the geometry box (and so the input region) fits. */
    val outputTileX: Int get() = outputTile(scaleX)
    val outputTileY: Int get() = outputTile(scaleY)

    /** The main-pass tiles in row-major order. */
    fun outputTiles(): List<OutputTile> {
        val out = ArrayList<OutputTile>()
        val tx = outputTileX
        val ty = outputTileY
        for (oy in 0 until output.height step ty) {
            val h = minOf(ty, output.height - oy)
            val rows = span(oy until oy + h, scaleY, geometrySize.height)
            for (ox in 0 until output.width step tx) {
                val w = minOf(tx, output.width - ox)
                val cols = span(ox until ox + w, scaleX, geometrySize.width)
                val geometry = PixelBox(cols.first, rows.first, cols.last - cols.first + 1, rows.last - rows.first + 1)
                out += OutputTile(PixelBox(ox, oy, w, h), geometry, inputOf(geometry))
            }
        }
        return out
    }

    private fun inputOf(box: PixelBox): PixelBox? {
        val region = if (transforms) {
            mapping.sourceRegion(box.x, box.y, box.width, box.height)?.let {
                PixelBox(it.left, it.top, it.width, it.height)
            }
        } else {
            box
        }
        return region?.let { grow(it, reach, imageWidth, imageHeight) }
    }

    private fun outputTile(scale: Float): Int {
        val budget = geometryBudget
        if (!resamples) return budget
        // Taps of n output pixels span about (n - 1) s + 2 * 3 s + 2 source pixels.
        val support = 2 * (LanczosResampleOp.LOBES * scale).toInt() + SLACK
        return maxOf(1, floor((budget - support) / scale).toInt())
    }

    private fun span(range: IntRange, scale: Float, size: Int): IntRange =
        if (resamples) LanczosResampleOp.sourceSpan(range, 0, scale, size) else range

    companion object {
        /** Default tile edge: 2048 px (RGBA16F source + scene + working textures: 3 x 32 MB). */
        const val DEFAULT_MAX_TILE: Int = 2048

        /** Smallest tile edge the fallback goes down to. */
        const val MIN_TILE: Int = 256

        private const val SLACK = 6

        /** Smallest tile core left once the Detail apron is taken off both sides. */
        private const val MIN_CORE = 64
        private const val SQRT2 = 1.4142135623730951

        /**
         * The plan for an upright [width] x [height] image with the recipe's [geometry] at [size]; [reach] is the
         * Detail operator's apron at full resolution (`DetailParams.reach`).
         */
        @Suppress("LongParameterList")
        fun of(
            width: Int,
            height: Int,
            geometry: Geometry,
            size: ExportSize,
            maxTile: Int = DEFAULT_MAX_TILE,
            reach: Int = 0,
        ): ExportPlan {
            val mapping = GeometryMapping.of(geometry, width, height)
            val output = size.outputSize(mapping.outputWidth, mapping.outputHeight)
            return ExportPlan(width, height, mapping, output, maxTile, reach)
        }

        /** [box] grown by [margin] on every side, clamped to the [width] x [height] image. */
        fun grow(box: PixelBox, margin: Int, width: Int, height: Int): PixelBox {
            val x0 = maxOf(0, box.x - margin)
            val y0 = maxOf(0, box.y - margin)
            val x1 = minOf(width, box.right + margin)
            val y1 = minOf(height, box.bottom + margin)
            return PixelBox(x0, y0, x1 - x0, y1 - y0)
        }
    }
}
