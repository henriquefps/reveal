package dev.hfps.reveal.data.model

/** What a source is: an added SAF folder, or the one source that collects photos other apps hand to Reveal. */
enum class SourceKind { FOLDER, HANDOFF }

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

/** Grid filters (`docs/plan/product.md`, Library). [RATED] uses the query's minimum rating. */
enum class PhotoFilter {
    ALL,
    EDITED,
    NOT_EDITED,
    PICKED,
    RATED,

    /** Photos with a RAW (a pair counts). */
    RAW,

    /** Photos without a RAW: camera or phone JPEG, HEIF, PNG, WebP. */
    JPEG,
}

/** Grid order. Capture time falls back to the file's modification time when the header has none. */
enum class PhotoSort { CAPTURE_NEWEST, CAPTURE_OLDEST, NAME }

/** Which photos a grid shows. */
sealed interface LibraryScope {
    /** Every photo of every source. */
    data object All : LibraryScope

    /** Photos opened in the editor recently, most recent first. */
    data object Recent : LibraryScope

    /** Photos other apps handed to Reveal (ADR 0011), most recent hand-off first. */
    data object FromApps : LibraryScope

    /** One added folder. */
    data class Source(val sourceId: Long) : LibraryScope
}

/** A grid query: scope, filter, order and file-name search. Pure data, turned into SQL by `PhotoQuerySql`. */
data class PhotoQuery(
    val scope: LibraryScope = LibraryScope.All,
    val filter: PhotoFilter = PhotoFilter.ALL,
    /** Minimum stars for [PhotoFilter.RATED], 1..5. */
    val minRating: Int = 1,
    val sort: PhotoSort = PhotoSort.CAPTURE_NEWEST,
    /** Case-insensitive substring of the file name; blank = no search. */
    val search: String = "",
)
