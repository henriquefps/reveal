@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.optics

import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * Lookup tables over the normalised radius `r` in `[0, R_MAX]` (half diagonals), [SIZE] entries, read with linear
 * interpolation between entries and flat beyond the last ([lookup]): the **definition** of the lens operators, so
 * the CPU reference and the shaders interpolate the same floats (docs/adr/0015, "Lens tables").
 */
object LensTables {
    /** Entries per table. */
    const val SIZE: Int = 256

    /** Largest radius the tables cover; corners are at 1, the margin covers points just outside the image. */
    const val R_MAX: Float = 1.5f

    /** Entries per unit radius: `(SIZE - 1) / R_MAX`. */
    const val PER_RADIUS: Float = (SIZE - 1) / R_MAX

    /**
     * Entry [channel] of the interleaved [table] (`SIZE * stride`) at radius [r]: `t = r * PER_RADIUS`, clamped to
     * `[0, SIZE - 1]`, `i = floor(t)`, `v[i] + (t - i) * (v[i + 1] - v[i])`.
     */
    fun lookup(table: FloatArray, stride: Int, channel: Int, r: Float): Float {
        val t = (r * PER_RADIUS).coerceIn(0f, (SIZE - 1).toFloat())
        val i = minOf(t.toInt(), SIZE - 2)
        val f = t - i
        val a = table[i * stride + channel]
        val b = table[(i + 1) * stride + channel]
        return a + f * (b - a)
    }

    /** The radius of entry [i]. */
    fun radius(i: Int): Double = i.toDouble() / PER_RADIUS.toDouble()
}

/**
 * The lens's coordinate correction for one photo: per channel, the source radius divided by the corrected radius,
 * `lambda_c(r)`, as an RGBA table (`a` unused). A corrected-image point `q` reads channel `c` at
 * `centre + lambda_c(|q - centre| / halfDiagonal) * (q - centre)`. Built in double precision from the profile at the
 * recipe's amount, the CA source, then **auto-scaled** (`lambda(r) = l(r / s) / s`, `s` solved so that the image
 * border just stays inside the source: no empty edges, the widest view), rounded once to float.
 *
 * @property scale the auto-scale `s` (above 1 zooms in).
 */
class LensWarp private constructor(val table: FloatArray, val scale: Double, val chromatic: Boolean) {
    /** `lambda_c(r)` as the operators read it ([LensTables.lookup]). */
    fun lambda(channel: Int, r: Float): Float = LensTables.lookup(table, STRIDE, channel, r)

    override fun equals(other: Any?): Boolean =
        other is LensWarp && table.contentEquals(other.table) && chromatic == other.chromatic

    override fun hashCode(): Int = table.contentHashCode() * 31 + chromatic.hashCode()

    override fun toString(): String = "LensWarp(scale=$scale, chromatic=$chromatic)"

    companion object {
        const val STRIDE: Int = 4
        private const val CA_UNIT = 1e-4
        private const val BISECTIONS = 60
        private const val BORDER_SAMPLES = 128

        private data class Key(
            val profile: LensProfile?,
            val lensCorrections: Boolean,
            val distortion: Float,
            val removeCa: Boolean,
            val caRed: Float,
            val caBlue: Float,
            val width: Int,
            val height: Int,
        )

        @Volatile
        private var last: Pair<Key, LensWarp?>? = null

        /**
         * [of], remembering the last result: the editor asks for the same warp on every frame (the auto-scale's
         * search is the costly part). Safe from any thread.
         */
        fun cached(profile: LensProfile?, optics: Optics, width: Int, height: Int): LensWarp? {
            if (profile == null && !optics.removeCa) return null
            val key = Key(
                profile, optics.lensCorrections, optics.distortionPercent, optics.removeCa, optics.caRed, optics.caBlue,
                width, height,
            )
            last?.let { (k, v) -> if (k == key) return v }
            return of(profile, optics, width, height).also { last = key to it }
        }

        /**
         * The warp of [optics] with [profile] on a [width] x [height] photo, or `null` when it moves nothing (lens
         * corrections off or without distortion data, and no CA removal).
         */
        fun of(profile: LensProfile?, optics: Optics, width: Int, height: Int): LensWarp? {
            val distortionOn = optics.lensCorrections && profile?.hasDistortion == true
            val amount = RecipeRanges.lensDistortion.clamp(optics.distortionPercent) / 100.0
            val profileCa = optics.removeCa && profile?.hasChromatic == true
            val autoCa = optics.removeCa && !profileCa && (optics.caRed != 0f || optics.caBlue != 0f)
            val distortion: (Double) -> Double = if (distortionOn && amount != 0.0) {
                { r -> 1.0 + amount * (checkNotNull(profile).distortion(r) - 1.0) }
            } else {
                { 1.0 }
            }
            val active = (distortionOn && amount != 0.0) || profileCa || autoCa
            if (!active) return null
            val chromatic: (Int, Double) -> Double = when {
                profileCa -> { c, r -> checkNotNull(profile).chromatic(c, r) }
                autoCa -> { c, _ ->
                    when (c) {
                        0 -> 1.0 + optics.caRed * CA_UNIT
                        2 -> 1.0 + optics.caBlue * CA_UNIT
                        else -> 1.0
                    }
                }
                else -> { _, _ -> 1.0 }
            }
            val l = { c: Int, r: Double -> distortion(r) * chromatic(c, r) }
            val s = autoScale(l, width, height)
            val table = FloatArray(LensTables.SIZE * STRIDE)
            for (i in 0 until LensTables.SIZE) {
                val r = LensTables.radius(i)
                for (c in 0 until 3) table[i * STRIDE + c] = (l(c, r / s) / s).toFloat()
                table[i * STRIDE + 3] = 1f
            }
            return LensWarp(table, s, profileCa || autoCa)
        }

        /**
         * The zoom `s` with `max over the border of l_c(r / s) / s = 1`: every border point of the corrected image
         * reads the source at or inside its own border (rectangles are star-shaped about the centre), and at least one
         * reads exactly on it. The border's radii run from half the short side to the corner.
         */
        internal fun autoScale(l: (Int, Double) -> Double, width: Int, height: Int): Double {
            val half = hypot(width.toDouble(), height.toDouble()) / 2
            val shortest = min(width, height) / 2.0 / half
            fun excess(s: Double): Double {
                var worst = 0.0
                for (k in 0..BORDER_SAMPLES) {
                    val r = shortest + (1 - shortest) * k / BORDER_SAMPLES
                    for (c in 0 until 3) worst = maxOf(worst, l(c, r / s) / s)
                }
                return worst - 1
            }
            var lo = 0.5
            var hi = 2.0
            repeat(BISECTIONS) {
                val mid = (lo + hi) / 2
                if (excess(mid) > 0) lo = mid else hi = mid
            }
            return hi
        }
    }
}

/**
 * The vignetting correction for one photo (slot 3, docs/adr/0015): the gain `1 / vignetting(r)^amount` at the source
 * radius, as a [LensTables] table with one entry per radius. Resolution independent (no auto-scale).
 */
class VignetteTable private constructor(val gains: FloatArray) {
    fun gain(r: Float): Float = LensTables.lookup(gains, 1, 0, r)

    override fun equals(other: Any?): Boolean = other is VignetteTable && gains.contentEquals(other.gains)

    override fun hashCode(): Int = gains.contentHashCode()

    companion object {
        /** The correction of [optics] with [profile], or `null` when it is off, has no data or a zero amount. */
        fun of(profile: LensProfile?, optics: Optics): VignetteTable? {
            if (!optics.lensCorrections || profile?.hasVignetting != true) return null
            val amount = RecipeRanges.lensVignetting.clamp(optics.vignettingPercent) / 100.0
            if (amount == 0.0) return null
            val gains = FloatArray(LensTables.SIZE) { i ->
                (1.0 / profile.vignetting(LensTables.radius(i)).coerceAtLeast(MIN_ILLUMINATION).pow(amount)).toFloat()
            }
            return VignetteTable(gains)
        }

        private const val MIN_ILLUMINATION = 1e-4
    }
}
