// Numeric and geometric code: early returns validate, loops mirror the shaders, constants are the maths.
@file:Suppress("ReturnCount")

package dev.hfps.reveal.pipeline.input

import dev.hfps.image.color.ColorInfo
import dev.hfps.image.color.RawColorInfo
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.optics.LensfunDatabase
import dev.hfps.reveal.pipeline.optics.ShotOptics
import dev.hfps.reveal.pipeline.optics.SonyLensProfile

/**
 * Finds a photo's lens profile (docs/adr/0015, "Lens profiles"): the correction data the camera embedded in the RAW
 * (Sony ARW) first, else the lensfun database matched by camera, lens, focal length and aperture from the EXIF.
 * `null` when neither knows the lens. Never throws (a bad database entry is no profile).
 */
object LensResolver {
    fun resolve(
        info: ColorInfo?,
        metadata: PhotoMetadata?,
        database: () -> LensfunDatabase = LensfunDatabase::shipped,
    ): LensProfile? {
        val raw = info as? RawColorInfo
        val name = metadata?.lens?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(info?.make, info?.model).joinToString(" ").ifBlank { "?" }
        raw?.lensCorrection?.let { c ->
            SonyLensProfile.of(name, c.distortion, c.vignetting, c.chromaticAberration)?.let { return it }
        }
        val meta = metadata ?: return null
        val shot = ShotOptics(
            make = meta.make ?: info?.make,
            model = meta.model ?: info?.model,
            lens = meta.lens,
            focalLength = meta.focalLength,
            fNumber = meta.fNumber,
            focalLength35 = meta.focalLength35,
        )
        return runCatching { database().find(shot) }.getOrNull()
    }
}
