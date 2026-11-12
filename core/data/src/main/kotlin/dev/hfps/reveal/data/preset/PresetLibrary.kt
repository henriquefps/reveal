package dev.hfps.reveal.data.preset

import dev.hfps.reveal.pipeline.preset.Preset
import java.io.File

/** Where a preset came from (docs/adr/0019 "Library"). */
enum class PresetOrigin {
    /** Shipped with the app ([BuiltInPresetSource]); not a database row. */
    BUILT_IN,

    /** Made on this device from an edit. */
    USER,

    /** Imported from a `.revealpreset` file. */
    IMPORTED,

    /** From a pack: renaming and moving are not offered (an update of the pack would undo them), deleting is. */
    PACK,

    /** Pulled from the sync server (another device's preset). */
    SYNC,
    ;

    /** The user can rename it and move it between groups. */
    val editable: Boolean get() = this == USER || this == IMPORTED || this == SYNC
}

/** What kind of group: the app's, the user's own, or a pack's. */
enum class GroupKind { BUILT_IN, USER, PACK }

/** An imported pack as the panel shows it (name, author, licence, link, cover, whether it is still on trial). */
data class PackInfo(
    val rowId: Long,
    val uuid: String,
    val name: String,
    val author: String,
    val version: Int,
    val licenceName: String,
    val licenceText: String,
    val link: String?,
    val cover: File?,
    val trial: Boolean,
)

/** One preset of the library with its place and marks. */
data class LibraryPreset(
    val preset: Preset,
    /** The `presets` row, null for a built-in. */
    val rowId: Long?,
    val groupUuid: String,
    val origin: PresetOrigin,
    val favourite: Boolean = false,
    val lastUsedAt: Long? = null,
) {
    val uuid: String get() = preset.id
    val name: String get() = preset.name
}

/** A group of the panel. A user group with an empty [name] is the default group ("My presets"). */
data class LibraryGroup(
    val uuid: String,
    val rowId: Long?,
    val name: String,
    val kind: GroupKind,
    val presets: List<LibraryPreset>,
    val pack: PackInfo? = null,
)

/** The whole preset library: built-ins first, then the user's and the packs' groups in their order. */
data class PresetLibrary(val groups: List<LibraryGroup> = emptyList()) {
    /** Every preset, in group order. */
    val all: List<LibraryPreset> by lazy { groups.flatMap { it.presets } }

    private val byUuid: Map<String, LibraryPreset> by lazy { all.associateBy { it.uuid } }

    /** The favourites in library order. */
    val favourites: List<LibraryPreset> by lazy { all.filter { it.favourite } }

    /** The last applied presets, newest first (at most [RECENTS]). */
    val recents: List<LibraryPreset> by lazy {
        all.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(RECENTS)
    }

    /** The user's groups (where "Create preset" can put a preset). */
    val userGroups: List<LibraryGroup> get() = groups.filter { it.kind == GroupKind.USER }

    /** Packs on trial (the panel shows a Keep / Remove banner). */
    val trialPacks: List<PackInfo> get() = groups.mapNotNull { it.pack }.filter { it.trial }.distinctBy { it.uuid }

    operator fun get(uuid: String): LibraryPreset? = byUuid[uuid]

    companion object {
        const val RECENTS: Int = 12
    }
}

/** A group of built-in presets (M3/B provides them). */
data class BuiltInGroup(val uuid: String, val name: String, val presets: List<Preset>)

/**
 * The app's own presets (M3/B: designed looks and smart presets). They are not rows of the database: they come with
 * the app version, and only their marks (favourite, last used) are stored, by preset id.
 */
fun interface BuiltInPresetSource {
    fun groups(): List<BuiltInGroup>

    companion object {
        /** No built-in presets (the default until M3/B binds its own). */
        val NONE: BuiltInPresetSource = BuiltInPresetSource { emptyList() }
    }
}
