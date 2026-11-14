package dev.hfps.reveal.feature.library.viewer

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.edit.CopySource
import dev.hfps.reveal.data.edit.HistoryWriter
import dev.hfps.reveal.data.edit.VersionInfo
import dev.hfps.reveal.data.edit.VersionRepository
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.OriginalPlace
import dev.hfps.reveal.data.library.PhotoDetail
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.thumb.DisplayImage
import dev.hfps.reveal.data.thumb.PhotoImages
import dev.hfps.reveal.data.thumb.RegionSource
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.feature.library.ConflictChoice
import dev.hfps.reveal.feature.library.ForgetPrompt
import dev.hfps.reveal.feature.library.UnavailableHelper
import dev.hfps.reveal.feature.library.UnavailableInfo
import dev.hfps.reveal.feature.library.ViewerRoute
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.engine.ConflictSide
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the viewer's pages and info sheet read (the ViewModel, or a fake in tests). */
interface ViewerPages {
    /** The screen-size image of photo [id] as version [versionId] shows it (a virtual copy has its own edit). */
    suspend fun display(id: Long, versionId: Long, longEdge: Int): DisplayImage?

    suspend fun developed(id: Long, versionId: Long, longEdge: Int): Bitmap?

    suspend fun region(id: Long): RegionSource?

    suspend fun detail(id: Long): PhotoDetail?

    suspend fun metadata(detail: PhotoDetail): PhotoMetadata?

    /** Where the photo's original is (the info's "Original" line); null when unknown. */
    suspend fun originalPlace(id: Long): OriginalPlace? = null

    /** Nothing can show the photo (`PhotoAvailability`): the viewer draws the placeholder and its explanation. */
    suspend fun isUnavailable(id: Long): Boolean = false
}

/** One viewer page: a photo's version (the photo's first one, or a virtual copy, docs/adr/0025). */
data class ViewerPage(val photoId: Long, val versionId: Long)

/** The cells the viewer pages through (a snapshot of the grid's list when it opened) and where it starts. */
data class ViewerUiState(
    val pages: List<ViewerPage> = emptyList(),
    val start: Int = 0,
    val loaded: Boolean = false,
    /** The explanation of an unavailable photo (FIX/U); null = closed. */
    val unavailable: UnavailableInfo? = null,
    /** Asking before forgetting a photo that has edits. */
    val forgetPrompt: ForgetPrompt? = null,
    /** Bumped when access was re-checked, so the pages try their image again. */
    val reloads: Int = 0,
    /** The open conflict chooser (ADR 0018 FIX/W); null = closed. */
    val conflict: ConflictChoice? = null,
    /** Bumped when a conflict was resolved, so the page asks again whether it has one. */
    val conflictsResolved: Int = 0,
    /** The versions sheet of a photo (docs/adr/0025); null = closed. */
    val versions: VersionsUi? = null,
    /** A page to move to (a new virtual copy, a version picked in the sheet); consumed by the screen. */
    val jumpTo: Int? = null,
) {
    val ids: List<Long> get() = pages.map { it.photoId }
}

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
    private val sync: SyncRepository,
    private val writer: HistoryWriter,
    private val versions: VersionRepository,
) : ViewModel(),
    ViewerPages {
    private val route = savedState.toRoute<ViewerRoute>()
    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    /** The grid's query (`PhotoListCodec`: view and filters), for the editor's filmstrip; `null` without one. */
    val strip: String? = PhotoListCodec.decode(route.query)?.let(PhotoListCodec::encode)

    init {
        viewModelScope.launch {
            // One page per grid cell: a virtual copy is a page of its own (docs/adr/0025).
            val pages = PhotoListCodec.decode(route.query)?.let { query ->
                library.photoList(query).map { ViewerPage(it.id, it.versionId) }
            }.orEmpty().ifEmpty {
                listOfNotNull(versions.versions(route.photoId).firstOrNull()?.let { ViewerPage(route.photoId, it.id) })
            }
            val start = pages.indexOfFirst { it.versionId == route.versionId }.takeIf { it >= 0 }
                ?: pages.indexOfFirst { it.photoId == route.photoId }
            _state.update { it.copy(pages = pages, start = start.coerceAtLeast(0), loaded = true) }
        }
    }

    // ------------------------------------------------------------------ versions (docs/adr/0025)

    /** Opens the versions sheet of [page]'s photo. */
    fun openVersions(page: ViewerPage, photoName: String) {
        viewModelScope.launch {
            val list = versions.versions(page.photoId)
            _state.update { it.copy(versions = VersionsUi(photoName, list, page.versionId)) }
        }
    }

    fun dismissVersions() = _state.update { it.copy(versions = null) }

    /** Shows [version]: its page when the viewer lists it, else a page added after its photo's last one. */
    fun showVersion(version: VersionInfo) {
        _state.update { s -> s.withPage(ViewerPage(version.photoId, version.id)).copy(versions = null) }
    }

    /** A virtual copy of the version on screen (its edit, or the original), shown at once. */
    fun createCopy(source: CopySource, nameFor: (Int) -> String) {
        val sheet = _state.value.versions ?: return
        viewModelScope.launch {
            writer.flush()
            val id = versions.createCopy(sheet.currentVersionId, source, nameFor) ?: return@launch
            val photoId = versions.version(id)?.photoId ?: return@launch
            _state.update { s -> s.withPage(ViewerPage(photoId, id)).copy(versions = null) }
        }
    }

    fun renameVersion(version: VersionInfo, name: String) {
        viewModelScope.launch {
            versions.rename(version.id, name)
            refreshVersions()
        }
    }

    /** Deletes a virtual copy; its page leaves the viewer. */
    fun deleteVersion(version: VersionInfo) {
        viewModelScope.launch {
            writer.flush()
            if (!versions.delete(version.id)) return@launch
            _state.update { s ->
                val pages = s.pages.filter { it.versionId != version.id }
                s.copy(pages = pages)
            }
            refreshVersions()
        }
    }

    /** The screen moved to the requested page. */
    fun jumped() = _state.update { it.copy(jumpTo = null) }

    private suspend fun refreshVersions() {
        val sheet = _state.value.versions ?: return
        val photoId = sheet.versions.firstOrNull()?.photoId ?: return
        val list = versions.versions(photoId)
        _state.update { s -> s.versions?.let { s.copy(versions = it.copy(versions = list)) } ?: s }
    }

    /** [page] added after its photo's last page when missing, and asked to be shown. */
    private fun ViewerUiState.withPage(page: ViewerPage): ViewerUiState {
        val existing = pages.indexOf(page)
        if (existing >= 0) return copy(jumpTo = existing)
        val after = pages.indexOfLast { it.photoId == page.photoId }
        val at = if (after < 0) pages.size else after + 1
        return copy(pages = pages.toMutableList().apply { add(at, page) }, jumpTo = at)
    }

    fun photo(id: Long): Flow<PhotoEntity?> = library.observePhoto(id)

    override suspend fun display(id: Long, versionId: Long, longEdge: Int): DisplayImage? = runCatching {
        images.display(id, longEdge, versionId)
    }.getOrNull()

    override suspend fun developed(id: Long, versionId: Long, longEdge: Int): Bitmap? =
        images.developed(id, longEdge, versionId)

    /** The version [versionId] as the top bar names it (null when gone). */
    suspend fun version(versionId: Long): VersionInfo? = versions.version(versionId)

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

    /** True when [id] has a sync conflict copy (the "Resolve conflict" button). */
    suspend fun hasConflict(id: Long): Boolean = sync.conflictOfPhoto(id) != null

    fun openConflict(id: Long, name: String) {
        viewModelScope.launch {
            writer.flush()
            sync.conflictOfPhoto(id)?.let { pair -> _state.update { it.copy(conflict = ConflictChoice(name, pair)) } }
        }
    }

    fun keepConflict(keepCopy: Boolean) {
        val choice = _state.value.conflict ?: return
        _state.update { it.copy(conflict = null) }
        viewModelScope.launch {
            writer.flush()
            sync.keep(choice.pair, keepCopy)
            _state.update { it.copy(conflictsResolved = it.conflictsResolved + 1, reloads = it.reloads + 1) }
        }
    }

    fun dismissConflict() = _state.update { it.copy(conflict = null) }

    /** A conflict side's thumbnail (its own edit). */
    suspend fun conflictThumbnail(photoId: Long, side: ConflictSide): ImageBitmap? =
        thumbnails.thumbnail(photoId, side.versionId, side.editTime)?.asImageBitmap()

    /** The version the editor opens for [id] (its first; a page knows its own version). */
    suspend fun versionOf(id: Long): Long? = library.detail(id)?.versionId
}
