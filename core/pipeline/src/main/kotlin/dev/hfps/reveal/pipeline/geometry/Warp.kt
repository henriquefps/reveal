@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.geometry

import dev.hfps.reveal.pipeline.optics.LensWarp
import dev.hfps.reveal.pipeline.recipe.Transform
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The non-affine part of the geometry operator (docs/adr/0015): a point of the crop's **plane** back to the source
 * image, through the Transform's homography ([Perspective.toCorrected]) and the lens's per-channel radial table
 * ([LensWarp]). For plane pixel `t` of a [width] x [height] image (centre `C`, half diagonal `R`):
 *
 * ```
 * v = (t - C) / R;  h = M (v, 1);  u = h.xy / h.z  (h.z <= 1e-6: behind the horizon, outside);
 * r = |u|;  source_c = C + R * lambda_c(r) * u
 * ```
 *
 * [sourcePoint] is that chain in float32 with the float constants below, operation for operation as the shaders do
 * it (`WarpShaders`), so the CPU reference and the GPU start from the same numbers.
 */
class Warp private constructor(
    val width: Int,
    val height: Int,
    /** Plane to corrected image, normalised coordinates. */
    val toCorrected: Homography,
    val lens: LensWarp?,
) {
    val centreX: Float = (width / 2.0).toFloat()
    val centreY: Float = (height / 2.0).toFloat()
    val radius: Float = (hypot(width.toDouble(), height.toDouble()) / 2).toFloat()
    val invRadius: Float = (2 / hypot(width.toDouble(), height.toDouble())).toFloat()

    /** Row-major float coefficients of [toCorrected]. */
    val matrix: FloatArray = toCorrected.toFloats()

    /** True when the channels read different points (lateral CA removal). */
    val chromatic: Boolean get() = lens?.chromatic == true

    /**
     * The source points of plane point ([x], [y]) for red, green and blue into [out] (`x, y` per channel, 6 values);
     * false when the point is behind the horizon (outside).
     */
    fun sourcePoint(x: Float, y: Float, out: FloatArray): Boolean {
        val m = matrix
        val vx = (x - centreX) * invRadius
        val vy = (y - centreY) * invRadius
        val hz = m[6] * vx + m[7] * vy + m[8]
        if (hz <= W_MIN) return false
        val hx = m[0] * vx + m[1] * vy + m[2]
        val hy = m[3] * vx + m[4] * vy + m[5]
        val ux = hx / hz
        val uy = hy / hz
        val l = lens
        if (l == null) {
            val sx = centreX + radius * ux
            val sy = centreY + radius * uy
            for (c in 0 until 3) {
                out[c * 2] = sx
                out[c * 2 + 1] = sy
            }
            return true
        }
        val r = sqrt(ux * ux + uy * uy)
        for (c in 0 until 3) {
            val k = l.lambda(c, r)
            out[c * 2] = centreX + radius * (k * ux)
            out[c * 2 + 1] = centreY + radius * (k * uy)
        }
        return true
    }

    /** The source point of plane point ([x], [y]) for [channel] in double precision (bounds), or `null` if behind. */
    fun source(x: Double, y: Double, channel: Int): Point? {
        val half = hypot(width.toDouble(), height.toDouble()) / 2
        val u = toCorrected.map((x - width / 2.0) / half, (y - height / 2.0) / half) ?: return null
        val k = lens?.lambda(channel, hypot(u.x, u.y).toFloat())?.toDouble() ?: 1.0
        return Point(width / 2.0 + half * k * u.x, height / 2.0 + half * k * u.y)
    }

    override fun equals(other: Any?): Boolean = other is Warp && width == other.width && height == other.height &&
        toCorrected == other.toCorrected && lens == other.lens

    override fun hashCode(): Int = ((width * 31 + height) * 31 + toCorrected.hashCode()) * 31 + lens.hashCode()

    override fun toString(): String = "Warp(${width}x$height, $toCorrected, $lens)"

    companion object {
        /** `h.z` at or below this is behind the horizon (as [Homography.W_MIN]). */
        const val W_MIN: Float = 1e-6f

        /** The warp of [transform] and [lens] on a [width] x [height] image, or `null` when it is the identity. */
        fun of(transform: Transform, lens: LensWarp?, width: Int, height: Int): Warp? {
            val toCorrected = Perspective.toCorrected(transform)
            if (lens == null && toCorrected.isIdentity) return null
            return Warp(width, height, toCorrected, lens)
        }
    }
}
