@file:Suppress("MagicNumber", "ComplexCondition", "ReturnCount")

package dev.hfps.reveal.pipeline.optics

import kotlin.math.hypot
import kotlin.math.pow

/** Where a [LensProfile] came from (shown in the Optics panel). */
enum class LensProfileSource {
    /** The correction data the camera embedded in the RAW (Sony ARW). */
    EMBEDDED,

    /** The lensfun database, matched by camera and lens from the EXIF. */
    LENSFUN,
}

/**
 * The correction data of the lens a photo was taken with, resolution independent (docs/adr/0015, "Lens profiles").
 * Every radius is **normalised to the half diagonal** of the photo (0 = centre, 1 = corner), and every function is the
 * profile at 100 %:
 *
 * - [distortion]`(r)`: for a point at radius `r` of the corrected image, the source radius divided by `r` (output to
 *   source, as the geometry resampling looks up its pixels);
 * - [chromatic]`(c, r)`: the extra radial scale of channel `c` (0 = red, 2 = blue; green is 1) at corrected radius `r`,
 *   multiplying [distortion];
 * - [vignetting]`(r)`: the relative illumination at source radius `r` (1 at the centre, below 1 in darkened corners);
 *   the correction divides by it.
 *
 * @property name the lens as the panel names it.
 */
sealed interface LensProfile {
    val name: String
    val source: LensProfileSource
    val hasDistortion: Boolean
    val hasChromatic: Boolean
    val hasVignetting: Boolean

    fun distortion(r: Double): Double

    fun chromatic(channel: Int, r: Double): Double

    fun vignetting(r: Double): Double
}

/**
 * The Sony ARW correction knots (`DistortionCorrParams`, `ChromaticAberrationCorrParams`, `VignettingCorrParams`, as
 * `:core:image`'s `SonyLensCorrection` reads them: the knot count first, then the values). Scales as darktable's
 * `lens.cc` reads them (embedded metadata, version 2); the knot radii were measured against the camera's own JPEGs
 * (docs/adr/0015, "Sony knots"): `i / (n - 1)` fits them better than darktable's `(i + 0.5) / (n - 1)`.
 *
 * ```
 * knot i (0 <= i < n) at radius i / (n - 1) (0 = centre, 1 = corner), piecewise linear, flat beyond the last
 * distortion   = 1 + D_i * 2^-14
 * chromatic    = 1 + R_i * 2^-21 (red), 1 + B_i * 2^-21 (blue)
 * vignetting   = 2^(0.5 - 2^(V_i * 2^-13 - 1))
 * ```
 */
class SonyLensProfile(
    override val name: String,
    private val distortionKnots: IntArray?,
    private val redKnots: IntArray?,
    private val blueKnots: IntArray?,
    private val vignettingKnots: IntArray?,
) : LensProfile {
    override val source: LensProfileSource = LensProfileSource.EMBEDDED
    override val hasDistortion: Boolean = distortionKnots != null
    override val hasChromatic: Boolean = redKnots != null && blueKnots != null
    override val hasVignetting: Boolean = vignettingKnots != null

    override fun distortion(r: Double): Double = distortionKnots?.let { 1.0 + spline(it, r) * DISTORTION_UNIT } ?: 1.0

    override fun chromatic(channel: Int, r: Double): Double {
        val knots = when (channel) {
            0 -> redKnots
            2 -> blueKnots
            else -> null
        } ?: return 1.0
        return 1.0 + spline(knots, r) * CHROMATIC_UNIT
    }

    override fun vignetting(r: Double): Double {
        val knots = vignettingKnots ?: return 1.0
        return 2.0.pow(0.5 - 2.0.pow(spline(knots, r) * VIGNETTING_UNIT - 1.0))
    }

    /** Piecewise-linear interpolation of [values] over the knots `i / (n - 1)`, flat outside. */
    private fun spline(values: IntArray, r: Double): Double {
        val n = values.size
        if (n == 1) return values[0].toDouble()
        val t = r * (n - 1)
        if (t <= 0.0) return values[0].toDouble()
        if (t >= n - 1) return values[n - 1].toDouble()
        val i = t.toInt()
        val f = t - i
        return values[i] + (values[i + 1] - values[i]) * f
    }

    override fun equals(other: Any?): Boolean = other is SonyLensProfile && name == other.name &&
        distortionKnots.contentEquals(other.distortionKnots) && redKnots.contentEquals(other.redKnots) &&
        blueKnots.contentEquals(other.blueKnots) && vignettingKnots.contentEquals(other.vignettingKnots)

    override fun hashCode(): Int = listOf(
        name.hashCode(),
        distortionKnots.contentHashCode(),
        redKnots.contentHashCode(),
        blueKnots.contentHashCode(),
        vignettingKnots.contentHashCode(),
    ).fold(0) { h, v -> h * HASH + v }

    override fun toString(): String = "SonyLensProfile($name)"

    companion object {
        private const val HASH = 31
        private val DISTORTION_UNIT = 2.0.pow(-14)
        private val CHROMATIC_UNIT = 2.0.pow(-21)
        private val VIGNETTING_UNIT = 2.0.pow(-13)

        /** The largest knot count Sony writes (16; APS-C bodies write 11). */
        const val MAX_KNOTS: Int = 16

        /**
         * The profile of the three stored blocks (each with its count first, as stored), or `null` when none is
         * usable. A block whose count does not fit is dropped (darktable rejects the same).
         */
        fun of(name: String, distortion: List<Int>?, vignetting: List<Int>?, chromatic: List<Int>?): SonyLensProfile? {
            val d = knots(distortion, 1)
            val v = knots(vignetting, 1)
            val ca = knots(chromatic, 2)
            val red = ca?.let { it.copyOfRange(0, it.size / 2) }
            val blue = ca?.let { it.copyOfRange(it.size / 2, it.size) }
            if (d == null && v == null && red == null) return null
            return SonyLensProfile(name, d, red, blue, v)
        }

        private fun knots(block: List<Int>?, perKnot: Int): IntArray? {
            if (block == null || block.isEmpty()) return null
            val count = block[0]
            val n = count / perKnot
            if (n < 2 || n > MAX_KNOTS || count % perKnot != 0 || block.size < count + 1) return null
            return IntArray(count) { block[it + 1] }
        }
    }
}

/** lensfun's distortion models (`lfDistortionModel`). */
enum class LensfunDistortion { NONE, POLY3, POLY5, PTLENS }

/** lensfun's transverse chromatic aberration models (`lfTCAModel`). */
enum class LensfunTca { NONE, LINEAR, POLY3 }

/**
 * One lens of the lensfun database, its calibration already interpolated to the photo's focal length (and aperture
 * for vignetting) by [LensfunDatabase]. lensfun's conventions (`modifier.cpp`, `mod-coord.cpp`, `mod-color.cpp`) are
 * converted to the half-diagonal radius here:
 *
 * - distortion and TCA are in Hugin units, `r = 1` at half the calibration image's height: a corrected radius `r`
 *   (half diagonals) is `rho = r * (cropCal / cropImage) * hypot(aspectCal, 1)`. lensfun keeps the centre's
 *   magnification at 1, so with `d` the polynomial's value at 0 the ratio is `P(rho / d) / d` (poly3 `d = 1 - k1`,
 *   ptlens `d = 1 - a - b - c`, poly5 `d = 1`), `P(x) = Rd(x) / x`;
 * - TCA scales each channel at the distorted radius `rho * distortion(r)` (linear `k`, poly3 `b x^2 + c x + v`);
 * - vignetting (`pa`) is in units of the calibration's half diagonal: `x = r * cropCal / cropImage`,
 *   `1 + k1 x^2 + k2 x^4 + k3 x^6`, which the correction divides by.
 */
data class LensfunProfile(
    override val name: String,
    val distortionModel: LensfunDistortion,
    val distortionTerms: List<Double>,
    val tcaModel: LensfunTca,
    /** LINEAR: `kr, kb`; POLY3: `vr, vb, cr, cb, br, bb` (lensfun's order). */
    val tcaTerms: List<Double>,
    /** `k1, k2, k3` of the `pa` model, or empty. */
    val vignettingTerms: List<Double>,
    val cropCalibration: Double,
    val aspectCalibration: Double,
    val cropImage: Double,
) : LensProfile {
    override val source: LensProfileSource = LensProfileSource.LENSFUN
    override val hasDistortion: Boolean = distortionModel != LensfunDistortion.NONE
    override val hasChromatic: Boolean = tcaModel != LensfunTca.NONE
    override val hasVignetting: Boolean = vignettingTerms.size == 3

    private val hugin = cropCalibration / cropImage * hypot(aspectCalibration, 1.0)
    private val vignetteScale = cropCalibration / cropImage

    override fun distortion(r: Double): Double {
        val t = distortionTerms
        val rho = r * hugin
        return when (distortionModel) {
            LensfunDistortion.NONE -> 1.0

            LensfunDistortion.POLY3 -> {
                val d = 1 - t[0]
                val x = rho / d
                (1 - t[0] + t[0] * x * x) / d
            }

            LensfunDistortion.POLY5 -> 1 + t[0] * rho * rho + t[1] * rho.pow(4)

            LensfunDistortion.PTLENS -> {
                val d = 1 - t[0] - t[1] - t[2]
                val x = rho / d
                (t[0] * x * x * x + t[1] * x * x + t[2] * x + d) / d
            }
        }
    }

    override fun chromatic(channel: Int, r: Double): Double {
        if (channel == 1 || tcaModel == LensfunTca.NONE) return 1.0
        val blue = channel == 2
        val t = tcaTerms
        return when (tcaModel) {
            LensfunTca.NONE -> 1.0

            LensfunTca.LINEAR -> if (blue) t[1] else t[0]

            LensfunTca.POLY3 -> {
                val x = r * hugin * distortion(r)
                val v = if (blue) t[1] else t[0]
                val c = if (blue) t[3] else t[2]
                val b = if (blue) t[5] else t[4]
                b * x * x + c * x + v
            }
        }
    }

    override fun vignetting(r: Double): Double {
        if (!hasVignetting) return 1.0
        val x2 = (r * vignetteScale).let { it * it }
        val k = vignettingTerms
        return 1 + k[0] * x2 + k[1] * x2 * x2 + k[2] * x2 * x2 * x2
    }
}
