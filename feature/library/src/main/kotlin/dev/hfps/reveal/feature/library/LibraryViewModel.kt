package dev.hfps.reveal.feature.library

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.data.library.AddSourceResult
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarOutcome
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.storage.StorageVolumes
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-off messages for the snackbar. */
sealed interface LibraryMessage {
    data object DatabaseOnly : LibraryMessage

    data class Removed(val count: Int) : LibraryMessage

    data object AddFailed : LibraryMessage

    data object AlreadyAdded : LibraryMessage
}

/**
 * The library screen (ADR 0006): sources with their state, the grid of the chosen source with filters, sort and
 * search (an observed Room query, so indexing results stream in), selection and its actions. The chosen source
 * and query survive process death (SavedStateHandle).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val library: LibraryRepository,
    val thumbnails: ThumbnailRepository,
    private val volumes: StorageVolumes,
) : ViewModel() {
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
            if (savedState.get<String>(KEY_SELECTION) == null) {
                library.lastSelection()?.let(::decodeSelection)?.let { restored ->
                    savedState[KEY_SELECTION] = restored.encode()
                    _state.update { it.copy(selection = restored) }
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
        // Volumes coming and going (SD card, USB drive) re-check the sources and index what came back.
        viewModelScope.launch {
            volumes.changes().drop(1).debounce(VOLUME_DEBOUNCE_MS).collect { library.refresh() }
        }
        viewModelScope.launch { library.refresh() }
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

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            when (val result = library.addSource(treeUri)) {
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

    fun removeSource(sourceId: Long) {
        viewModelScope.launch { library.removeSource(sourceId) }
    }

    fun rate(rating: Int) = act { ids -> library.setRating(ids, rating) }

    fun flag(flag: PickFlag) = act { ids -> library.setFlag(ids, flag) }

    /** Rates or flags one photo (the viewer). */
    fun rateOne(photoId: Long, rating: Int) = act(listOf(photoId)) { ids -> library.setRating(ids, rating) }

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

    private fun restoreQuery(): PhotoQuery = savedState.get<String>(KEY_QUERY)?.let(::decodeQuery) ?: PhotoQuery()

    companion object {
        private const val KEY_SELECTION = "selection"
        private const val KEY_QUERY = "query"
        private const val QUERY_PARTS = 4
        private const val GRID_DEBOUNCE_MS = 250L
        private const val VOLUME_DEBOUNCE_MS = 500L
        private const val TAG = "RevealLibrary"

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
