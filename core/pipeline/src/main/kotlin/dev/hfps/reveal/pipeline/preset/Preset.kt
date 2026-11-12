package dev.hfps.reveal.pipeline.preset

import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/**
 * A preset (docs/adr/0019): metadata, the recipe [patch] it sets and an optional [smart] part.
 *
 * @property id stable version 4 UUID (lower-case, dashes): the preset's identity in files, the database and sync.
 * @property name shown under the thumbnail; 1..[PresetLimits.NAME] characters.
 * @property group the group it belongs to, by name (files) — the library keeps groups by id.
 * @property author who made it (free text, may be empty).
 * @property version the preset's own revision, counted by its author (a pack's update replaces lower versions).
 * @property createdAt Unix milliseconds.
 */
data class Preset(
    val id: String,
    val name: String,
    val patch: PresetPatch,
    val group: String = "",
    val author: String = "",
    val version: Int = 1,
    val createdAt: Long = 0L,
    val smart: SmartPart? = null,
) {
    init {
        require(PresetIds.isValid(id)) { "preset id must be a UUID" }
        require(name.isNotBlank() && name.length <= PresetLimits.NAME) { "preset name must be 1..${PresetLimits.NAME}" }
        require(group.length <= PresetLimits.NAME && author.length <= PresetLimits.NAME) { "group/author too long" }
        require(version >= 1) { "preset version starts at 1" }
    }

    /**
     * [before] with this preset applied at [strength] (0..2). A [smart] part is resolved by [smart] first (its
     * correction is computed from the photo and is not scaled), then the style patch is scaled on top.
     */
    fun apply(
        before: Recipe,
        strength: Float = 1f,
        context: ApplyContext = ApplyContext(),
        smartCorrection: SmartCorrection? = null,
    ): Recipe {
        val corrected = smart?.let { part -> smartCorrection?.correct(this, part, before) } ?: before
        return patch.apply(corrected, strength, context)
    }
}

/**
 * The smart part of a preset (docs/plan/product.md "Smart presets", docs/adr/0019 "Smart slot"): correction rules
 * computed from each photo before the style is applied. M3/A only carries it: [rules] is the file's `smart` object,
 * kept verbatim (validated as JSON and by size). The photo-analysis workstream (M3/B) defines its schema and
 * provides the [SmartCorrection] that reads it; until then a preset with a smart part applies its style only.
 */
data class SmartPart(val rules: JsonObject)

/** Resolves a [SmartPart] on one photo: [before] with the correction (exposure, white balance, …) applied. */
fun interface SmartCorrection {
    fun correct(preset: Preset, smart: SmartPart, before: Recipe): Recipe
}

/** Ids of presets, groups and packs. */
object PresetIds {
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun newId(): String = UUID.randomUUID().toString()

    /** A version 4 UUID, lower-case with dashes. */
    fun isValid(id: String): Boolean = UUID_RE.matches(id)
}

/** Size limits of preset and pack files (docs/adr/0019, "Limits"). */
object PresetLimits {
    /** Characters in a preset, group, pack or author name. */
    const val NAME: Int = 80

    /** Characters in a pack link. */
    const val LINK: Int = 512

    /** Bytes of one `.revealpreset` file. */
    const val PRESET_BYTES: Int = 256 * 1024

    /** Bytes of a preset's smart part (as JSON). */
    const val SMART_BYTES: Int = 64 * 1024

    /** Bytes of a pack manifest. */
    const val MANIFEST_BYTES: Int = 256 * 1024

    /** Bytes of a pack's licence text. */
    const val LICENCE_BYTES: Int = 64 * 1024

    /** Bytes of a pack's cover image. */
    const val COVER_BYTES: Int = 4 * 1024 * 1024

    /** Bytes of a whole `.revealpack` (compressed, as received). */
    const val PACK_BYTES: Long = 32L * 1024 * 1024

    /** Bytes of all a pack's entries once decompressed. */
    const val PACK_UNCOMPRESSED_BYTES: Long = 48L * 1024 * 1024

    /** Entries in a pack zip. */
    const val PACK_ENTRIES: Int = 1100

    /** Presets in one pack. */
    const val PACK_PRESETS: Int = 1000

    /** Groups in one pack. */
    const val PACK_GROUPS: Int = 64
}

/** Why a preset or pack file was refused (stable codes; the UI maps them to strings). */
enum class PresetError {
    /** Not JSON / not a zip / missing required members. */
    MALFORMED,

    /** Not a Reveal preset or pack (`format` differs). */
    NOT_A_PRESET,

    /** Written by a newer Reveal (format or recipe version above this app's). */
    NEWER_VERSION,

    /** Over a size or count limit. */
    TOO_LARGE,

    /** A value of the wrong type, out of its domain, or a bad id/name/link. */
    INVALID_VALUE,

    /** An unsafe zip entry name (absolute, `..`, backslash, duplicate). */
    UNSAFE_ENTRY,
}

/** A preset or pack file that cannot be imported. */
class PresetFormatException(val error: PresetError, message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
