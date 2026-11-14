package dev.hfps.reveal.feature.editor.layout

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.editor.EditorLayout
import dev.hfps.reveal.data.editor.EditorLayoutPreferences
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.feature.editor.EditorRoute
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The tablet editor's filmstrip and remembered layout: the photos of the grid the editor was opened from
 * ([EditorRoute.strip], live from Room so ratings and edited thumbnails follow) and the panel width / filmstrip
 * visibility from [EditorLayoutPreferences].
 */
@HiltViewModel
class FilmstripViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    private val thumbnails: ThumbnailRepository,
    private val preferences: EditorLayoutPreferences,
) : ViewModel() {
    private val route: EditorRoute = savedStateHandle.toRoute<EditorRoute>()

    /** The version being edited (`null` for a photo opened from another app: no filmstrip). */
    val currentVersionId: Long? = route.versionId

    /** The photos of the strip; empty without a scope. */
    val photos: StateFlow<List<PhotoRow>> = route.strip?.let(PhotoListCodec::decode)?.let(library::photos)
        .let { it ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val layout: StateFlow<EditorLayout> = preferences.layout

    val thumbs: FilmstripThumbs = object : FilmstripThumbs {
        override fun cached(row: PhotoRow): ImageBitmap? = thumbnails.inMemory(row.id, row.editTime)?.asImageBitmap()

        override suspend fun load(row: PhotoRow): ImageBitmap? =
            thumbnails.thumbnail(row.id, row.versionId, row.editTime)?.asImageBitmap()

        override suspend fun upgrade(row: PhotoRow): ImageBitmap? =
            thumbnails.withAiMasks(row.id, row.versionId, row.editTime)?.asImageBitmap()
    }

    fun setFilmstripVisible(visible: Boolean) = preferences.setFilmstripVisible(visible)

    fun setPanelWidth(dp: Float) = preferences.setPanelWidth(dp)

    fun setPanelHeight(dp: Float) = preferences.setPanelHeight(dp)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
