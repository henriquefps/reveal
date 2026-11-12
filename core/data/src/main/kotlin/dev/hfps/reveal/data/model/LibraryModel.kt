package dev.hfps.reveal.data.model

/**
 * What a source is: an added SAF folder, the one source that collects photos other apps hand to Reveal, or the one
 * source of photos only the sync server has (its NAS folders, other devices' photos; ADR 0018).
 */
enum class SourceKind { FOLDER, HANDOFF, SERVER }

/** Whether a source (a SAF folder) can be read right now. Stored by name in `sources.state`. */
enum class SourceState {
    /** The folder answers: browse, index, open. */
    AVAILABLE,

    /** Its volume (SD card, USB drive) is not mounted, or the folder is gone: the index and thumbnails stay. */
    UNPLUGGED,

    /** The persisted grant was revoked: the user has to add the folder again. */
    PERMISSION_LOST,
}

/** The pick/reject flag of a photo (Hoard ADR 0008/0009; see `XmpFields` for how it is stored in XMP). */
enum class PickFlag { NONE, PICK, REJECT }

/** What a file is to its photo: the RAW, the camera rendering (JPEG/HEIF/...), or an extra file with the same stem. */
enum class FileRole { RAW, RENDERED, OTHER }

/** Progress of the header read (EXIF) of a photo; the walk only lists files, the header is read afterwards. */
enum class MetadataState { PENDING, READ, FAILED }

/** A photo's file formats as the grid badge shows them. */
enum class PhotoFormat {
    /** A RAW with its camera JPEG (or HEIF): one photo, edited from the RAW. */
    RAW_PAIR,
    RAW,
    JPEG,
    HEIF,
    PNG,
    WEBP,
}

/**
 * What "unavailable" means, once (the SQL twin is `PhotoQuerySql.UNAVAILABLE_SQL`): a file of the photo cannot be read
 * AND nothing stands in for it (the sync server's original, a smart preview on the server, a local preview).
 */
object PhotoAvailability {
    fun isUnavailable(
        unreadable: Boolean,
        serverOriginal: Boolean,
        serverPreview: Boolean,
        localPreview: Boolean,
    ): Boolean = unreadable && !serverOriginal && !serverPreview && !localPreview
}

/** Grid order. Capture time falls back to the file's modification time when the header has none. */
enum class PhotoSort { CAPTURE_NEWEST, CAPTURE_OLDEST, NAME }

/**
 * Which photos a grid shows (FIX/L): the library home is one list of every photo, and everything else is a filter
 * ([PhotoFilters]). Only "Recent" is a view of its own.
 */
sealed interface LibraryScope {
    /**
     * Every photo: this device's (folders, hand-offs) and the Server photos that open (`PhotoQuerySql
     * .SERVER_OPENABLE_SQL`), one row per photo.
     */
    data object All : LibraryScope

    /** Photos opened in the editor recently, most recent first. */
    data object Recent : LibraryScope
}

/** One choice of the Source group. */
sealed interface SourceFilter {
    /** One added folder. */
    data class Folder(val sourceId: Long) : SourceFilter

    /** Photos other apps handed to Reveal (ADR 0011), folder photos a hand-off opened included. */
    data object FromApps : SourceFilter

    /** Photos only the sync server has: its NAS folders and other devices' photos (ADR 0018). */
    data object ServerOnly : SourceFilter
}

/** The Culling group. */
enum class CullingFilter {
    PICKED,

    /** Any rating (one star or more). */
    STARRED,
    REJECTED,
}

/** The Edits group. */
enum class EditFilter { EDITED, NOT_EDITED }

/** The Type group. */
enum class TypeFilter {
    /** Photos with a RAW (a pair counts). */
    RAW,

    /** Photos without a RAW: camera or phone JPEG, HEIF, PNG, WebP. */
    JPEG,
}

/** The Sync group (only while paired). */
enum class SyncFilter {
    /** The server holds the original of any file of the shot; Server-only photos count. */
    SYNCED,

    /** Everything else, uploads still waiting included. */
    NOT_SYNCED,
}

/**
 * The grid's combinable filters (FIX/L, ADR 0006 "Filters"): within a group the choices are OR, across groups AND; an
 * empty group does not narrow. Pure data, turned into SQL by `PhotoQuerySql`.
 */
data class PhotoFilters(
    val sources: Set<SourceFilter> = emptySet(),
    val culling: Set<CullingFilter> = emptySet(),
    val edits: Set<EditFilter> = emptySet(),
    val types: Set<TypeFilter> = emptySet(),
    val sync: Set<SyncFilter> = emptySet(),
) {
    val isEmpty: Boolean
        get() = sources.isEmpty() && culling.isEmpty() && edits.isEmpty() && types.isEmpty() && sync.isEmpty()

    /** How many choices are on (the Filters button's badge). */
    val count: Int get() = sources.size + culling.size + edits.size + types.size + sync.size

    /** The one folder the grid is narrowed to, if the Source group holds exactly a folder. */
    val singleFolder: Long? get() = (sources.singleOrNull() as? SourceFilter.Folder)?.sourceId

    /** Exactly "Opened from other apps": the grid groups the photos by hand-off. */
    val onlyFromApps: Boolean get() = sources == setOf(SourceFilter.FromApps)

    fun toggle(choice: SourceFilter) = copy(sources = sources.toggle(choice))

    fun toggle(choice: CullingFilter) = copy(culling = culling.toggle(choice))

    fun toggle(choice: EditFilter) = copy(edits = edits.toggle(choice))

    fun toggle(choice: TypeFilter) = copy(types = types.toggle(choice))

    fun toggle(choice: SyncFilter) = copy(sync = sync.toggle(choice))

    /** Folders that no longer exist leave the Source group. */
    fun withFolders(existing: Collection<Long>): PhotoFilters {
        val kept = sources.filterTo(LinkedHashSet()) { it !is SourceFilter.Folder || it.sourceId in existing }
        return if (kept.size == sources.size) this else copy(sources = kept)
    }

    companion object {
        val NONE = PhotoFilters()

        fun folder(sourceId: Long) = PhotoFilters(sources = setOf(SourceFilter.Folder(sourceId)))

        val FROM_APPS = PhotoFilters(sources = setOf(SourceFilter.FromApps))
        val SERVER_ONLY = PhotoFilters(sources = setOf(SourceFilter.ServerOnly))

        private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
    }
}

/** A grid query: view, filters, order and file-name search. Pure data, turned into SQL by `PhotoQuerySql`. */
data class PhotoQuery(
    val scope: LibraryScope = LibraryScope.All,
    val filters: PhotoFilters = PhotoFilters.NONE,
    val sort: PhotoSort = PhotoSort.CAPTURE_NEWEST,
    /** Case-insensitive substring of the file name; blank = no search. */
    val search: String = "",
    /**
     * Only photos Reveal cannot show at all (`PhotoAvailability`): "Try again" and the tests ask for them; the grid
     * never offers it as a filter (the placeholder marks them in place).
     */
    val unavailableOnly: Boolean = false,
    /**
     * Also the Server records that open nothing yet (no original, no preview: FIX/V keeps them unlisted). Sync tests
     * and diagnostics only; the grid never sets it.
     */
    val withUnopenable: Boolean = false,
) {
    companion object {
        /** One folder's photos. */
        fun ofSource(sourceId: Long) = PhotoQuery(filters = PhotoFilters.folder(sourceId))

        /** "Opened from other apps" (newest hand-off first). */
        val FROM_APPS = PhotoQuery(filters = PhotoFilters.FROM_APPS)

        /** Photos only the sync server has. */
        val SERVER_ONLY = PhotoQuery(filters = PhotoFilters.SERVER_ONLY)
    }
}
