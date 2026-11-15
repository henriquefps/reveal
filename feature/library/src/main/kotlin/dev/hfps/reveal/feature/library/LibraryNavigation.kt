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
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.hfps.reveal.data.library.XmpDestination
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.feature.export.ui.ExportRequest
import dev.hfps.reveal.feature.export.ui.ExportSheetHost
import dev.hfps.reveal.feature.library.presets.ApplyPresetSheet
import dev.hfps.reveal.feature.library.viewer.DeleteCopiesDialog
import dev.hfps.reveal.feature.library.viewer.ViewerScreen
import dev.hfps.reveal.feature.library.xmpexport.XmpExportSheet
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
 * The full-screen viewer on [photoId] ([versionId]: the cell tapped, a virtual copy included), paging through the same
 * cells as the grid it came from: [query] is the grid's (view and filters), encoded by `PhotoListCodec`.
 */
@Serializable
data class ViewerRoute(val photoId: Long, val query: String, val versionId: Long = NO_VERSION) {
    companion object {
        /** No version given: the viewer starts on the photo's first version. */
        const val NO_VERSION = -1L
    }
}

/** "Copy N" in the app's language, for new virtual copies (docs/adr/0025). */
fun copyNamer(context: android.content.Context): (Int) -> String =
    { n -> context.getString(R.string.library_copy_name, n) }

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
    onAbout: () -> Unit,
    onSettings: () -> Unit = {},
    onSync: () -> Unit = {},
) {
    composable<LibraryRoute> {
        LibraryScreen(
            onOpen = { row, state ->
                navController.navigate(
                    ViewerRoute(
                        photoId = row.id,
                        query = PhotoListCodec.encode(state.gridQuery),
                        versionId = row.versionId,
                    ),
                )
            },
            onOpenFile = onOpenFile,
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
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenFile(uri)
    }
    val snackbar = remember { SnackbarHostState() }
    LibraryMessages(viewModel.events, snackbar)
    val thumbnails = remember(viewModel) { RepositoryThumbnails(viewModel.thumbnails) }
    var exportRequest by remember { mutableStateOf<ExportRequest?>(null) }
    var choosingPreset by remember { mutableStateOf(false) }
    val presetLibrary by viewModel.presetLibrary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actions = remember(viewModel) {
        LibraryActions(
            onFilters = viewModel::setFilters,
            onFiltersOpen = viewModel::setFiltersOpen,
            onGrouping = viewModel::setGrouping,
            onDateJumpOpen = viewModel::setDateJumpOpen,
            onDateRangeOpen = viewModel::setDateRangeOpen,
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
            onCreateCopies = { viewModel.createCopies(copyNamer(context)) },
            onDeleteCopies = viewModel::deleteCopies,
            onDeleteCopiesConfirm = viewModel::confirmDeleteCopies,
            onDeleteCopiesDismiss = viewModel::dismissDeleteCopies,
            onExport = {
                scope.launch {
                    val versions = viewModel.selectedVersions()
                    if (versions.isNotEmpty()) exportRequest = ExportRequest.Versions(versions)
                }
            },
            onApplyPreset = { choosingPreset = true },
            onExportXmp = viewModel::openXmpExport,
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
            onAbout = onAbout,
        )
    }
    ExportSheetHost(request = exportRequest, onDismiss = { exportRequest = null })
    val galleryOffer by viewModel.galleryOffer.collectAsStateWithLifecycle()
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onGalleryAccessResult()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    val galleryPartial by viewModel.galleryPartial.collectAsStateWithLifecycle()
    if (galleryOffer) {
        GalleryAccessSheet(
            partial = galleryPartial,
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
    state.deleteCopiesPrompt?.let { copies ->
        DeleteCopiesDialog(
            count = copies.size,
            name = null,
            onConfirm = actions.onDeleteCopiesConfirm,
            onDismiss = actions.onDeleteCopiesDismiss,
        )
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
    XmpExportHost(
        state.xmpExport,
        onRun = { viewModel.runXmpExport(context, it) },
        onDismiss = viewModel::dismissXmpExport,
    )
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

/** Export XMP's destination sheet and the folder pickers it launches (ADR 0026 "Amendment W4"); public for its test. */
@Composable
fun XmpExportHost(sheet: XmpExportSheetState?, onRun: (XmpDestination) -> Unit, onDismiss: () -> Unit) {
    // The picker in flight: a folder to write the sidecars into (null), or the one folder to write next to (its id).
    // Saveable: the activity may be recreated (rotation) while the system picker is open.
    var pickSource by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val source = pickSource
        pickSource = null
        if (uri != null) {
            onRun(
                if (source == null) {
                    XmpDestination.Folder(uri.toString())
                } else {
                    XmpDestination.NextToPhotos(mapOf(source to uri.toString()))
                },
            )
        }
    }
    sheet?.let {
        XmpExportSheet(
            scope = it.scope,
            onNextToPhotos = { onRun(XmpDestination.NextToPhotos()) },
            onPickFolderToWrite = { sourceId ->
                pickSource = sourceId
                picker.launch(null)
            },
            onChooseFolder = {
                pickSource = null
                picker.launch(null)
            },
            onDismiss = onDismiss,
        )
    }
}

/** Shows the ViewModel's one-off messages in the snackbar. */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun LibraryMessages(events: Flow<LibraryMessage>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(events, resources) {
        events.collect { message ->
            val text = when (message) {
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

                is LibraryMessage.XmpQueued -> resources.getQuantityString(
                    R.plurals.xmp_export_queued,
                    message.count,
                    message.count,
                )

                LibraryMessage.XmpWrongFolder -> resources.getString(R.string.xmp_export_wrong_folder)

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

                is LibraryMessage.CopiesCreated -> resources.getQuantityString(
                    R.plurals.library_copies_created,
                    message.count,
                    message.count,
                )

                is LibraryMessage.CopiesDeleted -> resources.getQuantityString(
                    R.plurals.library_copies_deleted,
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
    override fun cached(row: PhotoRow): ImageBitmap? = cached(row, ThumbnailRepository.EDGE)

    override suspend fun load(row: PhotoRow): ImageBitmap? = load(row, ThumbnailRepository.EDGE)

    override fun cached(row: PhotoRow, edge: Int): ImageBitmap? =
        repository.inMemory(row.id, row.editTime, edge)?.asImageBitmap()

    // prepareToDraw: the texture upload starts on the render thread now, not in the frame that first draws the cell.
    override suspend fun load(row: PhotoRow, edge: Int): ImageBitmap? =
        repository.thumbnail(row.id, row.versionId, row.editTime, edge)?.also { it.prepareToDraw() }?.asImageBitmap()

    override suspend fun upgrade(row: PhotoRow): ImageBitmap? =
        repository.withAiMasks(row.id, row.versionId, row.editTime)?.also { it.prepareToDraw() }?.asImageBitmap()
}
