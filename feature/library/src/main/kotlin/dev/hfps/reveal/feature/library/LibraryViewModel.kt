package dev.hfps.reveal.feature.library

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.edit.EditClipboard
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.edit.HistoryWriter
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.library.AddSourceResult
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarOutcome
import dev.hfps.reveal.data.model.PhotoFilter
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.preset.PresetLibrary
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.data.storage.StorageVolumes
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.feature.library.presets.PresetBatchWorker
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.sync.SyncRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-off messages for the snackbar. */
sealed interface LibraryMessage {
    data object DatabaseOnly : LibraryMessage

    data class Removed(val count: Int) : LibraryMessage

    data object AddFailed : LibraryMessage

    data object AlreadyAdded : LibraryMessage

    data object Copied : LibraryMessage

    /** Edits pasted onto [count] photos (the others already had them). */
    data class Pasted(val count: Int) : LibraryMessage

    /** Edits reset on [count] photos. */
    data class Reset(val count: Int) : LibraryMessage

    /** Photos [count] became readable again and [still] stay unavailable (after "Try again"). */
    data class Rechecked(val readable: Int, val still: Int) : LibraryMessage

    /** [count] unavailable photos forgotten ("Remove from Reveal"). */
    data class Forgotten(val count: Int) : LibraryMessage

    /** A preset is being applied to [count] photos in the background. */
    data class PresetQueued(val name: String, val count: Int) : LibraryMessage

    /** [count] photos deleted from the sync server ("Delete from server"). */
    data class DeletedFromServer(val count: Int) : LibraryMessage
}

/**
 * The library screen (ADR 0006): sources with their state, the grid of the chosen source with filters, sort and
 * search (an observed Room query, so indexing results stream in), selection and its actions. The chosen source
 * and query survive process death (SavedStateHandle).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Suppress("LongParameterList")
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val library: LibraryRepository,
    val thumbnails: ThumbnailRepository,
    private val volumes: StorageVolumes,
    private val edits: EditRepository,
    private val writer: HistoryWriter,
    private val sync: SyncRepository,
    private val presets: PresetRepository,
    private val gallery: GalleryAccess,
    private val unavailable: UnavailableHelper,
) : ViewModel() {
    /**
     * Offer the "Photos and videos" permission now (ADR 0011 "Gallery access"): a photo the gallery shared can no
     * longer be read, Reveal has no gallery access, and the offer was never answered.
     */
    val galleryOffer: StateFlow<Boolean> =
        combine(library.unreadableHandoffs(), gallery.state, gallery.asked, GalleryAccess::shouldOffer)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

    /** "Not now": remembered, never offered again (Settings can turn it on). */
    fun declineGalleryAccess() = gallery.setAsked()

    /** The system dialog answered: the documents it opened are readable again (thumbnails, export, backup). */
    fun onGalleryAccessResult() {
        gallery.setAsked()
        gallery.refresh()
        viewModelScope.launch { library.checkHandoffs() }
    }

    /** The app came back (the permission may have changed in the system settings). */
    fun onResumed() {
        if (gallery.refresh()) viewModelScope.launch { library.checkHandoffs() }
    }

    /** The preset library, for "Apply preset" on the selection (docs/adr/0019 "Batch"). */
    val presetLibrary: StateFlow<PresetLibrary> =
        presets.library().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), PresetLibrary())

    /** Applies the preset [uuid] at [strength] to the selected photos in the background; clears the selection. */
    fun applyPreset(context: android.content.Context, uuid: String, strength: Float) {
        viewModelScope.launch {
            val versions = selectedVersions()
            if (versions.isEmpty()) return@launch
            val name = presetLibrary.value[uuid]?.name ?: presets.preset(uuid)?.name ?: return@launch
            PresetBatchWorker.enqueue(context.applicationContext, uuid, strength, versions)
            messages.send(LibraryMessage.PresetQueued(name, versions.size))
            setSelected(emptySet())
        }
    }

    /** True when the route asked to open on "Opened from other apps" (a hand-off of several photos, ADR 0011). */
    private val fromApps: Boolean = savedState.get<Boolean>(KEY_FROM_APPS) == true

    private val _state = MutableStateFlow(LibraryUiState(selection = restoreSelection(), query = restoreQuery()))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val events: Flow<LibraryMessage> = messages.receiveAsFlow()

    init {
        LibraryMetrics.selected(SystemClock.elapsedRealtime())
    }

    init {
        viewModelScope.launch {
            // A fresh start reopens the folder the library showed last (the process-death state wins when present).
            if (savedState.get<String>(KEY_SELECTION) == null && !fromApps) {
                library.lastSelection()?.let(::decodeSelection)?.takeUnless { restored ->
                    // The Server entry only exists while the server has photos here (ADR 0018).
                    restored == LibrarySelection.Special(SpecialSource.SERVER) &&
                        library.serverPhotoCount().first() == 0
                }.let { restored ->
                    // Nothing to reopen and no folder, but photos other apps handed over: start there.
                    val start = startSelection(
                        restored,
                        folders = library.sources().first().size,
                        fromApps = library.fromAppsCount().first(),
                    ) ?: return@let
                    savedState[KEY_SELECTION] = start.encode()
                    _state.update { it.copy(selection = start) }
                }
            }
            library.sources().collect { sources ->
                _state.update { s ->
                    // A removed folder that was open falls back to the sources list.
                    val selection = s.selection.takeUnless { sel ->
                        sel is LibrarySelection.Folder && sources.none { it.id == sel.sourceId }
                    }
                    s.copy(sources = sources, sourcesLoaded = true, selection = selection)
                }
            }
        }
        viewModelScope.launch {
            library.fromAppsCount().collect { count -> _state.update { it.copy(fromAppsPhotos = count) } }
        }
        viewModelScope.launch {
            library.serverPhotoCount().collect { count ->
                _state.update { s ->
                    // Unpaired (or the server emptied): the Server entry is gone, back to the sources list.
                    val gone = count == 0 && s.selection == LibrarySelection.Special(SpecialSource.SERVER)
                    s.copy(serverPhotos = count, selection = if (gone) null else s.selection)
                }
            }
        }
        viewModelScope.launch {
            _state.map { it.selection to it.query }.distinctUntilChanged().collectLatest { (selection, query) ->
                val scope = selection?.scope()
                if (scope == null) {
                    _state.update { it.copy(entries = emptyList(), photoCount = 0, photosLoaded = true) }
                    return@collectLatest
                }
                val full = query.copy(scope = scope)
                // While indexing, rows arrive in batches: coalesce to a few updates per second.
                library.photos(full).conflate().debounceAfterFirst(GRID_DEBOUNCE_MS)
                    .map { rows -> rows to buildGridEntries(rows, full) }
                    .flowOn(Dispatchers.Default)
                    .collect { (rows, entries) ->
                        LibraryMetrics.firstRows(SystemClock.elapsedRealtime(), rows.size)
                        _state.update { s ->
                            val visible = rows.mapTo(HashSet(rows.size)) { it.id }
                            s.copy(
                                entries = entries,
                                photoCount = rows.size,
                                photosLoaded = true,
                                selected = s.selected.filterTo(HashSet()) { it in visible },
                            )
                        }
                    }
            }
        }
        // Opening a scope re-checks the files marked unreadable (batch, in the background, rate-limited by the
        // repository). There is no "Unavailable" filter any more (FIX/V): the placeholder marks this device's own
        // unreadable photos in place, and the Server scope lists only photos that open.
        viewModelScope.launch {
            _state.map { it.selection?.scope() }.distinctUntilChanged().collectLatest { scope ->
                if (scope != null) library.recheckAccess()
            }
        }
        // Volumes coming and going (SD card, USB drive) re-check the sources and index what came back.
        viewModelScope.launch {
            volumes.changes().drop(1).debounce(VOLUME_DEBOUNCE_MS).collect { library.refresh() }
        }
        viewModelScope.launch { library.refresh() }
        viewModelScope.launch {
            edits.clipboard().collect { clip -> _state.update { it.copy(clipboard = clip?.recipe) } }
        }
    }

    // ------------------------------------------------------------------ copy / paste edits

    /** Versions of the selected photos, in grid order, for the export sheet. */
    suspend fun selectedVersions(): List<Long> {
        val selected = _state.value.selected
        val ordered = _state.value.photoIds.filter { it in selected }
        return edits.versionsOf(ordered)
    }

    /** Copies the whole edit of the one selected photo (the paste chooser picks the groups). */
    fun copyEdits() {
        val photoId = _state.value.selected.singleOrNull() ?: return
        viewModelScope.launch {
            writer.flush()
            val versionId = edits.versionsOf(listOf(photoId)).firstOrNull() ?: return@launch
            edits.copy(edits.recipe(versionId))
            messages.send(LibraryMessage.Copied)
        }
    }

    /** Deletes the conflict copies among the selected photos (ADR 0018), here and, through sync, everywhere. */
    fun deleteConflictCopies() {
        val versions = _state.value.selectedConflictCopies
        if (versions.isEmpty()) return
        viewModelScope.launch {
            writer.flush()
            versions.forEach { sync.deleteVersion(it) }
        }
    }

    /** Resets every selected photo to the original: one "Reset all" history step per edited photo (undoable). */
    fun resetEdits() {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            writer.flush()
            val all = RecipeGroup.entries.toSet()
            val changed = edits.paste(
                edits.versionsOf(ids),
                EditClipboard(Recipe.DEFAULT),
                all,
                HistoryAction.RESET_ALL,
            )
            messages.send(LibraryMessage.Reset(changed))
        }
    }

    /** Opens the paste chooser on the last choice of groups. */
    fun openPaste() {
        if (_state.value.clipboard == null || _state.value.selected.isEmpty()) return
        viewModelScope.launch {
            val groups = edits.pasteGroups()
            _state.update { it.copy(pasteChooser = groups) }
        }
    }

    fun setPasteGroups(groups: Set<RecipeGroup>) =
        _state.update { it.copy(pasteChooser = groups.takeIf { _ -> it.pasteChooser != null }) }

    fun dismissPaste() = _state.update { it.copy(pasteChooser = null) }

    /** Pastes the chosen groups onto every selected photo: one history step per photo the paste changes. */
    fun confirmPaste() {
        val groups = _state.value.pasteChooser ?: return
        val ids = _state.value.selected.toList()
        dismissPaste()
        if (groups.isEmpty() || ids.isEmpty()) return
        viewModelScope.launch {
            val clip = edits.currentClipboard() ?: return@launch
            edits.setPasteGroups(groups)
            writer.flush()
            val changed = edits.paste(edits.versionsOf(ids), clip, groups)
            messages.send(LibraryMessage.Pasted(changed))
        }
    }

    fun select(selection: LibrarySelection?) {
        LibraryMetrics.selected(SystemClock.elapsedRealtime())
        savedState[KEY_SELECTION] = selection?.encode()
        viewModelScope.launch { library.setLastSelection(selection?.encode()) }
        _state.update {
            it.copy(selection = selection, selected = emptySet(), searchOpen = false, photosLoaded = false)
        }
    }

    fun setQuery(query: PhotoQuery) {
        savedState[KEY_QUERY] = encodeQuery(query)
        _state.update { it.copy(query = query) }
    }

    fun setSearchOpen(open: Boolean) {
        _state.update { it.copy(searchOpen = open, query = if (open) it.query else it.query.copy(search = "")) }
    }

    fun setSelected(ids: Set<Long>) = _state.update { it.copy(selected = ids) }

    /**
     * A folder picked in the system's picker. While paired with a sync server it first asks what the folder backs up
     * ([chooseFolderBackup]); otherwise it is added with the default, only edited photos (FIX/V).
     */
    fun addFolder(treeUri: Uri) {
        if (sync.paired) {
            _state.update { it.copy(addFolderPrompt = treeUri.toString()) }
        } else {
            add(treeUri, everything = false)
        }
    }

    /** The backup choice of the folder being added. */
    fun chooseFolderBackup(everything: Boolean) {
        val uri = _state.value.addFolderPrompt ?: return
        _state.update { it.copy(addFolderPrompt = null) }
        add(uri.toUri(), everything)
    }

    /** The backup sheet was dismissed: nothing is added. */
    fun dismissAddFolder() = _state.update { it.copy(addFolderPrompt = null) }

    private fun add(treeUri: Uri, everything: Boolean) {
        viewModelScope.launch {
            when (val result = library.addSource(treeUri, backup = everything)) {
                is AddSourceResult.Added -> select(LibrarySelection.Folder(result.sourceId))

                is AddSourceResult.AlreadyAdded -> {
                    select(LibrarySelection.Folder(result.sourceId))
                    messages.send(LibraryMessage.AlreadyAdded)
                }

                AddSourceResult.Unreadable -> messages.send(LibraryMessage.AddFailed)
            }
        }
    }

    fun rescan(sourceId: Long) {
        viewModelScope.launch {
            library.refresh(reindex = false)
            library.index(sourceId)
        }
    }

    /** Asks before removing a folder (and, while paired, whether its photos leave the server too). */
    fun removeSource(sourceId: Long) {
        val source = _state.value.sources.firstOrNull { it.id == sourceId } ?: return
        viewModelScope.launch {
            val paired = sync.paired
            val edited = if (paired) sync.editedInFolder(sourceId) else 0
            _state.update { it.copy(removePrompt = RemovePrompt(sourceId, source.name, paired, edited)) }
        }
    }

    fun dismissRemove() = _state.update { it.copy(removePrompt = null) }

    /** Removes the prompted folder; its photos stay on the server unless [deleteFromServer] (ADR 0018, FIX/V). */
    fun confirmRemove(deleteFromServer: Boolean) {
        val prompt = _state.value.removePrompt ?: return
        _state.update { it.copy(removePrompt = null) }
        viewModelScope.launch {
            writer.flush()
            if (prompt.server) sync.removeFolder(prompt.sourceId, deleteFromServer)
            library.removeSource(prompt.sourceId)
        }
    }

    /** "Delete from server" for the selected Server photos: asks first. */
    fun deleteFromServer() {
        val count = _state.value.selected.size
        if (count > 0 && _state.value.serverScope) _state.update { it.copy(deleteServerPrompt = count) }
    }

    fun dismissDeleteFromServer() = _state.update { it.copy(deleteServerPrompt = null) }

    fun confirmDeleteFromServer() {
        val ids = _state.value.selected.toList()
        _state.update { it.copy(deleteServerPrompt = null) }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            writer.flush()
            val count = sync.deleteFromServer(ids)
            _state.update { s -> s.copy(selected = s.selected - ids.toSet()) }
            messages.send(LibraryMessage.DeletedFromServer(count))
        }
    }

    fun rate(rating: Int) = act { ids -> library.setRating(ids, rating) }

    fun flag(flag: PickFlag) = act { ids -> library.setFlag(ids, flag) }

    /** Rates or flags one photo (the viewer). */
    fun rateOne(photoId: Long, rating: Int) = act(listOf(photoId)) { ids -> library.setRating(ids, rating) }

    // ------------------------------------------------------------------ unavailable photos (FIX/U)

    /** A tap on an unavailable photo: explains why and what to do. */
    fun explain(row: PhotoRow) {
        viewModelScope.launch {
            unavailable.info(row.id)?.let { info -> _state.update { it.copy(unavailableInfo = info) } }
        }
    }

    fun dismissUnavailable() = _state.update { it.copy(unavailableInfo = null) }

    fun openSender() {
        _state.value.unavailableInfo?.let(unavailable::openApp)
    }

    /** Re-checks access to [ids] now (not rate limited) and says what changed. */
    fun tryAgain(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            library.recheckAccess(ids, force = true)
            _state.update { it.copy(unavailableInfo = null) }
            val scope = _state.value.selection?.scope() ?: return@launch
            val still = library.photoList(PhotoQuery(scope = scope, filter = PhotoFilter.UNAVAILABLE))
                .mapTo(HashSet()) { it.id }.count { it in ids }
            messages.send(LibraryMessage.Rechecked((ids.size - still).coerceAtLeast(0), still))
        }
    }

    /** "Remove from Reveal": forgets [ids] at once, or asks first when any has edits (they go too). */
    fun forget(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val edited = library.editedCount(ids)
            if (edited == 0) {
                doForget(ids)
            } else {
                _state.update { it.copy(forgetPrompt = ForgetPrompt(ids, edited), unavailableInfo = null) }
            }
        }
    }

    fun confirmForget() {
        val prompt = _state.value.forgetPrompt ?: return
        _state.update { it.copy(forgetPrompt = null) }
        viewModelScope.launch { doForget(prompt.ids) }
    }

    fun dismissForget() = _state.update { it.copy(forgetPrompt = null) }

    private suspend fun doForget(ids: List<Long>) {
        library.forget(ids)
        _state.update { s -> s.copy(selected = s.selected - ids.toSet(), unavailableInfo = null, forgetPrompt = null) }
        messages.send(LibraryMessage.Forgotten(ids.size))
    }

    fun removeSelected() {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            library.removeFromLibrary(ids)
            _state.update { it.copy(selected = emptySet()) }
            messages.send(LibraryMessage.Removed(ids.size))
        }
    }

    private fun act(ids: List<Long> = _state.value.selected.toList(), block: suspend (List<Long>) -> SidecarOutcome) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val outcome = block(ids)
            if (outcome.databaseOnly > 0) messages.send(LibraryMessage.DatabaseOnly)
        }
    }

    /** First-frame and indexing measurements read by the instrumented test. */
    fun onGridDraw(count: Int) = LibraryMetrics.firstGridFrame(SystemClock.elapsedRealtime(), count)

    private fun restoreSelection(): LibrarySelection? = savedState.get<String>(KEY_SELECTION)?.let(::decodeSelection)
        ?: LibrarySelection.Special(SpecialSource.FROM_APPS).takeIf { fromApps }

    private fun restoreQuery(): PhotoQuery = savedState.get<String>(KEY_QUERY)?.let(::decodeQuery) ?: PhotoQuery()

    companion object {
        private const val KEY_SELECTION = "selection"
        private const val KEY_QUERY = "query"
        private const val KEY_FROM_APPS = "fromApps"
        private const val QUERY_PARTS = 4
        private const val GRID_DEBOUNCE_MS = 250L
        private const val VOLUME_DEBOUNCE_MS = 500L
        private const val TAG = "RevealLibrary"
        private const val STOP_TIMEOUT = 5_000L

        /**
         * Where a fresh start opens: the [restored] last selection, else "Opened from other apps" when the library has
         * no folder but photos other apps handed over (ADR 0011), else the sources list (null).
         */
        fun startSelection(restored: LibrarySelection?, folders: Int, fromApps: Int): LibrarySelection? = restored
            ?: LibrarySelection.Special(SpecialSource.FROM_APPS).takeIf { folders == 0 && fromApps > 0 }

        fun LibrarySelection.encode(): String = when (this) {
            is LibrarySelection.Folder -> "f:$sourceId"
            is LibrarySelection.Special -> "s:${which.name}"
        }

        fun decodeSelection(text: String): LibrarySelection? = runCatching {
            when {
                text.startsWith("f:") -> LibrarySelection.Folder(text.removePrefix("f:").toLong())
                text.startsWith("s:") -> LibrarySelection.Special(SpecialSource.valueOf(text.removePrefix("s:")))
                else -> null
            }
        }.onFailure { Log.w(TAG, "Bad saved selection $text", it) }.getOrNull()

        fun encodeQuery(q: PhotoQuery): String =
            listOf(q.filter.name, q.minRating, q.sort.name, q.search).joinToString("\u0001")

        fun decodeQuery(text: String): PhotoQuery? = runCatching {
            val (filter, minRating, sort) = text.split('\u0001', limit = QUERY_PARTS)
            PhotoQuery(
                filter = dev.hfps.reveal.data.model.PhotoFilter.valueOf(filter),
                minRating = minRating.toInt(),
                sort = dev.hfps.reveal.data.model.PhotoSort.valueOf(sort),
                search = text.split('\u0001', limit = QUERY_PARTS).getOrElse(QUERY_PARTS - 1) { "" },
            )
        }.getOrNull()
    }
}

/** The first emission passes at once (first grid frame), later ones are debounced (indexing batches). */
@OptIn(FlowPreview::class)
private fun <T> Flow<T>.debounceAfterFirst(ms: Long): Flow<T> {
    var first = true
    return debounce {
        if (first) {
            first = false
            0L
        } else {
            ms
        }
    }
}

/**
 * Timings of the library for the instrumented measurement (logcat tag `REVEAL_LIBRARY`): from the moment a grid is
 * asked for (screen start or a source chosen) to its first rows from Room and to its first drawn frame.
 */
object LibraryMetrics {
    private const val TAG = "REVEAL_LIBRARY"

    @Volatile
    private var since: Long = 0

    @Volatile
    var firstRowsMs: Long = -1
        private set

    @Volatile
    var firstGridFrameMs: Long = -1
        private set

    internal fun selected(now: Long) {
        since = now
    }

    internal fun firstRows(now: Long, count: Int) {
        if (firstRowsMs < 0 && count > 0) {
            firstRowsMs = now - since
            Log.i(TAG, "first rows: $count in $firstRowsMs ms")
        }
    }

    internal fun firstGridFrame(now: Long, count: Int) {
        if (firstGridFrameMs < 0 && count > 0) {
            firstGridFrameMs = now - since
            Log.i(TAG, "first grid frame: $count photos, $firstGridFrameMs ms after the grid was asked for")
        }
    }

    /** Resets for a new measurement (tests). */
    fun reset() {
        firstRowsMs = -1
        firstGridFrameMs = -1
    }
}
