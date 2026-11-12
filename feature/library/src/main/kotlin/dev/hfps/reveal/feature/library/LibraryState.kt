package dev.hfps.reveal.feature.library

import androidx.compose.runtime.Immutable
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup

/** The pseudo-sources of the sources list besides the added folders. */
enum class SpecialSource { ALL, RECENT, FROM_APPS, SERVER }

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
    /** The copied edit (from the editor or here), null when nothing was copied. */
    val clipboard: Recipe? = null,
    /** The paste chooser is open with these groups ticked; null = closed. */
    val pasteChooser: Set<RecipeGroup>? = null,
    /** Photos only the sync server has (ADR 0018); the Server entry shows when there are any. */
    val serverPhotos: Int = 0,
    /** Photos in "Opened from other apps": with them, a library without folders is not empty (ADR 0011). */
    val fromAppsPhotos: Int = 0,
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
) {
    /** The selected photos are Server photos ("Delete from server" applies to them). */
    val serverScope: Boolean get() = selection == LibrarySelection.Special(SpecialSource.SERVER)

    val selecting: Boolean get() = selected.isNotEmpty()

    val currentSource: SourceItem?
        get() = (selection as? LibrarySelection.Folder)?.let { s -> sources.firstOrNull { it.id == s.sourceId } }

    /** True when any filter, rating or search narrows the grid. */
    val filtered: Boolean
        get() = query.filter != dev.hfps.reveal.data.model.PhotoFilter.ALL || query.search.isNotBlank()

    val photoIds: List<Long> get() = entries.mapNotNull { (it as? GridEntry.Photo)?.row?.id }.distinct()

    /** The selected photos Reveal cannot show at all: what "Try again" and "Remove from Reveal" act on. */
    val selectedUnavailable: List<Long>
        get() = entries.mapNotNull { entry ->
            (entry as? GridEntry.Photo)?.row?.takeIf { it.unavailable && it.id in selected }?.id
        }.distinct()

    /** Sync conflict copies among the selected photos (ADR 0018): what "Delete conflict copies" removes. */
    val selectedConflictCopies: List<Long>
        get() = entries.mapNotNull { entry ->
            (entry as? GridEntry.Photo)?.row?.takeIf {
                it.conflict &&
                    it.id in selected
            }?.versionId
        }
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

/** Maps a selection to the query scope. */
fun LibrarySelection.scope(): LibraryScope? = when (this) {
    is LibrarySelection.Folder -> LibraryScope.Source(sourceId)

    is LibrarySelection.Special -> when (which) {
        SpecialSource.ALL -> LibraryScope.All
        SpecialSource.RECENT -> LibraryScope.Recent
        SpecialSource.FROM_APPS -> LibraryScope.FromApps
        SpecialSource.SERVER -> LibraryScope.Server
    }
}

/** The grid entries of [rows]: date headers (only in capture-time order) between the photos. */
fun buildGridEntries(rows: List<PhotoRow>, query: PhotoQuery): List<GridEntry> {
    val dated = query.scope != LibraryScope.Recent && query.sort != PhotoSort.NAME
    return when {
        query.scope == LibraryScope.FromApps -> buildGroupEntries(rows)
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
