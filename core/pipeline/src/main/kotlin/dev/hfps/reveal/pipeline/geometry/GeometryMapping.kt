// Numeric and geometric code: early returns validate, loops mirror the shaders, constants are the maths.
@file:Suppress("ComplexCondition", "ReturnCount", "MagicNumber")

package dev.hfps.reveal.pipeline.geometry

import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.optics.LensWarp
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The geometry operator's coordinate mapping (docs/adr/0002 slot 13): output pixel <-> image pixel for one
 * [Geometry] on an image of [imageWidth] x [imageHeight] pixels. Pure maths shared by the CPU reference
 * (`GeometryOp`), the GPU output pass, the export's tiled path and, later, masks and heals (which live in image
 * coordinates). Pixel `i` covers `[i, i + 1)`; its centre is `i + 0.5`.
 *
 * The chain, image point `p` to output point `o`:
 * ```
 * o = D * R(angle) * (p - c) + (outputWidth, outputHeight) / 2
 * ```
 * - `c` is the centre of the crop rectangle in image pixels; the rectangle is the recipe's normalised crop
 *   (oriented, un-rotated image) with its edges rounded to whole pixels, so an un-rotated crop is an exact pixel
 *   copy (no resampling blur).
 * - `R(angle)` turns clockwise as seen (y down) about `c`: straightening happens in the image's own orientation, so
 *   a level horizon stays level under quarter turns and flips.
 * - `D = F * Q^rotate` is the orientation of the result: `rotate` clockwise quarter turns `Q`, then the flips `F`
 *   (mirrors of the final picture: flip horizontal always mirrors left/right as seen).
 * - The output is the crop's size, swapped for odd quarter turns.
 * - Since docs/adr/0015 "image" here is the crop's **plane**: the lens-corrected, transformed image of the same size.
 *   When the lens corrections or the Transform move pixels, [warp] takes a plane point on to the source image (a
 *   homography and the lens's per-channel radial table); without them the plane is the source image.
 *
 * @property toImage output pixel coordinates to image pixel coordinates.
 * @property axisAligned no straightening and no [warp]: every output pixel centre lands exactly on an image pixel
 *   centre, so the operator copies pixels (no filter); otherwise it resamples with Catmull-Rom (`GeometryOp`).
 * @property warp plane to source (perspective, lens), or `null` when the plane is the source image.
 * @property emptyIsSurround the crop mode's editing plane: points outside the image show the editor surround instead
 *   of the operator's black.
 */
class GeometryMapping private constructor(
    val imageWidth: Int,
    val imageHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val toImage: Affine,
    val axisAligned: Boolean,
    val emptyIsSurround: Boolean,
    val warp: Warp? = null,
) {
    /** Image pixel coordinates to output pixel coordinates. */
    val toOutput: Affine = toImage.inverse()

    /** True when the output is the image itself (no crop, angle, quarter turn or flip). */
    val isIdentity: Boolean
        get() = outputWidth == imageWidth && outputHeight == imageHeight && toImage == Affine.IDENTITY && warp == null

    /** The output point of image point ([x], [y]). */
    fun imageToOutput(x: Double, y: Double): Point = toOutput.map(Point(x, y))

    /** The image point of output point ([x], [y]). */
    fun outputToImage(x: Double, y: Double): Point = toImage.map(Point(x, y))

    /**
     * The image pixels an output tile `[x, x + width) x [y, y + height)` reads, including the resampling filter's
     * reach ([FILTER_MARGIN] pixels), clipped to the image; `null` when the tile lies entirely outside it (an empty
     * corner). The export's tiled path renders exactly this source region and runs the operator on it.
     */
    fun sourceRegion(x: Int, y: Int, width: Int, height: Int): IntBox? {
        val box = Box(x.toDouble(), y.toDouble(), (x + width).toDouble(), (y + height).toDouble())
        val b = warp?.let { warpedBounds(it, box) ?: return null } ?: toImage.mapBounds(box)
        val left = maxOf(0, floor(b.left).toInt() - FILTER_MARGIN)
        val top = maxOf(0, floor(b.top).toInt() - FILTER_MARGIN)
        val right = minOf(imageWidth, ceil(b.right).toInt() + FILTER_MARGIN)
        val bottom = minOf(imageHeight, ceil(b.bottom).toInt() + FILTER_MARGIN)
        return if (left < right && top < bottom) IntBox(left, top, right, bottom) else null
    }

    /**
     * Bounds in the source of the output [box] through [toImage] and [warp]: the map is continuous and one to one, so
     * the region's extremes lie on the image of its border, sampled densely (all three channels) plus one pixel for
     * the curvature between samples. A box reaching behind the horizon reads the whole image; `null` when nothing of
     * it lands in the plane.
     */
    private fun warpedBounds(w: Warp, box: Box): Box? {
        var left = Double.MAX_VALUE
        var top = Double.MAX_VALUE
        var right = -Double.MAX_VALUE
        var bottom = -Double.MAX_VALUE
        var behind = false
        var inPlane = false
        fun add(ox: Double, oy: Double) {
            val p = toImage.map(Point(ox, oy))
            if (p.x >= 0 && p.y >= 0 && p.x <= imageWidth && p.y <= imageHeight) inPlane = true
            for (c in 0 until 3) {
                val s = w.source(p.x, p.y, c)
                if (s == null) {
                    behind = true
                } else {
                    left = minOf(left, s.x)
                    top = minOf(top, s.y)
                    right = maxOf(right, s.x)
                    bottom = maxOf(bottom, s.y)
                }
            }
        }
        for (k in 0..BORDER_SAMPLES) {
            val t = k.toDouble() / BORDER_SAMPLES
            val x = box.left + box.width * t
            val y = box.top + box.height * t
            add(x, box.top)
            add(x, box.bottom)
            add(box.left, y)
            add(box.right, y)
        }
        if (!inPlane && !intersectsPlane(box)) return null
        if (behind) return Box(0.0, 0.0, imageWidth.toDouble(), imageHeight.toDouble())
        return Box(left - 1, top - 1, right + 1, bottom + 1)
    }

    /** Whether the plane rectangle meets the output [box] (it can contain the plane with no border sample in it). */
    private fun intersectsPlane(box: Box): Boolean {
        val plane = toOutput.mapBounds(Box(0.0, 0.0, imageWidth.toDouble(), imageHeight.toDouble()))
        return plane.left < box.right && plane.right > box.left && plane.top < box.bottom && plane.bottom > box.top
    }

    /** The output-pass affine as floats, row-major `a, b, tx, c, d, ty` (GPU uniforms and the CPU operator). */
    fun toImageFloats(): FloatArray = floatArrayOf(
        toImage.a.toFloat(),
        toImage.b.toFloat(),
        toImage.tx.toFloat(),
        toImage.c.toFloat(),
        toImage.d.toFloat(),
        toImage.ty.toFloat(),
    )

    override fun equals(other: Any?): Boolean = other is GeometryMapping &&
        imageWidth == other.imageWidth &&
        imageHeight == other.imageHeight &&
        outputWidth == other.outputWidth &&
        outputHeight == other.outputHeight &&
        toImage == other.toImage &&
        axisAligned == other.axisAligned &&
        emptyIsSurround == other.emptyIsSurround &&
        warp == other.warp

    override fun hashCode(): Int {
        var h = imageWidth
        h = h * HASH + imageHeight
        h = h * HASH + outputWidth
        h = h * HASH + outputHeight
        h = h * HASH + toImage.hashCode()
        h = h * HASH + axisAligned.hashCode()
        h = h * HASH + warp.hashCode()
        return h * HASH + emptyIsSurround.hashCode()
    }

    override fun toString(): String =
        "GeometryMapping(${imageWidth}x$imageHeight -> ${outputWidth}x$outputHeight, $toImage, aligned=$axisAligned, " +
            "warp=$warp)"

    companion object {
        /** Image pixels around a sample the resampling filter reads (Catmull-Rom: 2). */
        const val FILTER_MARGIN: Int = 2
        private const val HASH = 31
        private const val QUARTER = 90.0
        private const val BORDER_SAMPLES = 32

        /** The identity mapping of an image of [width] x [height]. */
        fun identity(width: Int, height: Int): GeometryMapping =
            GeometryMapping(width, height, width, height, Affine.IDENTITY, axisAligned = true, emptyIsSurround = false)

        /**
         * The geometry operator's mapping of [recipe] (its geometry, and its optics with the photo's [lens] profile)
         * on an image of [width] x [height].
         */
        fun of(recipe: Recipe, lens: LensProfile?, width: Int, height: Int): GeometryMapping =
            of(recipe.geometry, width, height, lens, recipe.optics)

        /**
         * The geometry operator's mapping of [geometry] on an image of [width] x [height] (crop edges snapped), with
         * the lens corrections of [optics] and [lens] (docs/adr/0015) when given.
         */
        fun of(
            geometry: Geometry,
            width: Int,
            height: Int,
            lens: LensProfile? = null,
            optics: Optics = Optics(),
        ): GeometryMapping {
            require(width > 0 && height > 0) { "image must not be empty" }
            val crop = cropBox(geometry, width, height, snap = true)
            val warp = warp(geometry, width, height, lens, optics)
            val odd = geometry.rotate % 2 == 1
            val outW = (if (odd) crop.height else crop.width).roundToInt()
            val outH = (if (odd) crop.width else crop.height).roundToInt()
            val toOutput = Affine.translate(outW / 2.0, outH / 2.0) *
                orientation(geometry) *
                Affine.rotation(angle(geometry)) *
                Affine.translate(-crop.centerX, -crop.centerY)
            return GeometryMapping(
                imageWidth = width,
                imageHeight = height,
                outputWidth = outW,
                outputHeight = outH,
                toImage = toOutput.inverse(),
                axisAligned = angle(geometry) == 0.0 && warp == null,
                emptyIsSurround = false,
                warp = warp,
            )
        }

        /** The warp of [geometry]'s transform and the lens corrections, `null` when they move nothing. */
        fun warp(geometry: Geometry, width: Int, height: Int, lens: LensProfile?, optics: Optics): Warp? =
            Warp.of(geometry.transform, LensWarp.cached(lens, optics, width, height), width, height)

        /**
         * The crop mode's **editing plane**: the whole image, straightened about the crop centre and oriented, in a
         * plane the size of the oriented image (`width x height`, swapped for odd quarter turns); the crop frame is
         * the axis-aligned [frame] in it. While straightening the image turns under a frame that stays put, and the
         * frame's content is exactly the operator's output ([of]) up to the pixel snapping of the crop edges.
         */
        fun editing(
            geometry: Geometry,
            width: Int,
            height: Int,
            lens: LensProfile? = null,
            optics: Optics = Optics(),
        ): GeometryMapping {
            require(width > 0 && height > 0) { "image must not be empty" }
            val warp = warp(geometry, width, height, lens, optics)
            val odd = geometry.rotate % 2 == 1
            val planeW = if (odd) height else width
            val planeH = if (odd) width else height
            val crop = cropBox(geometry, width, height, snap = false)
            val centre = frameCentre(geometry, crop, width, height)
            val toPlane = Affine.translate(centre.x, centre.y) *
                orientation(geometry) *
                Affine.rotation(angle(geometry)) *
                Affine.translate(-crop.centerX, -crop.centerY)
            return GeometryMapping(
                imageWidth = width,
                imageHeight = height,
                outputWidth = planeW,
                outputHeight = planeH,
                toImage = toPlane.inverse(),
                axisAligned = angle(geometry) == 0.0 && warp == null,
                emptyIsSurround = true,
                warp = warp,
            )
        }

        /** The crop frame in the [editing] plane (no pixel snapping). */
        fun frame(geometry: Geometry, width: Int, height: Int): Box {
            val crop = cropBox(geometry, width, height, snap = false)
            val centre = frameCentre(geometry, crop, width, height)
            val odd = geometry.rotate % 2 == 1
            return Box.centred(
                centre.x,
                centre.y,
                if (odd) crop.height else crop.width,
                if (odd) crop.width else crop.height,
            )
        }

        /** The orientation `D = F * Q^rotate` (linear, about the origin). */
        fun orientation(geometry: Geometry): Affine =
            Affine.flip(geometry.flipH, geometry.flipV) * Affine.rotation(geometry.rotate * QUARTER)

        /** The straighten angle the operator uses: the recipe's, clamped to its range. */
        fun angle(geometry: Geometry): Double {
            val range = RecipeRanges.angle
            return geometry.angle.coerceIn(range.min, range.max).toDouble()
        }

        /**
         * The crop rectangle in image pixels, the full image when the recipe has none. [snap] rounds the edges to
         * whole pixels (at least one pixel wide and tall), as the operator does.
         */
        @Suppress("ReturnCount")
        fun cropBox(geometry: Geometry, width: Int, height: Int, snap: Boolean): Box {
            val crop = geometry.crop ?: return Box(0.0, 0.0, width.toDouble(), height.toDouble())
            if (!snap) {
                return Box(
                    crop.left.toDouble() * width,
                    crop.top.toDouble() * height,
                    crop.right.toDouble() * width,
                    crop.bottom.toDouble() * height,
                )
            }
            val (l, r) = snapEdges(crop.left, crop.right, width)
            val (t, b) = snapEdges(crop.top, crop.bottom, height)
            return Box(l.toDouble(), t.toDouble(), r.toDouble(), b.toDouble())
        }

        private fun snapEdges(lo: Float, hi: Float, size: Int): Pair<Int, Int> {
            var a = (lo.toDouble() * size).roundToInt().coerceIn(0, size - 1)
            var b = (hi.toDouble() * size).roundToInt().coerceIn(1, size)
            if (b <= a) {
                if (a + 1 <= size) b = a + 1 else a = b - 1
            }
            return a to b
        }

        /** Where the crop centre sits in the editing plane: `D * (c - imageCentre) + planeSize / 2`. */
        private fun frameCentre(geometry: Geometry, crop: Box, width: Int, height: Int): Point {
            val odd = geometry.rotate % 2 == 1
            val planeW = if (odd) height else width
            val planeH = if (odd) width else height
            val v = orientation(geometry).mapVector(crop.centerX - width / 2.0, crop.centerY - height / 2.0)
            return Point(v.x + planeW / 2.0, v.y + planeH / 2.0)
        }
    }
}

/** An integer pixel rectangle `[left, right) x [top, bottom)`. */
data class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
