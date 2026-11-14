// Geometry: corner tuples (left, top, right, bottom) and early outs for values that cannot be read.
@file:Suppress("ReturnCount", "MagicNumber", "DestructuringDeclarationWithTooManyEntries")

package dev.hfps.reveal.data.xmp

import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The crop, straighten angle and orientation as Camera Raw stores them (ADR 0026 "Crop and orientation").
 *
 * Camera Raw stores the two opposite corners of the crop (`crs:CropLeft/Top/Right/Bottom`) as points of the **stored**
 * (un-oriented, un-straightened) image, 0..1, and the straighten angle `crs:CropAngle`; the rectangle is axis-aligned
 * once the image is turned by the angle about the crop's centre (the reading darktable's Lightroom import makes).
 * Reveal's crop is centre + size in the oriented image with the angle about the centre (`GeometryMapping`). A
 * straightened crop needs the photo's size (the angle turns pixels, not fractions): without it nothing is written or
 * read. Each app has its own transform plane, so a crop over a transform is approximate.
 *
 * `tiff:Orientation` is the picture as seen: the photo's EXIF orientation, then Reveal's quarter turns and flips.
 */
object CameraRawGeometry {
    private const val MAX_ANGLE = 45.0
    private const val COORDINATE_EPSILON = 1e-4
    private const val ANGLE_EPSILON = 0.01f
    private val CORNERS = listOf("CropLeft", "CropTop", "CropRight", "CropBottom")

    /** `HasCrop`, the corners and `CropAngle` for [recipe], or only `HasCrop=False`; empty when it cannot be said. */
    fun crop(recipe: Recipe, frame: PhotoFrame): LinkedHashMap<String, String> {
        val g = recipe.geometry
        val out = LinkedHashMap<String, String>()
        if (g.crop == null && g.angle == 0f) {
            out["HasCrop"] = "False"
            return out
        }
        val (pw, ph) = pixelSize(frame, straightened = g.angle != 0f) ?: return out
        val rect = g.crop ?: CropRect(0f, 0f, 1f, 1f)
        val orientation = Orientation.matrix(frame.orientation)
        val swaps = orientation[0] == 0
        // Oriented image: centre and half size in pixels; then the stored image (orientation undone).
        val (sx, sy) = Orientation.applyNormalised(
            Orientation.inverse(orientation),
            (rect.left + rect.right) / 2.0,
            (rect.top + rect.bottom) / 2.0,
        )
        val halfW = (rect.right - rect.left) / 2.0 * pw
        val halfH = (rect.bottom - rect.top) / 2.0 * ph
        val (sw, sh) = if (swaps) ph to pw else pw to ph
        val (shw, shh) = if (swaps) halfH to halfW else halfW to halfH
        val angle = g.angle.toDouble() * Orientation.det(orientation)
        val t = Math.toRadians(angle)

        // A corner = centre + R(-angle) * (±half), normalised by the stored size.
        fun corner(dx: Double, dy: Double): Pair<Double, Double> = (sx * sw + cos(t) * dx + sin(t) * dy) / sw to
            (sy * sh - sin(t) * dx + cos(t) * dy) / sh
        val (left, top) = corner(-shw, -shh)
        val (right, bottom) = corner(shw, shh)
        out["HasCrop"] = "True"
        out["CropTop"] = coordinate(top)
        out["CropLeft"] = coordinate(left)
        out["CropBottom"] = coordinate(bottom)
        out["CropRight"] = coordinate(right)
        out["CropAngle"] = CameraRaw.fmt(angle.toFloat(), 2, signed = true)
        return out
    }

    /**
     * [base] with the crop and angle of [values] (the inverse of [crop]); `HasCrop=False` clears them. Values that are
     * missing, a straightened crop without the photo's size, or a crop outside the image leave [base] as it was.
     */
    fun applyCrop(base: Recipe, values: Map<String, String>, frame: PhotoFrame): Recipe {
        val has = values["HasCrop"]?.trim()
        val stored = (values["CropAngle"]?.let(CameraRaw::num) ?: 0f).toDouble().coerceIn(-MAX_ANGLE, MAX_ANGLE)
        val corners = corners(values)
        val size = pixelSize(frame, straightened = stored != 0.0)
        return when {
            has == null -> base

            !has.equals("True", ignoreCase = true) -> base.copy(geometry = base.geometry.copy(crop = null, angle = 0f))

            corners == null || size == null -> base

            else -> fromCorners(corners, stored, frame, size)?.let { (rect, angle) ->
                base.copy(geometry = base.geometry.copy(crop = rect, angle = angle))
            } ?: base
        }
    }

    private fun fromCorners(
        corners: DoubleArray,
        stored: Double,
        frame: PhotoFrame,
        size: Pair<Double, Double>,
    ): Pair<CropRect?, Float>? {
        val (left, top, right, bottom) = corners.toList()
        val (pw, ph) = size
        val orientation = Orientation.matrix(frame.orientation)
        val swaps = orientation[0] == 0
        val (sw, sh) = if (swaps) ph to pw else pw to ph
        // Stored image: the centre, and the half diagonal turned into the crop's own axes.
        val sx = (left + right) / 2.0
        val sy = (top + bottom) / 2.0
        val dx = (right - sx) * sw
        val dy = (bottom - sy) * sh
        val t = Math.toRadians(stored)
        val qx = abs(cos(t) * dx - sin(t) * dy)
        val qy = abs(sin(t) * dx + cos(t) * dy)
        val (halfW, halfH) = if (swaps) qy to qx else qx to qy
        val (cx, cy) = Orientation.applyNormalised(orientation, sx, sy)
        val angle = (stored * Orientation.det(orientation)).toFloat()
        val box = doubleArrayOf(cx - halfW / pw, cy - halfH / ph, cx + halfW / pw, cy + halfH / ph)
        if (!fits(box)) return null
        val rect = runCatching {
            CropRect(clamp(box[0]), clamp(box[1]), clamp(box[2]), clamp(box[3]))
        }.getOrNull() ?: return null
        val full = rect == CropRect(0f, 0f, 1f, 1f)
        return (if (full && angle == 0f) null else rect) to angle
    }

    private fun clamp(v: Double): Float = v.coerceIn(0.0, 1.0).toFloat()

    /** Inside the image (within rounding) and not empty. */
    private fun fits(box: DoubleArray): Boolean {
        val (l, t, r, b) = box.toList()
        val inside = l >= -COORDINATE_EPSILON && t >= -COORDINATE_EPSILON &&
            r <= 1 + COORDINATE_EPSILON && b <= 1 + COORDINATE_EPSILON
        return inside && r - l > COORDINATE_EPSILON && b - t > COORDINATE_EPSILON
    }

    /** Left, top, right, bottom; null when one is missing or not a number. */
    private fun corners(values: Map<String, String>): DoubleArray? {
        val parsed = CORNERS.map { key -> values[key]?.let(CameraRaw::num)?.toDouble() }
        return if (parsed.any { it == null }) null else parsed.map { it!! }.toDoubleArray()
    }

    /** The upright pixel size; a crop without straightening needs none (fractions are enough). */
    private fun pixelSize(frame: PhotoFrame, straightened: Boolean): Pair<Double, Double>? {
        val w = frame.uprightWidth
        val h = frame.uprightHeight
        return when {
            w != null && h != null && w > 0 && h > 0 -> w.toDouble() to h.toDouble()
            !straightened -> 1.0 to 1.0
            else -> null
        }
    }

    private fun coordinate(v: Double) = String.format(Locale.ROOT, "%.6f", v)

    /** True when [a] and [b] are the same crop within Camera Raw's six decimals (and a hundredth of a degree). */
    fun sameCrop(a: Map<String, String>, b: Map<String, String>): Boolean {
        val hasA = a["HasCrop"]?.trim()?.equals("True", ignoreCase = true) == true
        val hasB = b["HasCrop"]?.trim()?.equals("True", ignoreCase = true) == true
        val ca = corners(a)
        val cb = corners(b)
        return when {
            hasA != hasB -> false

            !hasA -> true

            ca == null || cb == null -> false

            else ->
                ca.indices.all { abs(ca[it] - cb[it]) < COORDINATE_EPSILON } &&
                    abs((a["CropAngle"]?.let(CameraRaw::num) ?: 0f) - (b["CropAngle"]?.let(CameraRaw::num) ?: 0f)) <
                    ANGLE_EPSILON
        }
    }

    /** `tiff:Orientation` of the picture: the photo's EXIF orientation, then Reveal's quarter turns and flips. */
    fun orientation(recipe: Recipe, frame: PhotoFrame): Int =
        Orientation.compose(frame.orientation, recipe.geometry.rotate, recipe.geometry.flipH, recipe.geometry.flipV)

    /** [base] with the quarter turns and flip that turn the photo's EXIF orientation into [tiffOrientation]. */
    fun applyOrientation(base: Recipe, tiffOrientation: Int, frame: PhotoFrame): Recipe {
        val (rotate, flipH) = Orientation.residual(frame.orientation, tiffOrientation) ?: return base
        return base.copy(geometry = base.geometry.copy(rotate = rotate, flipH = flipH, flipV = false))
    }
}
