package dev.hfps.reveal.feature.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.feature.export.ui.ExportRequest
import dev.hfps.reveal.feature.export.ui.ExportSheetHost
import dev.hfps.reveal.feature.library.presets.ApplyPresetSheet
import dev.hfps.reveal.feature.library.viewer.ViewerScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The library: start destination of the app. [fromApps] opens on "Opened from other apps" (several photos were just
 * handed over, ADR 0011).
 */
@Serializable
data class LibraryRoute(val fromApps: Boolean = false)

/**
 * The full-screen viewer on [photoId], paging through the same photos as the grid it came from: [query] is the grid's
 * (view and filters), encoded by `PhotoListCodec`.
 */
@Serializable
data class ViewerRoute(val photoId: Long, val query: String)

/**
 * MIME types of "Open a photo": every image (JPEG, HEIF, PNG, WebP and the `image/x-*` RAW types) plus
 * `application/octet-stream`, which some providers report for ARW and other RAW files.
 */
val OpenPhotoMimeTypes: Array<String> = arrayOf("image/*", "application/octet-stream")

/**
 * Adds the library and viewer destinations. [onEdit] opens the editor on a version, with the grid's photos for the
 * tablet filmstrip (a `PhotoListCodec` query, `null` = none); [onOpenFile] on a single document picked with "Open a
 * photo".
 */
fun NavGraphBuilder.libraryScreens(
    navController: NavController,
    onEdit: (versionId: Long, strip: String?) -> Unit,
    onOpenFile: (Uri) -> Unit,
    onGallery: () -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit = {},
    onSync: () -> Unit = {},
) {
    composable<LibraryRoute> {
        LibraryScreen(
            onOpen = { row, state ->
                navController.navigate(ViewerRoute(photoId = row.id, query = PhotoListCodec.encode(state.gridQuery)))
            },
            onOpenFile = onOpenFile,
            onGallery = onGallery,
            onAbout = onAbout,
            onSettings = onSettings,
            onSync = onSync,
        )
    }
    composable<ViewerRoute> {
        ViewerScreen(onBack = { navController.popBackStack() }, onEdit = onEdit)
    }
}

/** The library screen with its ViewModel, pickers, messages and back handling. */
@Suppress("LongMethod")
@Composable
fun LibraryScreen(
    onOpen: (PhotoRow, LibraryUiState) -> Unit,
    onOpenFile: (Uri) -> Unit,
    onGallery: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit = {},
    onSync: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenFile(uri)
    }
    val snackbar = remember { SnackbarHostState() }
    LibraryMessages(viewModel.events, snackbar)
    val thumbnails = remember(viewModel) { RepositoryThumbnails(viewModel.thumbnails) }
    var exportRequest by remember { mutableStateOf<ExportRequest?>(null) }
    var choosingPreset by remember { mutableStateOf(false) }
    val presetLibrary by viewModel.presetLibrary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember(viewModel) {
        LibraryActions(
            onFilters = viewModel::setFilters,
            onFiltersOpen = viewModel::setFiltersOpen,
            onFoldersOpen = viewModel::setFoldersOpen,
            onRecent = viewModel::setRecent,
            onShowFolder = viewModel::showFolder,
            onFolderBackup = viewModel::setFolderBackup,
            onSync = onSync,
            onAddFolder = { folderPicker.launch(null) },
            onOpenPhoto = { filePicker.launch(OpenPhotoMimeTypes) },
            onRescan = viewModel::rescan,
            onRemoveSource = viewModel::removeSource,
            onQuery = viewModel::setQuery,
            onSearchOpen = viewModel::setSearchOpen,
            onOpen = { row -> onOpen(row, viewModel.state.value) },
            onSelectionChange = viewModel::setSelected,
            onRate = viewModel::rate,
            onFlag = viewModel::flag,
            onRemovePhotos = viewModel::removeSelected,
            onCopyEdits = viewModel::copyEdits,
            onPasteEdits = viewModel::openPaste,
            onPasteGroups = viewModel::setPasteGroups,
            onPasteConfirm = viewModel::confirmPaste,
            onPasteDismiss = viewModel::dismissPaste,
            onResetEdits = viewModel::resetEdits,
            onDeleteConflictCopies = viewModel::deleteConflictCopies,
            onExport = {
                scope.launch {
                    val versions = viewModel.selectedVersions()
                    if (versions.isNotEmpty()) exportRequest = ExportRequest.Versions(versions)
                }
            },
            onApplyPreset = { choosingPreset = true },
            onOpenUnavailable = viewModel::explain,
            onOpenConflict = viewModel::openConflict,
            onKeepConflict = viewModel::keepConflict,
            onConflictDismiss = viewModel::dismissConflict,
            onUnavailableDismiss = viewModel::dismissUnavailable,
            onOpenSender = viewModel::openSender,
            onTryAgain = viewModel::tryAgain,
            onForget = viewModel::forget,
            onForgetConfirm = viewModel::confirmForget,
            onForgetDismiss = viewModel::dismissForget,
            onAddFolderBackup = viewModel::chooseFolderBackup,
            onAddFolderDismiss = viewModel::dismissAddFolder,
            onRemoveConfirm = viewModel::confirmRemove,
            onRemoveDismiss = viewModel::dismissRemove,
            onDeleteFromServer = viewModel::deleteFromServer,
            onDeleteFromServerConfirm = viewModel::confirmDeleteFromServer,
            onDeleteFromServerDismiss = viewModel::dismissDeleteFromServer,
            onSettings = onSettings,
            onGallery = onGallery,
            onAbout = onAbout,
        )
    }
    ExportSheetHost(request = exportRequest, onDismiss = { exportRequest = null })
    val galleryOffer by viewModel.galleryOffer.collectAsStateWithLifecycle()
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onGalleryAccessResult()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    if (galleryOffer) {
        GalleryAccessSheet(
            onAllow = { galleryPermission.launch(GalleryAccess.permissions()) },
            onDecline = viewModel::declineGalleryAccess,
        )
    }
    state.unavailableInfo?.let { info ->
        UnavailableSheet(
            info = info,
            onOpenApp = actions.onOpenSender,
            onGallery = { galleryPermission.launch(GalleryAccess.permissions()) },
            onTryAgain = { actions.onTryAgain(listOf(info.photoId)) },
            onRemove = { actions.onForget(listOf(info.photoId)) },
            onDismiss = actions.onUnavailableDismiss,
        )
    }
    state.forgetPrompt?.let { prompt ->
        ForgetDialog(prompt, onConfirm = actions.onForgetConfirm, onDismiss = actions.onForgetDismiss)
    }
    state.conflictChoice?.let { choice ->
        ConflictSheet(
            choice = choice,
            thumbnail = { side ->
                viewModel.thumbnails.thumbnail(choice.pair.photoId, side.versionId, side.editTime)?.asImageBitmap()
            },
            onKeep = actions.onKeepConflict,
            onDismiss = actions.onConflictDismiss,
        )
    }
    ServerViewDialogs(state, actions)
    if (choosingPreset) {
        ApplyPresetSheet(
            library = presetLibrary,
            count = state.selected.size,
            onApply = { uuid, strength ->
                choosingPreset = false
                viewModel.applyPreset(context, uuid, strength)
            },
            onDismiss = { choosingPreset = false },
        )
    }
    BackHandler(enabled = state.selecting) { viewModel.setSelected(emptySet()) }
    BackHandler(enabled = !state.selecting && state.searchOpen) { viewModel.setSearchOpen(false) }
    BackHandler(enabled = !state.selecting && !state.searchOpen && state.recent) { viewModel.setRecent(false) }
    LibraryContent(
        state = state,
        actions = actions,
        thumbnails = thumbnails,
        snackbar = snackbar,
        onGridDraw = viewModel::onGridDraw,
        modifier = modifier,
    )
}

/** Shows the ViewModel's one-off messages in the snackbar. */
@Composable
private fun LibraryMessages(events: Flow<LibraryMessage>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(events, resources) {
        events.collect { message ->
            val text = when (message) {
                LibraryMessage.DatabaseOnly -> resources.getString(R.string.library_saved_database_only)

                is LibraryMessage.Removed -> resources.getQuantityString(
                    R.plurals.library_removed,
                    message.count,
                    message.count,
                )

                LibraryMessage.AddFailed -> resources.getString(R.string.library_add_folder_failed)

                LibraryMessage.AlreadyAdded -> resources.getString(R.string.library_add_folder_exists)

                LibraryMessage.Copied -> resources.getString(R.string.library_edits_copied)

                is LibraryMessage.Reset -> resources.getQuantityString(
                    R.plurals.library_edits_reset,
                    message.count,
                    message.count,
                )

                is LibraryMessage.PresetQueued -> resources.getQuantityString(
                    R.plurals.presets_batch_queued,
                    message.count,
                    message.name,
                    message.count,
                )

                is LibraryMessage.Rechecked -> resources.getString(
                    R.string.library_unavailable_checked,
                    message.readable,
                    message.still,
                )

                is LibraryMessage.Forgotten -> resources.getQuantityString(
                    R.plurals.library_forgotten,
                    message.count,
                    message.count,
                )

                is LibraryMessage.DeletedFromServer -> resources.getQuantityString(
                    R.plurals.library_deleted_server,
                    message.count,
                    message.count,
                )

                is LibraryMessage.Pasted -> resources.getQuantityString(
                    R.plurals.library_edits_pasted,
                    message.count,
                    message.count,
                )
            }
            snackbar.showSnackbar(text)
        }
    }
}

/** [ThumbnailSource] over the thumbnail cache. */
private class RepositoryThumbnails(private val repository: ThumbnailRepository) : ThumbnailSource {
    override fun cached(row: PhotoRow): ImageBitmap? = repository.inMemory(row.id, row.editTime)?.asImageBitmap()

    override suspend fun load(row: PhotoRow): ImageBitmap? =
        repository.thumbnail(row.id, row.versionId, row.editTime)?.asImageBitmap()

    override suspend fun upgrade(row: PhotoRow): ImageBitmap? =
        repository.withAiMasks(row.id, row.versionId, row.editTime)?.asImageBitmap()
}
