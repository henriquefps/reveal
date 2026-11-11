package dev.hfps.reveal.feature.library

import androidx.compose.runtime.Immutable
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort

/** The pseudo-sources of the sources list besides the added folders. */
enum class SpecialSource { ALL, RECENT, FROM_APPS }

/** What the grid shows: an added folder, or one of the special entries. */
sealed interface LibrarySelection {
    data class Special(val which: SpecialSource) : LibrarySelection

    data class Folder(val sourceId: Long) : LibrarySelection
}

/** One entry of the grid: a date header (spans the row) or a photo. */
@Immutable
sealed interface GridEntry {
    val key: Any

    /** [day] = days since the epoch of the camera's local date; [time] any millis of that day (for formatting). */
    data class Header(val day: Long, val time: Long) : GridEntry {
        override val key: Any get() = "h$day"
    }

    data class Photo(val row: PhotoRow) : GridEntry {
        override val key: Any get() = row.id
    }
}

@Immutable
data class LibraryUiState(
    val sources: List<SourceItem> = emptyList(),
    /** Null until the sources are known (avoids flashing the empty state). */
    val sourcesLoaded: Boolean = false,
    /** Null = the sources list is shown on its own (phone). */
    val selection: LibrarySelection? = null,
    val query: PhotoQuery = PhotoQuery(),
    val entries: List<GridEntry> = emptyList(),
    val photoCount: Int = 0,
    val photosLoaded: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val searchOpen: Boolean = false,
) {
    val selecting: Boolean get() = selected.isNotEmpty()

    val currentSource: SourceItem?
        get() = (selection as? LibrarySelection.Folder)?.let { s -> sources.firstOrNull { it.id == s.sourceId } }

    /** True when any filter, rating or search narrows the grid. */
    val filtered: Boolean
        get() = query.filter != dev.hfps.reveal.data.model.PhotoFilter.ALL || query.search.isNotBlank()

    val photoIds: List<Long> get() = entries.mapNotNull { (it as? GridEntry.Photo)?.row?.id }
}

/** Everything the library screen can ask for (stateless content for screenshots and tests). */
@Immutable
data class LibraryActions(
    val onSelect: (LibrarySelection?) -> Unit = {},
    val onAddFolder: () -> Unit = {},
    val onOpenPhoto: () -> Unit = {},
    val onRescan: (Long) -> Unit = {},
    val onRemoveSource: (Long) -> Unit = {},
    val onQuery: (PhotoQuery) -> Unit = {},
    val onSearchOpen: (Boolean) -> Unit = {},
    val onOpen: (PhotoRow) -> Unit = {},
    val onSelectionChange: (Set<Long>) -> Unit = {},
    val onRate: (Int) -> Unit = {},
    val onFlag: (dev.hfps.reveal.data.model.PickFlag) -> Unit = {},
    val onRemovePhotos: () -> Unit = {},
    val onGallery: () -> Unit = {},
    val onAbout: () -> Unit = {},
)

/** Maps a selection to the query scope. */
fun LibrarySelection.scope(): LibraryScope? = when (this) {
    is LibrarySelection.Folder -> LibraryScope.Source(sourceId)

    is LibrarySelection.Special -> when (which) {
        SpecialSource.ALL -> LibraryScope.All
        SpecialSource.RECENT -> LibraryScope.Recent
        SpecialSource.FROM_APPS -> null
    }
}

/** The grid entries of [rows]: date headers (only in capture-time order) between the photos. */
fun buildGridEntries(rows: List<PhotoRow>, query: PhotoQuery): List<GridEntry> {
    val dated = query.scope != LibraryScope.Recent && query.sort != PhotoSort.NAME
    if (!dated) return rows.map { GridEntry.Photo(it) }
    val out = ArrayList<GridEntry>(rows.size + rows.size / HEADER_GUESS + 1)
    var lastDay = Long.MIN_VALUE
    for (row in rows) {
        val day = Math.floorDiv(row.sortTime, DAY_MS)
        if (day != lastDay) {
            out += GridEntry.Header(day, row.sortTime)
            lastDay = day
        }
        out += GridEntry.Photo(row)
    }
    return out
}

private const val DAY_MS = 86_400_000L

/** Capacity guess: about one date header per this many photos. */
private const val HEADER_GUESS = 8
