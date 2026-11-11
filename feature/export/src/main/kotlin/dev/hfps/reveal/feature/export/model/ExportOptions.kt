package dev.hfps.reveal.feature.export.model

import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.export.ExportSize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Output file formats (docs/adr/0009). PNG, 16-bit TIFF and an original copy land in M5: add an entry, its encoder
 * (`ImageEncoders`) and its strings; nothing else switches on the list.
 */
@Serializable
enum class ExportFormat(val mimeType: String, val extension: String, val hasQuality: Boolean) {
    @SerialName("jpeg")
    JPEG("image/jpeg", "jpg", hasQuality = true),

    @SerialName("heif")
    HEIF("image/heic", "heic", hasQuality = true),
}

/** The colour space of the file (an ICC profile in JPEG). HEIF is sRGB in M1 (docs/adr/0009, "HEIF"). */
@Serializable
enum class ExportColour(val space: OutputSpace) {
    @SerialName("srgb")
    SRGB(OutputSpace.SRGB),

    @SerialName("p3")
    DISPLAY_P3(OutputSpace.DISPLAY_P3),
}

/** How much of the original's metadata goes into the file. */
@Serializable
enum class MetadataLevel {
    /** Camera, exposure, lens, location (unless removed), author, copyright, rating. */
    @SerialName("all")
    ALL,

    /** Author and copyright only (plus the capture time when kept). */
    @SerialName("copyright")
    COPYRIGHT,

    /** Nothing but the colour profile (plus the capture time when kept). */
    @SerialName("none")
    NONE,
}

@Serializable
data class MetadataOptions(
    val level: MetadataLevel = MetadataLevel.ALL,
    /** Drops the GPS block even when [level] is [MetadataLevel.ALL]. */
    val removeLocation: Boolean = false,
    /** Keeps the capture date and time whatever the [level]. */
    val keepCaptureTime: Boolean = true,
)

/** Where the files go. */
@Serializable
sealed interface ExportDestination {
    /** MediaStore `Pictures/Reveal` (the system gallery). */
    @Serializable
    @SerialName("pictures")
    data object Pictures : ExportDestination

    /** A folder chosen through SAF (persisted grant, remembered). */
    @Serializable
    @SerialName("folder")
    data class Folder(val treeUri: String, val name: String) : ExportDestination

    /** The share sheet, from a temporary file in the cache that is deleted afterwards. */
    @Serializable
    @SerialName("share")
    data object Share : ExportDestination
}

/** File name templates (the [NamingOptions.suffix] is appended to every one). */
@Serializable
enum class NameTemplate {
    /** `DSC01234-edit.jpg`. */
    @SerialName("original")
    ORIGINAL,

    /** `20260807-143012-edit.jpg` from the capture time (the original name without one). */
    @SerialName("date")
    DATE,

    /** `DSC01234-0007-edit.jpg`: the original name and a running number. */
    @SerialName("sequence")
    SEQUENCE,
}

@Serializable
data class NamingOptions(val template: NameTemplate = NameTemplate.ORIGINAL, val suffix: String = DEFAULT_SUFFIX) {
    companion object {
        const val DEFAULT_SUFFIX = "-edit"
    }
}

/** Everything the export sheet sets; serialised into the job and remembered between exports. */
@Serializable
data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = DEFAULT_QUALITY,
    val colour: ExportColour = ExportColour.SRGB,
    val size: ExportSize = ExportSize.Original,
    val metadata: MetadataOptions = MetadataOptions(),
    val destination: ExportDestination = ExportDestination.Pictures,
    val naming: NamingOptions = NamingOptions(),
) {
    init {
        require(quality in 1..MAX_QUALITY) { "quality must be 1..100" }
    }

    /** The colour actually written: HEIF is sRGB in M1. */
    val effectiveColour: ExportColour get() = if (format == ExportFormat.HEIF) ExportColour.SRGB else colour

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_QUALITY = 92
        const val MAX_QUALITY = 100

        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Options from [json]; defaults when it is missing or from an incompatible version. */
        fun decode(json: String?): ExportOptions =
            json?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: ExportOptions()
    }
}
