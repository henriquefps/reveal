package dev.hfps.reveal.feature.library.viewer

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.OriginalPlace
import dev.hfps.reveal.data.library.PhotoDetail
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.thumb.DisplayImage
import dev.hfps.reveal.data.thumb.PhotoImages
import dev.hfps.reveal.data.thumb.RegionSource
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.feature.library.ForgetPrompt
import dev.hfps.reveal.feature.library.LibraryViewModel
import dev.hfps.reveal.feature.library.UnavailableHelper
import dev.hfps.reveal.feature.library.UnavailableInfo
import dev.hfps.reveal.feature.library.ViewerRoute
import dev.hfps.reveal.feature.library.scope
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the viewer's pages and info sheet read (the ViewModel, or a fake in tests). */
interface ViewerPages {
    suspend fun display(id: Long, longEdge: Int): DisplayImage?

    suspend fun developed(id: Long, longEdge: Int): Bitmap?

    suspend fun region(id: Long): RegionSource?

    suspend fun detail(id: Long): PhotoDetail?

    suspend fun metadata(detail: PhotoDetail): PhotoMetadata?

    /** Where the photo's original is (the info's "Original" line); null when unknown. */
    suspend fun originalPlace(id: Long): OriginalPlace? = null

    /** Nothing can show the photo (`PhotoAvailability`): the viewer draws the placeholder and its explanation. */
    suspend fun isUnavailable(id: Long): Boolean = false
}

/** The photos the viewer pages through (a snapshot of the grid's list when it opened) and where it starts. */
data class ViewerUiState(
    val ids: List<Long> = emptyList(),
    val start: Int = 0,
    val loaded: Boolean = false,
    /** The explanation of an unavailable photo (FIX/U); null = closed. */
    val unavailable: UnavailableInfo? = null,
    /** Asking before forgetting a photo that has edits. */
    val forgetPrompt: ForgetPrompt? = null,
    /** Bumped when access was re-checked, so the pages try their image again. */
    val reloads: Int = 0,
)

/**
 * The full-screen viewer: the grid's photo list (same scope, filter, order, search) as a snapshot, so rating a photo
 * out of a filter does not pull the page away; per page the photo row (live, for stars/flag), its images and info.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
    private val images: PhotoImages,
    val thumbnails: ThumbnailRepository,
    private val unavailableHelper: UnavailableHelper,
) : ViewModel(),
    ViewerPages {
    private val route = savedState.toRoute<ViewerRoute>()
    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    /** The grid's query with its scope (`PhotoListCodec`), for the editor's filmstrip; `null` without a scope. */
    val strip: String? = LibraryViewModel.decodeSelection(route.selection)?.scope()?.let { scope ->
        PhotoListCodec.encode((LibraryViewModel.decodeQuery(route.query) ?: PhotoQuery()).copy(scope = scope))
    }

    init {
        viewModelScope.launch {
            val selection = LibraryViewModel.decodeSelection(route.selection)
            val query = (LibraryViewModel.decodeQuery(route.query) ?: PhotoQuery())
            val scope = selection?.scope()
            val ids = if (scope == null) {
                listOf(route.photoId)
            } else {
                library.photoList(query.copy(scope = scope)).map { it.id }.distinct().ifEmpty { listOf(route.photoId) }
            }
            _state.update {
                it.copy(ids = ids, start = ids.indexOf(route.photoId).coerceAtLeast(0), loaded = true)
            }
        }
    }

    fun photo(id: Long): Flow<PhotoEntity?> = library.observePhoto(id)

    override suspend fun display(id: Long, longEdge: Int): DisplayImage? = runCatching {
        images.display(id, longEdge)
    }.getOrNull()

    override suspend fun developed(id: Long, longEdge: Int): Bitmap? = images.developed(id, longEdge)

    override suspend fun region(id: Long): RegionSource? = images.region(id)

    override suspend fun detail(id: Long): PhotoDetail? = library.detail(id)

    override suspend fun metadata(detail: PhotoDetail): PhotoMetadata? = library.metadata(detail)

    override suspend fun originalPlace(id: Long): OriginalPlace? = library.originalPlace(id)

    override suspend fun isUnavailable(id: Long): Boolean = library.isUnavailable(id)

    /** Explains why [id] cannot be shown and what to do (the placeholder's button). */
    fun explain(id: Long) {
        viewModelScope.launch {
            unavailableHelper.info(id)?.let { info -> _state.update { it.copy(unavailable = info) } }
        }
    }

    fun dismissUnavailable() = _state.update { it.copy(unavailable = null) }

    fun openSender() {
        _state.value.unavailable?.let(unavailableHelper::openApp)
    }

    /** Re-checks access now: a photo readable again shows on its page. */
    fun tryAgain(id: Long) {
        viewModelScope.launch {
            library.recheckAccess(listOf(id), force = true)
            _state.update { it.copy(unavailable = null, reloads = it.reloads + 1) }
        }
    }

    /** "Remove from Reveal": at once, or after asking when the photo has edits; [onDone] when it is gone. */
    fun forget(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            if (library.editedCount(listOf(id)) == 0) {
                library.forget(listOf(id))
                _state.update { it.copy(unavailable = null) }
                onDone()
            } else {
                _state.update { it.copy(unavailable = null, forgetPrompt = ForgetPrompt(listOf(id), 1)) }
            }
        }
    }

    fun confirmForget(onDone: () -> Unit) {
        val prompt = _state.value.forgetPrompt ?: return
        viewModelScope.launch {
            library.forget(prompt.ids)
            _state.update { it.copy(forgetPrompt = null) }
            onDone()
        }
    }

    fun dismissForget() = _state.update { it.copy(forgetPrompt = null) }

    fun rate(id: Long, rating: Int) {
        viewModelScope.launch { library.setRating(listOf(id), rating) }
    }

    fun flag(id: Long, flag: PickFlag) {
        viewModelScope.launch { library.setFlag(listOf(id), flag) }
    }

    /** The version the editor opens for [id] (its first; virtual copies are M5). */
    suspend fun versionOf(id: Long): Long? = library.detail(id)?.versionId
}
