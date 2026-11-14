package dev.hfps.reveal.feature.export.model

import dev.hfps.reveal.data.export.StoredExportPreset
import dev.hfps.reveal.pipeline.export.ExportSize

/**
 * A named combination of the export sheet's choices (docs/adr/0025 "Export presets"): a built-in one (code, localised
 * name) or one the user saved (Room, synced). [key] identifies it in the sheet: `builtin:<id>` or the user's uuid.
 */
sealed interface ExportPreset {
    val key: String
    val options: ExportOptions

    data class BuiltIn(val kind: BuiltInExportPreset) : ExportPreset {
        override val key: String get() = BUILT_IN_PREFIX + kind.id
        override val options: ExportOptions get() = kind.options
    }

    data class User(val uuid: String, val name: String, override val options: ExportOptions) : ExportPreset {
        override val key: String get() = uuid
    }

    companion object {
        const val BUILT_IN_PREFIX = "builtin:"
    }
}

/**
 * The built-in export presets of `docs/plan/product.md` "Export and share". Their ids are stable (a remembered choice
 * names them); their options are what the name promises, nothing device-specific (no folder).
 */
enum class BuiltInExportPreset(val id: String, val options: ExportOptions) {
    /** 1080 x 1350 (fit), sRGB, copyright only, no location, straight to the share sheet. */
    INSTAGRAM(
        "instagram",
        ExportOptions(
            format = ExportFormat.JPEG,
            quality = 90,
            colour = ExportColour.SRGB,
            size = ExportSize.INSTAGRAM_PORTRAIT,
            metadata = MetadataOptions(level = MetadataLevel.COPYRIGHT, removeLocation = true, keepCaptureTime = true),
            destination = ExportDestination.Share,
        ),
    ),

    /** 2048 px long edge, sRGB, copyright only, no location, into Pictures/Reveal. */
    WEB(
        "web",
        ExportOptions(
            format = ExportFormat.JPEG,
            quality = 85,
            colour = ExportColour.SRGB,
            size = ExportSize.WEB,
            metadata = MetadataOptions(level = MetadataLevel.COPYRIGHT, removeLocation = true, keepCaptureTime = true),
            destination = ExportDestination.Pictures,
        ),
    ),

    /** Full resolution, JPEG quality 100, every metadata field, into Pictures/Reveal. */
    FULL_QUALITY(
        "full",
        ExportOptions(
            format = ExportFormat.JPEG,
            quality = ExportOptions.MAX_QUALITY,
            colour = ExportColour.SRGB,
            size = ExportSize.Original,
            metadata = MetadataOptions(level = MetadataLevel.ALL),
            destination = ExportDestination.Pictures,
        ),
    ),
}

object ExportPresets {
    val builtIns: List<ExportPreset> = BuiltInExportPreset.entries.map { ExportPreset.BuiltIn(it) }

    /** The user's stored presets as sheet presets (options decoded with defaults for anything missing). */
    fun user(stored: List<StoredExportPreset>): List<ExportPreset> =
        stored.map { ExportPreset.User(it.uuid, it.name, ExportOptions.decode(it.optionsJson)) }

    /** Every preset the sheet offers: built-ins first, then the user's in their order. */
    fun all(stored: List<StoredExportPreset>): List<ExportPreset> = builtIns + user(stored)

    /** The first preset whose options are exactly [options] (the chip shown as selected), or null. */
    fun matching(options: ExportOptions, presets: List<ExportPreset>): ExportPreset? =
        presets.firstOrNull { it.options == options }

    /**
     * [preset]'s options made usable on this device: a folder whose grant is not here (a preset from another device, or
     * a folder since forgotten) falls back to Pictures/Reveal, HEIF without an encoder to JPEG. The naming suffix and
     * template are the preset's.
     */
    fun resolve(preset: ExportPreset, heif: Boolean, folderGranted: (String) -> Boolean): ExportOptions {
        var options = preset.options
        val folder = options.destination as? ExportDestination.Folder
        if (folder != null &&
            !folderGranted(folder.treeUri)
        ) {
            options = options.copy(destination = ExportDestination.Pictures)
        }
        if (!heif && options.format == ExportFormat.HEIF) options = options.copy(format = ExportFormat.JPEG)
        return options
    }
}
