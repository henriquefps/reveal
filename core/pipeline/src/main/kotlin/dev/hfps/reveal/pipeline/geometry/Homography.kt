@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.geometry

import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.Transform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A 3x3 projective map in double precision, row-major: `(x, y) -> ((m0 x + m1 y + m2) / w, (m3 x + m4 y + m5) / w)`,
 * `w = m6 x + m7 y + m8`. Composition reads like function application: `(f * g)(p) = f(g(p))`.
 */
class Homography(val m: DoubleArray) {
    init {
        require(m.size == 9) { "a homography has 9 coefficients" }
    }

    operator fun times(o: Homography): Homography {
        val r = DoubleArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        }
        return Homography(r)
    }

    fun inverse(): Homography {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(abs(det) > 1e-300) { "singular homography" }
        val inv = doubleArrayOf(
            c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return Homography(DoubleArray(9) { inv[it] / det })
    }

    /** The image of ([x], [y]), or `null` when it lies on or behind the horizon (`w <= 0`). */
    fun map(x: Double, y: Double): Point? {
        val w = m[6] * x + m[7] * y + m[8]
        if (w <= W_MIN) return null
        return Point((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** True when this map is (numerically) the identity. */
    val isIdentity: Boolean get() = m.indices.all { abs(m[it] - IDENTITY.m[it]) < 1e-12 }

    fun toFloats(): FloatArray = FloatArray(9) { m[it].toFloat() }

    override fun equals(other: Any?): Boolean = other is Homography && m.contentEquals(other.m)

    override fun hashCode(): Int = m.contentHashCode()

    override fun toString(): String = "Homography(${m.joinToString()})"

    companion object {
        /** Points with `w` at or below this are behind the horizon. */
        const val W_MIN: Double = 1e-6

        val IDENTITY: Homography = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun affine(a: Double, b: Double, tx: Double, c: Double, d: Double, ty: Double): Homography =
            Homography(doubleArrayOf(a, b, tx, c, d, ty, 0.0, 0.0, 1.0))
    }
}

/**
 * The Transform panel as one homography (docs/adr/0015, "Transform"), in **normalised** coordinates: a point `p` of a
 * W x H image is `u = (p - centre) / halfDiagonal`, so the same transform means the same on the proxy and at full
 * resolution. The forward map `F` takes the lens-corrected image to the crop's plane:
 *
 * ```
 * F = Offset * Scale * Aspect * Rotate * Recentre * Tilt
 * Tilt = diag(1, 1, 1/f) * Ry(horizontal) * Rx(vertical) * diag(1, 1, f)     (a camera turn, focal f = FOCAL)
 * ```
 * - Tilt: the image as a plane at depth `f` seen by a camera turned by `vertical * MAX_TILT / 100` about the
 *   horizontal axis and `horizontal * MAX_TILT / 100` about the vertical one (positive = the top / right grows);
 *   Recentre moves the image centre back to the origin.
 * - Rotate: `rotate` degrees clockwise as seen (y down); Aspect: `x * 2^(a/200)`, `y * 2^(-a/200)`;
 *   Scale: `scale / 100`; Offset: `offset / 100 * MAX_OFFSET` half diagonals (positive = right / down).
 *
 * The renderers use the inverse ([toCorrected]): a plane point back to the corrected image.
 */
object Perspective {
    /** Focal length of the virtual camera in half diagonals (about a 22 mm full-frame lens). */
    const val FOCAL: Double = 1.0

    /** Tilt in degrees at a slider value of 100. */
    const val MAX_TILT: Double = 40.0

    /** Offset in half diagonals at a slider value of 100. */
    const val MAX_OFFSET: Double = 0.5

    /** `F` of [t] (normalised coordinates), sliders clamped to their ranges. */
    fun forward(t: Transform): Homography {
        val r = RecipeRanges
        val v = Math.toRadians(r.transformVertical.clamp(t.vertical) / 100.0 * MAX_TILT)
        val h = Math.toRadians(r.transformHorizontal.clamp(t.horizontal) / 100.0 * MAX_TILT)
        val tilt = tilt(v, h)
        val centre = checkNotNull(tilt.map(0.0, 0.0)) { "the image centre is always in front" }
        val recentred = Homography.affine(1.0, 0.0, -centre.x, 0.0, 1.0, -centre.y) * tilt
        val angle = Math.toRadians(r.transformRotate.clamp(t.rotate).toDouble())
        val cs = cos(angle)
        val sn = sin(angle)
        val rotate = Homography.affine(cs, -sn, 0.0, sn, cs, 0.0)
        val stretch = 2.0.pow(r.transformAspect.clamp(t.aspect) / 200.0)
        val scale = r.transformScale.clamp(t.scale) / 100.0
        val ox = r.transformOffsetX.clamp(t.offsetX) / 100.0 * MAX_OFFSET
        val oy = r.transformOffsetY.clamp(t.offsetY) / 100.0 * MAX_OFFSET
        val sizeAndOffset = Homography.affine(scale * stretch, 0.0, ox, 0.0, scale / stretch, oy)
        return sizeAndOffset * rotate * recentred
    }

    /** The plane to the corrected image, `F^-1` (normalised), or the identity for an identity transform. */
    fun toCorrected(t: Transform): Homography = if (t.isIdentity) Homography.IDENTITY else forward(t).inverse()

    /** The camera turn: `diag(1, 1, 1/f) * Ry(h) * Rx(v) * diag(1, 1, f)`. */
    private fun tilt(v: Double, h: Double): Homography {
        val cv = cos(v)
        val sv = sin(v)
        val ch = cos(h)
        val sh = sin(h)
        // Rx: y' = cv y - sv z, z' = sv y + cv z (y down: positive v brings the top closer, so it grows).
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cv, -sv, 0.0, sv, cv)
        // Ry: x' = ch x + sh z, z' = -sh x + ch z (positive h brings the right side closer, so it grows).
        val ry = doubleArrayOf(ch, 0.0, sh, 0.0, 1.0, 0.0, -sh, 0.0, ch)
        val f = FOCAL
        val k = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, f))
        val kInv = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0 / f))
        return kInv * Homography(ry) * Homography(rx) * k
    }
}
