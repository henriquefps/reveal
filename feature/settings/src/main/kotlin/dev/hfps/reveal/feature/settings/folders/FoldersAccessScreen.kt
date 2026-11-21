package dev.hfps.reveal.feature.settings.folders

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.handoff.GalleryAccessState
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.feature.settings.Hint
import dev.hfps.reveal.feature.settings.NavigationRow
import dev.hfps.reveal.feature.settings.R
import dev.hfps.reveal.feature.settings.SectionTitle
import dev.hfps.reveal.feature.settings.SettingsBody
import dev.hfps.reveal.feature.settings.SwitchRow
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.work.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A folder and its originals-backup mode. */
@Immutable
data class BackupFolder(
    val id: Long,
    val name: String,
    val backup: Boolean,
    /** Hoard's phone backup covers it (ADR 0018 "R1"): shown read only, "Backed up by Hoard". */
    val coveredByHoard: Boolean = false,
)

/** Everything "Folders and photo access" shows. */
@Immutable
data class FoldersUiState(
    val paired: Boolean = false,
    /** "Back up originals" (Backup and sync): the folders' modes only matter with it on. */
    val originals: Boolean = true,
    val folders: List<BackupFolder> = emptyList(),
    val gallery: GalleryAccessState = GalleryAccessState.NONE,
)

/** What "Folders and photo access" can ask for. */
@Immutable
data class FoldersActions(
    val onBack: () -> Unit = {},
    val onFolderBackup: (Long, Boolean) -> Unit = { _, _ -> },
    val onGallery: () -> Unit = {},
    /** Not paired: opens "Photo server". */
    val onServer: () -> Unit = {},
    /** Originals off: opens "Backup and sync". */
    val onBackup: () -> Unit = {},
)

/** Test tags of "Folders and photo access". */
object FoldersTags {
    const val SCREEN = "folders_screen"
    const val GALLERY = "folders_gallery"
    const val PAIR = "folders_pair"
    const val BACKUP = "folders_backup"
    const val NONE = "folders_none"

    fun folder(id: Long) = "sync_folder_$id"
}

@HiltViewModel
class FoldersAccessViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    sync: SyncRepository,
    private val library: LibraryRepository,
    private val gallery: GalleryAccess,
) : ViewModel() {
    val state: StateFlow<FoldersUiState> = combine(
        sync.account,
        sync.options,
        library.sources(),
        gallery.state,
    ) { account, options, sources, access ->
        FoldersUiState(
            paired = account != null,
            originals = options.originals,
            folders = sources.map { BackupFolder(it.id, it.name, it.backup, it.coveredByHoard) },
            gallery = access,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_MS),
        FoldersUiState(paired = sync.account.value != null, gallery = gallery.state.value),
    )

    /** The same switch as the library's Manage folders sheet: one source of truth, [LibraryRepository.setBackup]. */
    fun setFolderBackup(id: Long, backup: Boolean) {
        viewModelScope.launch {
            library.setBackup(id, backup)
            SyncScheduler.blobs(context)
        }
    }

    /** After the permission dialog: the offer is answered; which hand-offs read again. */
    fun onGalleryResult() {
        gallery.setAsked()
        gallery.refresh()
        library.scheduleAccessCheck(again = true)
    }

    /** Back from the app settings page (or anywhere): the permission may have changed. */
    fun onResumed() {
        if (gallery.refresh()) library.scheduleAccessCheck(again = true)
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun FoldersAccessScreen(
    onBack: () -> Unit,
    onServer: () -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoldersAccessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onGalleryResult()
        val activity = context as? Activity
        val blocked = result.values.none { it } && activity != null &&
            GalleryAccess.permissions().none { activity.shouldShowRequestPermissionRationale(it) }
        // The system no longer shows the dialog (denied twice): the app's own settings page is where it changes.
        if (blocked) context.startActivity(appSettings(context))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    FoldersAccessContent(
        state = state,
        actions = FoldersActions(
            onBack = onBack,
            onFolderBackup = viewModel::setFolderBackup,
            onGallery = {
                // Turning it off (or widening "selected photos") happens in the system's page for the app.
                if (state.gallery == GalleryAccessState.FULL) {
                    context.startActivity(appSettings(context))
                } else {
                    permission.launch(GalleryAccess.permissions())
                }
            },
            onServer = onServer,
            onBackup = onBackup,
        ),
        modifier = modifier,
    )
}

private fun appSettings(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Stateless "Folders and photo access" (screenshots, tests). */
@Composable
fun FoldersAccessContent(state: FoldersUiState, actions: FoldersActions, modifier: Modifier = Modifier) {
    RevealScaffold(
        modifier = modifier.testTag(FoldersTags.SCREEN),
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.folders_title),
                onBack = actions.onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        SettingsBody {
            SectionTitle(stringResource(R.string.sync_section_folders), first = true)
            FolderModes(state, actions)

            SectionTitle(stringResource(R.string.settings_gallery))
            Hint(stringResource(R.string.folders_gallery_hint))
            NavigationRow(
                title = stringResource(R.string.settings_gallery),
                subtitle = stringResource(
                    when (state.gallery) {
                        GalleryAccessState.FULL -> R.string.settings_gallery_full
                        GalleryAccessState.PARTIAL -> R.string.settings_gallery_partial
                        GalleryAccessState.NONE -> R.string.settings_gallery_off
                    },
                ),
                icon = PhosphorRegular.Image,
                onClick = actions.onGallery,
                tag = FoldersTags.GALLERY,
            )
        }
    }
}

/** Each folder's mode, or why there is no choice yet (not paired, originals off, no folders). */
@Composable
private fun FolderModes(state: FoldersUiState, actions: FoldersActions) {
    when {
        !state.paired -> {
            Hint(stringResource(R.string.folders_not_paired))
            NavigationRow(
                title = stringResource(R.string.server_title),
                subtitle = stringResource(R.string.hub_server_off),
                icon = PhosphorRegular.Lock,
                onClick = actions.onServer,
                tag = FoldersTags.PAIR,
            )
        }

        !state.originals -> {
            Hint(stringResource(R.string.folders_originals_off))
            NavigationRow(
                title = stringResource(R.string.backup_title),
                subtitle = stringResource(R.string.hub_backup_subtitle),
                icon = PhosphorRegular.Upload,
                onClick = actions.onBackup,
                tag = FoldersTags.BACKUP,
            )
        }

        else -> {
            Hint(stringResource(R.string.sync_folders_hint))
            if (state.folders.isEmpty()) {
                Hint(stringResource(R.string.folders_none), Modifier.testTag(FoldersTags.NONE))
            }
            for (folder in state.folders) FolderBackupRow(folder, actions)
        }
    }
}

/** One folder's backup mode: a switch, or "Backed up by Hoard" (read only) when Hoard's phone backup covers it. */
@Composable
private fun FolderBackupRow(folder: BackupFolder, actions: FoldersActions) {
    if (folder.coveredByHoard) {
        // Hoard's phone backup covers it (R1): its originals are left to Hoard, no choice to make here.
        RevealListRow(
            title = folder.name,
            subtitle = stringResource(R.string.sync_folder_hoard),
            icon = PhosphorRegular.CloudCheck,
            modifier = Modifier.testTag(FoldersTags.folder(folder.id)),
        )
        return
    }
    // On = "Everything", off = "Only photos you edit" (the default for new folders, FIX/V).
    SwitchRow(
        title = folder.name,
        subtitle = stringResource(if (folder.backup) R.string.sync_folder_everything else R.string.sync_folder_edited),
        checked = folder.backup,
        onChange = { actions.onFolderBackup(folder.id, it) },
        tag = FoldersTags.folder(folder.id),
    )
}
