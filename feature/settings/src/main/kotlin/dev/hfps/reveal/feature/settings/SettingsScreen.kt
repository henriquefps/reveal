package dev.hfps.reveal.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.appearance.AppearanceScreen
import dev.hfps.reveal.feature.settings.editing.EditingScreen
import dev.hfps.reveal.feature.settings.folders.FoldersAccessScreen
import dev.hfps.reveal.feature.settings.storage.StorageScreen
import dev.hfps.reveal.feature.settings.sync.BackupSyncScreen
import dev.hfps.reveal.feature.settings.sync.PhotoServerScreen
import dev.hfps.reveal.feature.settings.sync.ThisDeviceScreen
import dev.hfps.reveal.feature.settings.sync.statusTitle
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.SyncStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The entries of the Settings hub, top to bottom: each opens its own screen. A new setting goes into the screen of
 * the entry it belongs to; a new entry is one constant here plus its route, strings and destination.
 */
enum class SettingsEntry(val tag: String) {
    SERVER("settings_server"),
    BACKUP("settings_backup"),
    FOLDERS("settings_folders"),
    STORAGE("settings_storage"),
    APPEARANCE("settings_appearance"),
    EDITING("settings_editing"),
    ABOUT("settings_about"),
}

/** What the hub's rows say about the server (the only entry whose subtitle changes). */
@Immutable
data class SettingsHubState(val server: String? = null, val status: SyncStatus = SyncStatus())

@HiltViewModel
class SettingsViewModel @Inject constructor(sync: SyncRepository) : ViewModel() {
    val state: StateFlow<SettingsHubState> = combine(sync.account, sync.status) { account, status ->
        SettingsHubState(account?.let { it.serverName.ifBlank { it.url } }, status)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_MS),
        SettingsHubState(sync.account.value?.let { it.serverName.ifBlank { it.url } }),
    )

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/**
 * Adds Settings: the hub ([SettingsRoute]) and one destination per entry, plus This device under Photo server.
 * About lives in the app ([onAbout]). Back from any screen returns to the one below it.
 */
fun NavGraphBuilder.settingsScreens(navController: NavController, onAbout: () -> Unit) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<SettingsRoute> {
        SettingsScreen(
            onBack = back,
            onEntry = { entry ->
                when (entry) {
                    SettingsEntry.SERVER -> navController.navigate(PhotoServerRoute)
                    SettingsEntry.BACKUP -> navController.navigate(BackupSyncRoute)
                    SettingsEntry.FOLDERS -> navController.navigate(FoldersAccessRoute)
                    SettingsEntry.STORAGE -> navController.navigate(StorageRoute)
                    SettingsEntry.APPEARANCE -> navController.navigate(AppearanceRoute)
                    SettingsEntry.EDITING -> navController.navigate(EditingRoute)
                    SettingsEntry.ABOUT -> onAbout()
                }
            },
        )
    }
    composable<PhotoServerRoute> {
        PhotoServerScreen(onBack = back, onThisDevice = { navController.navigate(ThisDeviceRoute) })
    }
    composable<ThisDeviceRoute> { ThisDeviceScreen(onBack = back) }
    composable<BackupSyncRoute> {
        BackupSyncScreen(
            onBack = back,
            onServer = { navController.navigate(PhotoServerRoute) },
            onFolders = { navController.navigate(FoldersAccessRoute) },
        )
    }
    composable<FoldersAccessRoute> {
        FoldersAccessScreen(
            onBack = back,
            onServer = { navController.navigate(PhotoServerRoute) },
            onBackup = { navController.navigate(BackupSyncRoute) },
        )
    }
    composable<StorageRoute> { StorageScreen(onBack = back) }
    composable<AppearanceRoute> { AppearanceScreen(onBack = back) }
    composable<EditingRoute> { EditingScreen(onBack = back) }
}

/** Opens the Settings screen a door outside Settings names (the library's sync badge, a sheet's Pair...). */
fun NavController.navigateToSettings(link: SettingsLink) = navigate(link.route)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEntry: (SettingsEntry) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(state = state, onBack = onBack, onEntry = onEntry, modifier = modifier)
}

/** Stateless Settings hub (screenshots, tests). [now] formats the last sync (fixed in screenshots). */
@Composable
fun SettingsContent(
    state: SettingsHubState,
    onBack: () -> Unit,
    onEntry: (SettingsEntry) -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    RevealScaffold(
        modifier = modifier.testTag(SettingsTags.SCREEN),
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        SettingsBody {
            for (entry in SettingsEntry.entries) {
                val problem = entry == SettingsEntry.SERVER && state.server != null && state.status.problem != null
                NavigationRow(
                    title = stringResource(entry.title()),
                    subtitle = subtitle(entry, state, now),
                    icon = entry.icon(),
                    iconTint = if (problem) RevealTheme.colors.warning else RevealTheme.colors.mutedForeground,
                    onClick = { onEntry(entry) },
                    tag = entry.tag,
                )
            }
        }
    }
}

/** Test tags of the Settings hub (each entry's row is tagged [SettingsEntry.tag]). */
object SettingsTags {
    const val SCREEN = "settings_screen"
}

private fun SettingsEntry.title(): Int = when (this) {
    SettingsEntry.SERVER -> R.string.server_title
    SettingsEntry.BACKUP -> R.string.backup_title
    SettingsEntry.FOLDERS -> R.string.folders_title
    SettingsEntry.STORAGE -> R.string.storage_title
    SettingsEntry.APPEARANCE -> R.string.appearance_title
    SettingsEntry.EDITING -> R.string.editing_title
    SettingsEntry.ABOUT -> R.string.hub_about
}

private fun SettingsEntry.icon(): ImageVector = when (this) {
    SettingsEntry.SERVER -> PhosphorRegular.Lock
    SettingsEntry.BACKUP -> PhosphorRegular.Upload
    SettingsEntry.FOLDERS -> PhosphorRegular.Folder
    SettingsEntry.STORAGE -> PhosphorRegular.Database
    SettingsEntry.APPEARANCE -> PhosphorRegular.Palette
    SettingsEntry.EDITING -> PhosphorRegular.SlidersHorizontal
    SettingsEntry.ABOUT -> PhosphorRegular.ShieldCheck
}

@Composable
private fun subtitle(entry: SettingsEntry, state: SettingsHubState, now: Long): String = when (entry) {
    SettingsEntry.SERVER -> state.server?.let {
        stringResource(R.string.hub_server_on, it, statusTitle(state.status, now))
    }
        ?: stringResource(R.string.hub_server_off)

    SettingsEntry.BACKUP -> stringResource(R.string.hub_backup_subtitle)

    SettingsEntry.FOLDERS -> stringResource(R.string.hub_folders_subtitle)

    SettingsEntry.STORAGE -> stringResource(R.string.hub_storage_subtitle)

    SettingsEntry.APPEARANCE -> stringResource(R.string.hub_appearance_subtitle)

    SettingsEntry.EDITING -> stringResource(R.string.hub_editing_subtitle)

    SettingsEntry.ABOUT -> stringResource(R.string.hub_about_subtitle)
}
