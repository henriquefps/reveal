package dev.hfps.reveal.pipeline.cpu

import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.NoiseInfo
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The noise model of the Detail operator (docs/adr/0012, "Noise model"): how noisy a photo is expected to be relative
 * to a reference capture, from its EXIF ISO and its camera, so the luminance and colour noise-reduction amounts mean
 * the same at ISO 200 and at ISO 12800 (the thresholds scale with the expected noise, so an amount removes the same
 * share of it).
 *
 * After demosaicing and the tone curve, the noise of a mid-tone is dominated by photon shot noise amplified by the
 * ISO gain: its standard deviation grows as `sqrt(ISO)`, and at a given ISO it falls with the pixel's area (a pixel
 * collects photons in proportion to its area, so the relative noise goes as `1 / pitch`). So:
 *
 * `factor = sqrt(ISO / REFERENCE_ISO) * REFERENCE_PITCH_UM / pitch(camera)`, clamped to [[MIN_FACTOR], [MAX_FACTOR]].
 *
 * The reference (factor 1) is a full-frame 24 MP sensor (5.9 um, the CC0 Sony A7C files the amount curve was
 * calibrated on) at ISO 5000, the geometric middle of the calibration files (ISO 2000 and 12800). Defaults:
 * - **unknown ISO** (a RAW without the tag): factor 1, the M2 behaviour;
 * - **unknown camera**: [DEFAULT_PITCH_UM], an APS-C 24 MP sensor (the commonest interchangeable-lens format);
 * - **display-referred input** (JPEG, HEIF, PNG: the camera or an editor already denoised it and the decoders do not
 *   report its ISO): [DISPLAY_REFERRED_FACTOR].
 */
object NoiseModel {
    /** ISO of the reference capture (factor 1 on the reference sensor). */
    const val REFERENCE_ISO: Double = 5000.0

    /** Pixel pitch of the reference sensor (full frame, 24 MP), micrometres. */
    const val REFERENCE_PITCH_UM: Double = 5.9

    /** Pixel pitch assumed for a camera that is not in [PITCH_UM] (APS-C, 24 MP). */
    const val DEFAULT_PITCH_UM: Double = 3.9

    /** Factor for display-referred input: already denoised by its camera, ISO not reported. */
    const val DISPLAY_REFERRED_FACTOR: Float = 0.5f

    const val MIN_FACTOR: Float = 0.1f
    const val MAX_FACTOR: Float = 4f

    /**
     * Pixel pitch in micrometres by normalised model name (upper case, maker prefix dropped): sensor width over
     * pixel columns from the makers' specifications. Unknown models use [DEFAULT_PITCH_UM].
     */
    val PITCH_UM: Map<String, Double> = mapOf(
        // Sony APS-C 24-26 MP
        "ZV-E10" to 3.9, "ZV-E10M2" to 3.8, "ILCE-6000" to 3.9, "ILCE-6100" to 3.9, "ILCE-6400" to 3.9,
        "ILCE-6600" to 3.9, "ILCE-6700" to 3.8,
        // Sony full frame
        "ILCE-7C" to 5.9, "ILCE-7M3" to 5.9, "ILCE-7M4" to 5.1, "ILCE-7CM2" to 5.1, "ILCE-7RM3" to 4.5,
        "ILCE-7RM4" to 3.8, "ILCE-7RM5" to 3.8, "ILCE-7SM3" to 8.4, "ILCE-1" to 4.2, "ILCE-9" to 5.9,
        // Nikon, Canon, Fujifilm (a few common bodies)
        "D5600" to 3.9, "D7500" to 4.2, "D750" to 6.0, "D850" to 4.3, "Z 6" to 5.9, "Z 6_2" to 5.9, "Z 7" to 4.3,
        "Z 50" to 4.2, "EOS R6" to 6.6, "EOS R5" to 4.4, "EOS R" to 5.4, "EOS RP" to 5.7, "EOS 90D" to 3.2,
        "X-T3" to 3.8, "X-T4" to 3.8, "X-T5" to 3.0,
    )

    /** The noise factor of an input of [kind] with [noise] (see the object comment). */
    fun factor(kind: InputKind, noise: NoiseInfo?): Float {
        val iso = noise?.iso?.takeIf { it > 0 }
        if (iso == null) return if (kind == InputKind.DISPLAY_REFERRED) DISPLAY_REFERRED_FACTOR else 1f
        val pitch = pitch(noise.model)
        val f = sqrt(iso / REFERENCE_ISO) * REFERENCE_PITCH_UM / pitch
        val scaled = if (kind == InputKind.DISPLAY_REFERRED) f * DISPLAY_REFERRED_FACTOR else f
        return min(max(scaled.toFloat(), MIN_FACTOR), MAX_FACTOR)
    }

    /** The pixel pitch of [model] (as written in the file, any case, maker prefix allowed), or the default. */
    fun pitch(model: String?): Double {
        val m = model?.trim()?.uppercase(Locale.ROOT).orEmpty()
        // "NIKON D5600", "Canon EOS R6": also tried without the maker's word.
        return PITCH_UM[m] ?: PITCH_UM[m.substringAfter(' ', "")]
            ?: PITCH_UM.entries.firstOrNull { m.endsWith(" ${it.key}") }?.value ?: DEFAULT_PITCH_UM
    }
}
