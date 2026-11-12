package dev.hfps.reveal.feature.library

import androidx.compose.runtime.Immutable
import dev.hfps.reveal.data.db.FilterCounts
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
        override val key: Any get() = "h$day"
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
    val photoCount: Int = 0,
    val photosLoaded: Boolean = false,
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

    private fun selectedRows(): List<PhotoRow> =
        entries.mapNotNull { entry -> (entry as? GridEntry.Photo)?.row?.takeIf { it.id in selected } }

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
    val onGallery: () -> Unit = {},
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
fun buildGridEntries(rows: List<PhotoRow>, query: PhotoQuery): List<GridEntry> {
    val dated = query.scope != LibraryScope.Recent && query.sort != PhotoSort.NAME
    return when {
        query.scope != LibraryScope.Recent && query.filters.onlyFromApps -> buildGroupEntries(rows)
        !dated -> rows.map { GridEntry.Photo(it) }
        else -> buildDateEntries(rows)
    }
}

private fun buildDateEntries(rows: List<PhotoRow>): List<GridEntry> {
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
