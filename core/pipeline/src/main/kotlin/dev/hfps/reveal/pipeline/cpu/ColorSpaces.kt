package dev.hfps.reveal.pipeline.cpu

/**
 * Colorimetry used by the pipeline: RGB primaries to XYZ matrices derived from chromaticities, the D65 white point
 * and Bradford chromatic adaptation. Everything is derived from published chromaticities in double precision rather
 * than pasted as rounded matrices, so the constants are reproducible (docs/adr/0002).
 *
 * Sources: IEC 61966-2-1 (sRGB), SMPTE EG 432-1 (Display P3), ITU-R BT.2020, and Bruce Lindbloom's
 * "RGB/XYZ Matrices" and "Chromatic Adaptation" pages for the derivation and the Bradford matrix (Lam 1985).
 */
object ColorSpaces {
    /** CIE xy chromaticity. */
    data class Xy(val x: Double, val y: Double)

    /** An RGB colour space defined by its primaries and white point. */
    class Rgb(val red: Xy, val green: Xy, val blue: Xy, val white: Xy) {
        /** Linear RGB to XYZ (Y of the white = 1). */
        val toXyz: Mat3 by lazy { primariesToXyz(this) }

        /** XYZ to linear RGB. */
        val fromXyz: Mat3 by lazy { toXyz.inverse() }
    }

    /** CIE D65 white point (xy 0.3127, 0.3290, the value shared by sRGB, Display P3 and Rec.2020). */
    val D65: Xy = Xy(0.3127, 0.3290)

    /** D65 white as XYZ with Y = 1. */
    val D65_XYZ: DoubleArray = xyToXyz(D65)

    /** sRGB / Rec.709 primaries. */
    val SRGB: Rgb = Rgb(Xy(0.640, 0.330), Xy(0.300, 0.600), Xy(0.150, 0.060), D65)

    /** Display P3 primaries (DCI-P3 primaries with D65 white). */
    val DISPLAY_P3: Rgb = Rgb(Xy(0.680, 0.320), Xy(0.265, 0.690), Xy(0.150, 0.060), D65)

    /** Rec.2020 primaries; the pipeline's working space (docs/adr/0002). */
    val REC2020: Rgb = Rgb(Xy(0.708, 0.292), Xy(0.170, 0.797), Xy(0.131, 0.046), D65)

    /** Bradford cone response matrix (XYZ to sharpened RGB). */
    internal val bradford = Mat3(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    /** XYZ (Y = 1) of a chromaticity. */
    fun xyToXyz(xy: Xy): DoubleArray = doubleArrayOf(xy.x / xy.y, 1.0, (1.0 - xy.x - xy.y) / xy.y)

    /**
     * Bradford chromatic adaptation matrix mapping XYZ colours seen under the white [sourceWhite] to the
     * corresponding colours under [destWhite] (both XYZ): `M^-1 * diag(dst_cone / src_cone) * M`.
     */
    fun bradfordAdaptation(sourceWhite: DoubleArray, destWhite: DoubleArray): Mat3 {
        val s = bradford.times(sourceWhite)
        val d = bradford.times(destWhite)
        return bradford.inverse() * Mat3.diagonal(d[0] / s[0], d[1] / s[1], d[2] / s[2]) * bradford
    }

    private fun primariesToXyz(space: Rgb): Mat3 {
        val r = xyToXyz(space.red)
        val g = xyToXyz(space.green)
        val b = xyToXyz(space.blue)
        val primaries = Mat3(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val w = xyToXyz(space.white)
        val scale = primaries.inverse().times(w)
        return primaries * Mat3.diagonal(scale[0], scale[1], scale[2])
    }
}
