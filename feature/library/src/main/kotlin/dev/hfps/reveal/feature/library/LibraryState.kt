package dev.hfps.reveal.feature.library

import androidx.compose.runtime.Immutable
import dev.hfps.reveal.data.db.CameraCount
import dev.hfps.reveal.data.db.DateCounts
import dev.hfps.reveal.data.db.FilterCounts
import dev.hfps.reveal.data.db.LensCount
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.sync.SyncProblem
import dev.hfps.reveal.sync.SyncStatus

/**
 * The compact sync status of the library's top bar (FIX/L): tap opens the Sync screen. Derived from the paired account
 * and `SyncStatus` by [SyncBadge.of].
 */
@Immutable
sealed interface SyncBadge {
    data object NotPaired : SyncBadge

    data object Synced : SyncBadge

    data object Syncing : SyncBadge

    /** [count] originals or previews still to upload. */
    data class Uploading(val count: Int) : SyncBadge

    /** [count] changes wait for the next run (edits, ratings). */
    data class Waiting(val count: Int) : SyncBadge

    data object Offline : SyncBadge

    /** The server answered with a problem (unauthorised, changed, no storage, error): the Sync screen says which. */
    data object Problem : SyncBadge

    companion object {
        fun of(paired: Boolean, status: SyncStatus): SyncBadge = when {
            !paired -> NotPaired
            status.problem == SyncProblem.OFFLINE -> Offline
            status.problem != null -> Problem
            status.uploadsPending > 0 -> Uploading(status.uploadsPending)
            status.running -> Syncing
            status.pending > 0 -> Waiting(status.pending)
            else -> Synced
        }
    }
}

/** One entry of the grid: a date header (spans the row) or a photo. */
@Immutable
sealed interface GridEntry {
    val key: Any

    /** [day] = days since the epoch of the camera's local date; [time] any millis of that day (for formatting). */
    data class Header(val day: Long, val time: Long) : GridEntry {
        // Built once: the lazy grid asks for a key whenever it measures the item, never allocate one per call.
        override val key: Any = "h$day"
    }

    /**
     * A month's header (W3/D): [month] = [DateIndex] key (year * 12 + month - 1 of the camera's date, or
     * [DateIndex.NO_DATE] for the photos without a date), [count] its cells.
     */
    data class Month(val month: Int, val count: Int) : GridEntry {
        override val key: Any = "m$month"
    }

    /** A hand-off batch in "Opened from other apps": when it arrived ([at]) and the sender's title, if any. */
    data class Group(val at: Long, val title: String?) : GridEntry {
        override val key: Any get() = "g$at/$title"
    }

    /** A photo's version: its first version is keyed by the photo id, a copy by its version (ADR 0018). */
    data class Photo(val row: PhotoRow) : GridEntry {
        override val key: Any get() = row.gridKey
    }
}

/** How the date-sorted grid is grouped (W3/D): month headers only, or month headers with day sub-headers. */
enum class DateGrouping { MONTH, DAY }

/**
 * Where each month starts in the grid (W3/D): built in the same pass as the entries, off the main thread, so the fast
 * scroller, the sticky header and "Go to date" look months up without scanning the rows. [months] are in grid order.
 */
@Immutable
class DateIndex(
    /** Month keys (year * 12 + month - 1, or [NO_DATE]) in grid order. */
    val months: IntArray,
    /** The entry index of each month's header. */
    val starts: IntArray,
    /** Each month's cell count. */
    val counts: IntArray,
) {
    val size: Int get() = months.size

    /** The position in [months] of the month entry [entry] belongs to (binary search), or -1 before the first. */
    fun positionAt(entry: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= entry) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** The month key of the entry at [entry], or null when there is none. */
    fun monthAt(entry: Int): Int? = positionAt(entry).takeIf { it >= 0 }?.let { months[it] }

    /** The header entry index of [month], or null when the grid has none of it. */
    fun startOf(month: Int): Int? = months.indexOf(month).takeIf { it >= 0 }?.let { starts[it] }

    /** The cell count of [month] (0 when absent). */
    fun countOf(month: Int): Int = months.indexOf(month).takeIf { it >= 0 }?.let { counts[it] } ?: 0

    companion object {
        /** The "No date" group's key (photos whose `sort_time` is at or below 0). */
        const val NO_DATE = Int.MIN_VALUE

        /** The key of a `sort_time` (camera wall clock stored as UTC millis); [NO_DATE] at or below 0. */
        fun monthOf(sortTime: Long): Int {
            if (sortTime <= 0) return NO_DATE
            val day = Math.floorDiv(sortTime, DAY_MS)
            val date = java.time.LocalDate.ofEpochDay(day)
            return date.year * MONTHS + date.monthValue - 1
        }

        fun year(month: Int): Int = Math.floorDiv(month, MONTHS)

        /** 1..12. */
        fun monthOfYear(month: Int): Int = Math.floorMod(month, MONTHS) + 1

        fun of(year: Int, monthOfYear: Int): Int = year * MONTHS + monthOfYear - 1

        private const val MONTHS = 12
    }
}

/** The grid's entries and, when it is grouped by date, its [DateIndex]. */
@Immutable
data class GridModel(val entries: List<GridEntry>, val dateIndex: DateIndex? = null)

@Immutable
data class LibraryUiState(
    val sources: List<SourceItem> = emptyList(),
    /** False until the sources are known (avoids flashing the empty state). */
    val sourcesLoaded: Boolean = false,
    /** The home's filters, sort and search; [recent] shows the Recent view instead (no filters). */
    val query: PhotoQuery = PhotoQuery(),
    /** The Recent view (its own list, newest opened first), over the home. */
    val recent: Boolean = false,
    val entries: List<GridEntry> = emptyList(),
    /** Where each month starts, when the grid is sorted by date (W3/D); null otherwise. */
    val dateIndex: DateIndex? = null,
    /** Month headers only, or with day sub-headers (W3/D, remembered). */
    val grouping: DateGrouping = DateGrouping.DAY,
    /** "Go to date" is open (W3/D). */
    val dateJumpOpen: Boolean = false,
    /** The Date filter's custom range picker is open (W3/D). */
    val dateRangeOpen: Boolean = false,
    /** The Camera and Lens filters' choices with their counts (while the sheet is open). */
    val cameras: List<CameraCount> = emptyList(),
    val lenses: List<LensCount> = emptyList(),
    val dateCounts: DateCounts? = null,
    val photoCount: Int = 0,
    val photosLoaded: Boolean = false,
    /**
     * The selected grid cells by **version id** (docs/adr/0025): a virtual copy is selected on its own. Photo actions
     * (rating, flag, remove) act on [selectedPhotoIds], edit actions (paste, reset, export) on the versions.
     */
    val selected: Set<Long> = emptySet(),
    val searchOpen: Boolean = false,
    /** The filter sheet is open. */
    val filtersOpen: Boolean = false,
    /** "Manage folders" is open. */
    val foldersOpen: Boolean = false,
    /** Paired with a sync server: the Sync filter group shows. */
    val paired: Boolean = false,
    val syncBadge: SyncBadge = SyncBadge.NotPaired,
    /** Per-choice counts for the filter sheet; null until known. */
    val counts: FilterCounts? = null,
    /** The copied edit (from the editor or here), null when nothing was copied. */
    val clipboard: Recipe? = null,
    /** The paste chooser is open with these groups ticked; null = closed. */
    val pasteChooser: Set<RecipeGroup>? = null,
    /** The explanation of a tapped unavailable photo; null = closed. */
    val unavailableInfo: UnavailableInfo? = null,
    /** Asking before "Remove from Reveal" forgets photos that have edits; null = not asking. */
    val forgetPrompt: ForgetPrompt? = null,
    /** A folder picked while paired waits for its backup choice (its tree URI); null = not asking. */
    val addFolderPrompt: String? = null,
    /** Asking before a folder is removed (and, while paired, about its photos on the server); null = not asking. */
    val removePrompt: RemovePrompt? = null,
    /** Asking before "Delete from server" deletes this many selected Server photos; null = not asking. */
    val deleteServerPrompt: Int? = null,
    /** The conflict chooser of a tapped conflict copy (ADR 0018 FIX/W); null = closed. */
    val conflictChoice: ConflictChoice? = null,
    /** Asking before deleting the selected virtual copies (their edits go too); null = not asking. */
    val deleteCopiesPrompt: List<Long>? = null,
) {
    val filters: PhotoFilters get() = query.filters

    /** What the grid asks Room for: the home with its filters, or Recent. */
    val gridQuery: PhotoQuery
        get() = if (recent) PhotoQuery(scope = LibraryScope.Recent) else query

    val selecting: Boolean get() = selected.isNotEmpty()

    /** The folder the grid is narrowed to (its banner shows: indexing, unplugged, access lost). */
    val currentSource: SourceItem?
        get() = if (recent) null else filters.singleFolder?.let { id -> sources.firstOrNull { it.id == id } }

    /** True when any filter or search narrows the home. */
    val filtered: Boolean get() = !recent && (!filters.isEmpty || query.search.isNotBlank())

    /** Nothing at all yet: no folder and no photo (hand-offs and Server photos count). */
    val libraryEmpty: Boolean get() = sourcesLoaded && sources.isEmpty() && counts?.total == 0

    val photoIds: List<Long> get() = entries.mapNotNull { (it as? GridEntry.Photo)?.row?.id }.distinct()

    /** Every cell's version, in grid order ("Select all"). */
    val versionIds: List<Long> get() = entries.mapNotNull { (it as? GridEntry.Photo)?.row?.versionId }

    private fun selectedRows(): List<PhotoRow> =
        entries.mapNotNull { entry -> (entry as? GridEntry.Photo)?.row?.takeIf { it.versionId in selected } }

    /** The photos of the selected cells, in grid order, once each (a photo and its copy are one photo). */
    val selectedPhotoIds: List<Long> get() = selectedRows().map { it.id }.distinct()

    /** The selected versions in grid order: what edits and exports act on. */
    val selectedVersions: List<Long> get() = selectedRows().map { it.versionId }

    /** Every listed version of [photoIds] (to drop them from the selection when the photos go). */
    fun versionsOfPhotos(photoIds: Collection<Long>): Set<Long> {
        val ids = photoIds.toHashSet()
        return entries.mapNotNullTo(HashSet()) { (it as? GridEntry.Photo)?.row?.takeIf { r -> r.id in ids }?.versionId }
    }

    /** The selected virtual copies (not conflict copies): what "Delete copy" removes. */
    val selectedCopies: List<Long> get() = selectedRows().filter {
        it.versionPosition > 0 && !it.conflict
    }.map { it.versionId }

    /** The selected photos Reveal cannot show at all: what "Try again" and "Remove from Reveal" act on. */
    val selectedUnavailable: List<Long> get() = selectedRows().filter { it.unavailable }.map { it.id }.distinct()

    /** Selected Server photos (only the server has them): what "Delete from server" acts on. */
    val selectedRemote: List<Long> get() = selectedRows().filter { it.remote }.map { it.id }.distinct()

    /** Sync conflict copies among the selected photos (ADR 0018): what "Delete conflict copies" removes. */
    val selectedConflictCopies: List<Long> get() = selectedRows().filter { it.conflict }.map { it.versionId }
}

/** Everything the library screen can ask for (stateless content for screenshots and tests). */
@Immutable
data class LibraryActions(
    /** New filters for the home (the sheet, a chip's ×, "Clear all"). */
    val onFilters: (PhotoFilters) -> Unit = {},
    val onFiltersOpen: (Boolean) -> Unit = {},
    /** Month headers only, or with day sub-headers (W3/D). */
    val onGrouping: (DateGrouping) -> Unit = {},
    /** Opens or closes "Go to date" (W3/D). */
    val onDateJumpOpen: (Boolean) -> Unit = {},
    /** Opens or closes the Date filter's custom range picker (W3/D). */
    val onDateRangeOpen: (Boolean) -> Unit = {},
    val onFoldersOpen: (Boolean) -> Unit = {},
    /** Opens (true) or leaves (false) the Recent view. */
    val onRecent: (Boolean) -> Unit = {},
    /** The home narrowed to one folder ("Manage folders" → a folder). */
    val onShowFolder: (Long) -> Unit = {},
    /** A folder's backup mode: everything, or only the photos you edit (FIX/V). */
    val onFolderBackup: (sourceId: Long, everything: Boolean) -> Unit = { _, _ -> },
    /** The compact sync status: opens the Sync screen. */
    val onSync: () -> Unit = {},
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
    /** Copies the edit of the one selected photo. */
    val onCopyEdits: () -> Unit = {},
    /** Opens the paste chooser for the selection. */
    val onPasteEdits: () -> Unit = {},
    val onPasteGroups: (Set<RecipeGroup>) -> Unit = {},
    val onPasteConfirm: () -> Unit = {},
    val onPasteDismiss: () -> Unit = {},
    /** Resets the selected photos' edits (one undoable step each). */
    val onResetEdits: () -> Unit = {},
    /** Opens the export sheet for the selected photos. */
    val onExport: () -> Unit = {},
    /** Opens the preset chooser for the selected photos (docs/adr/0019 "Batch"). */
    val onApplyPreset: () -> Unit = {},
    /** Deletes the sync conflict copies of the selected photos, here and on the other devices. */
    val onDeleteConflictCopies: () -> Unit = {},
    /** A virtual copy of every selected cell's edit (docs/adr/0025). */
    val onCreateCopies: () -> Unit = {},
    /** Deletes the selected virtual copies (asks first). */
    val onDeleteCopies: () -> Unit = {},
    val onDeleteCopiesConfirm: () -> Unit = {},
    val onDeleteCopiesDismiss: () -> Unit = {},
    /** A tap on an unavailable photo: the explanation sheet. */
    val onOpenUnavailable: (PhotoRow) -> Unit = {},

    /** A tap on a conflict copy: the chooser (FIX/W). */
    val onOpenConflict: (PhotoRow) -> Unit = {},
    val onKeepConflict: (keepCopy: Boolean) -> Unit = {},
    val onConflictDismiss: () -> Unit = {},
    val onUnavailableDismiss: () -> Unit = {},
    val onOpenSender: () -> Unit = {},
    /** Re-checks access to these photos now. */
    val onTryAgain: (List<Long>) -> Unit = {},
    /** "Remove from Reveal" for these unavailable photos (asks first when any has edits). */
    val onForget: (List<Long>) -> Unit = {},
    val onForgetConfirm: () -> Unit = {},
    val onForgetDismiss: () -> Unit = {},
    /** The backup choice of a folder being added: everything, or only photos you edit. */
    val onAddFolderBackup: (everything: Boolean) -> Unit = {},
    val onAddFolderDismiss: () -> Unit = {},
    val onRemoveConfirm: (deleteFromServer: Boolean) -> Unit = {},
    val onRemoveDismiss: () -> Unit = {},
    /** "Delete from server" for the selected Server photos (asks first). */
    val onDeleteFromServer: () -> Unit = {},
    val onDeleteFromServerConfirm: () -> Unit = {},
    val onDeleteFromServerDismiss: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onAbout: () -> Unit = {},
)

/**
 * Removing folder [sourceId] ([name]). [server]: paired with a sync server, so the dialog asks whether its photos leave
 * the server too; [edited] of them have edits (then "Also delete from the server" starts unchecked).
 */
@Immutable
data class RemovePrompt(val sourceId: Long, val name: String, val server: Boolean, val edited: Int)

/**
 * The grid entries of [rows]: date headers (only in capture-time order) between the photos; "Opened from other apps"
 * alone groups them by hand-off instead.
 */
fun buildGridEntries(
    rows: List<PhotoRow>,
    query: PhotoQuery,
    grouping: DateGrouping = DateGrouping.DAY,
): List<GridEntry> = buildGrid(rows, query, grouping).entries

/**
 * The grid of [rows] (W3/D): in a date order, a header per month (with its count) and, grouped by [DateGrouping.DAY],
 * a sub-header per day, plus the [DateIndex] of the month headers; photos without a date close the grid in a "No
 * date" group. One pass over the rows, run off the main thread by the view model.
 */
fun buildGrid(rows: List<PhotoRow>, query: PhotoQuery, grouping: DateGrouping = DateGrouping.DAY): GridModel {
    val dated = query.scope != LibraryScope.Recent && query.sort != PhotoSort.NAME
    return when {
        query.scope != LibraryScope.Recent && query.filters.onlyFromApps -> GridModel(buildGroupEntries(rows))
        !dated -> GridModel(rows.map { GridEntry.Photo(it) })
        else -> buildDateGrid(rows, grouping)
    }
}

@Suppress("CyclomaticComplexMethod")
private fun buildDateGrid(rows: List<PhotoRow>, grouping: DateGrouping): GridModel {
    val days = grouping == DateGrouping.DAY
    val out = ArrayList<GridEntry>(rows.size + rows.size / HEADER_GUESS + 1)
    val months = ArrayList<Int>()
    val starts = ArrayList<Int>()
    val counts = ArrayList<Int>()
    var lastMonth: Int? = null
    var lastDay = Long.MIN_VALUE
    var count = 0
    for (row in rows) {
        val month = DateIndex.monthOf(row.sortTime)
        if (month != lastMonth) {
            if (lastMonth != null) counts += count
            months += month
            starts += out.size
            out += GridEntry.Month(month, 0) // the count is known at the month's end
            lastMonth = month
            lastDay = Long.MIN_VALUE
            count = 0
        }
        if (days && month != DateIndex.NO_DATE) {
            val day = Math.floorDiv(row.sortTime, DAY_MS)
            if (day != lastDay) {
                out += GridEntry.Header(day, row.sortTime)
                lastDay = day
            }
        }
        out += GridEntry.Photo(row)
        count++
    }
    if (lastMonth != null) counts += count
    for (i in starts.indices) out[starts[i]] = GridEntry.Month(months[i], counts[i])
    return GridModel(out, DateIndex(months.toIntArray(), starts.toIntArray(), counts.toIntArray()))
}

/** Hand-offs, most recent first: a header per batch (same arrival time and title), then its photos. */
private fun buildGroupEntries(rows: List<PhotoRow>): List<GridEntry> {
    val out = ArrayList<GridEntry>(rows.size + 1)
    var last: Pair<Long, String?>? = null
    for (row in rows) {
        val group = (row.handoffAt ?: 0L) to row.handoffTitle
        if (group != last) {
            out += GridEntry.Group(group.first, group.second)
            last = group
        }
        out += GridEntry.Photo(row)
    }
    return out
}

private const val DAY_MS = 86_400_000L

/** Capacity guess: about one date header per this many photos. */
private const val HEADER_GUESS = 8
