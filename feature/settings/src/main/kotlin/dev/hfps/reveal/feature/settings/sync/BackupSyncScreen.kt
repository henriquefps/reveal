package dev.hfps.reveal.feature.settings.sync

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.designsystem.components.RevealRowDivider
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.Hint
import dev.hfps.reveal.feature.settings.NavigationRow
import dev.hfps.reveal.feature.settings.R
import dev.hfps.reveal.feature.settings.SectionTitle
import dev.hfps.reveal.feature.settings.SettingsBody
import dev.hfps.reveal.feature.settings.SwitchRow
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.SyncStatus
import dev.hfps.reveal.sync.account.SyncOptions
import dev.hfps.reveal.sync.work.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything "Backup and sync" shows: what goes to the server, and when (ADR 0018). */
@Immutable
data class BackupUiState(
    val paired: Boolean = false,
    val options: SyncOptions = SyncOptions(),
    val status: SyncStatus = SyncStatus(),
    /** "Photos opened from other apps": everything (true) or only edited photos (the default, FIX/W). */
    val handoffBackup: Boolean = false,
)

/** What "Backup and sync" can ask for. */
@Immutable
data class BackupActions(
    val onBack: () -> Unit = {},
    val onOptions: (SyncOptions) -> Unit = {},
    val onHandoffBackup: (Boolean) -> Unit = {},
    /** Not paired: opens "Photo server". */
    val onServer: () -> Unit = {},
    /** "What each folder backs up": opens "Folders and photo access". */
    val onFolders: () -> Unit = {},
)

/** Test tags of "Backup and sync" (the switches keep their [SyncTags]). */
object BackupTags {
    const val SCREEN = "backup_screen"
    const val PAIR = "backup_pair"
    const val FOLDERS = "backup_folders"
    const val STORAGE = "backup_storage"
}

@HiltViewModel
class BackupSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: SyncRepository,
    private val library: LibraryRepository,
) : ViewModel() {
    val state: StateFlow<BackupUiState> = combine(
        sync.account,
        sync.options,
        sync.status,
        library.handoffBackup(),
    ) { account, options, status, handoffBackup ->
        BackupUiState(paired = account != null, options = options, status = status, handoffBackup = handoffBackup)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_MS),
        BackupUiState(paired = sync.account.value != null),
    )

    fun setOptions(options: SyncOptions) {
        sync.setOptions(options)
        SyncScheduler.blobs(context)
    }

    fun setHandoffBackup(everything: Boolean) {
        viewModelScope.launch {
            library.setHandoffBackup(everything)
            SyncScheduler.blobs(context)
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun BackupSyncScreen(
    onBack: () -> Unit,
    onServer: () -> Unit,
    onFolders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackupSyncContent(
        state = state,
        actions = BackupActions(
            onBack = onBack,
            onOptions = viewModel::setOptions,
            onHandoffBackup = viewModel::setHandoffBackup,
            onServer = onServer,
            onFolders = onFolders,
        ),
        modifier = modifier,
    )
}

/** Stateless "Backup and sync" (screenshots, tests). */
@Composable
fun BackupSyncContent(state: BackupUiState, actions: BackupActions, modifier: Modifier = Modifier) {
    RevealScaffold(
        modifier = modifier.testTag(BackupTags.SCREEN),
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.backup_title),
                onBack = actions.onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        SettingsBody {
            if (state.paired) Paired(state, actions) else NotPaired(actions)
        }
    }
}

@Composable
private fun NotPaired(actions: BackupActions) {
    SectionTitle(stringResource(R.string.sync_section_what), first = true)
    Hint(stringResource(R.string.backup_not_paired))
    NavigationRow(
        title = stringResource(R.string.server_title),
        subtitle = stringResource(R.string.hub_server_off),
        icon = PhosphorRegular.Lock,
        onClick = actions.onServer,
        tag = BackupTags.PAIR,
    )
}

@Composable
private fun Paired(state: BackupUiState, actions: BackupActions) {
    val options = state.options
    SectionTitle(stringResource(R.string.sync_section_what), first = true)
    Hint(stringResource(R.string.sync_edits_always))
    SwitchRow(
        title = stringResource(R.string.sync_originals),
        subtitle = stringResource(R.string.sync_originals_subtitle),
        checked = options.originals,
        onChange = { actions.onOptions(options.copy(originals = it)) },
        tag = SyncTags.ORIGINALS,
    )
    SwitchRow(
        title = stringResource(R.string.sync_wifi_only),
        subtitle = stringResource(R.string.sync_wifi_only_subtitle),
        checked = options.originalsWifiOnly,
        enabled = options.originals,
        onChange = { actions.onOptions(options.copy(originalsWifiOnly = it)) },
        tag = SyncTags.WIFI_ONLY,
    )
    // Hand-offs: the folders' two modes, "Only photos you edit" by default (FIX/W); a choice only with originals on.
    SwitchRow(
        title = stringResource(R.string.sync_handoff_backup),
        subtitle = stringResource(
            if (state.handoffBackup) R.string.sync_folder_everything else R.string.sync_folder_edited,
        ),
        checked = state.handoffBackup,
        enabled = options.originals,
        onChange = actions.onHandoffBackup,
        tag = SyncTags.HANDOFF_BACKUP,
    )
    NavigationRow(
        title = stringResource(R.string.sync_section_folders),
        subtitle = stringResource(R.string.backup_folders_subtitle),
        icon = PhosphorRegular.Folder,
        onClick = actions.onFolders,
        tag = BackupTags.FOLDERS,
    )
    RevealRowDivider()
    SwitchRow(
        title = stringResource(R.string.sync_previews),
        subtitle = stringResource(R.string.sync_previews_subtitle),
        checked = options.previews,
        onChange = { actions.onOptions(options.copy(previews = it)) },
        tag = SyncTags.PREVIEWS,
    )
    SwitchRow(
        title = stringResource(R.string.sync_edited_previews),
        subtitle = stringResource(R.string.sync_edited_previews_subtitle),
        checked = options.editedPreviews,
        onChange = { actions.onOptions(options.copy(editedPreviews = it)) },
        tag = SyncTags.EDITED_PREVIEWS,
    )

    SectionTitle(stringResource(R.string.sync_section_storage))
    StorageSummary(state.status)
}

/** Uploaded, waiting and server-only counts (the Sync screen's former "Storage" block). */
@Composable
private fun StorageSummary(status: SyncStatus) {
    val context = LocalContext.current
    Column(
        Modifier.padding(horizontal = 16.dp).testTag(BackupTags.STORAGE),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StorageLine(stringResource(R.string.sync_uploaded, size(context, status.uploadedBytes)))
        if (status.uploadsPending > 0) {
            StorageLine(
                pluralStringResource(
                    R.plurals.sync_waiting,
                    status.uploadsPending,
                    size(context, status.uploadBytesPending),
                    status.uploadsPending,
                ),
            )
        }
        StorageLine(pluralStringResource(R.plurals.sync_server_photos, status.serverPhotos, status.serverPhotos))
    }
}

@Composable
private fun StorageLine(text: String) {
    Text(text, style = RevealTheme.typography.body, color = RevealTheme.colors.foreground)
}
