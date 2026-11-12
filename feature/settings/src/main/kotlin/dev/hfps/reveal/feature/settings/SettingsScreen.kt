package dev.hfps.reveal.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.data.export.ExportPreferences
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.handoff.GalleryAccessState
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.storage.StorageScreen
import dev.hfps.reveal.feature.settings.sync.SyncSettingsScreen
import dev.hfps.reveal.sync.SyncRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings: sync (ADR 0018) and the author and copyright that exports write (docs/adr/0009, "Metadata"). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: ExportPreferences,
    sync: SyncRepository,
    private val gallery: GalleryAccess,
    private val library: LibraryRepository,
) : ViewModel() {
    val authorship: Authorship get() = preferences.authorship.value

    /** The optional gallery permission (ADR 0011 "Gallery access"). */
    val galleryAccess: StateFlow<GalleryAccessState> = gallery.state

    /** After the permission dialog: the offer is answered; which hand-offs read again. */
    fun onGalleryResult() {
        gallery.setAsked()
        gallery.refresh()
        viewModelScope.launch { library.checkHandoffs() }
    }

    /** Back from the app settings page (or anywhere): the permission may have changed. */
    fun onResumed() {
        if (gallery.refresh()) viewModelScope.launch { library.checkHandoffs() }
    }

    /** The paired server's name (or address), null when not paired. */
    val server: StateFlow<String?> = sync.account
        .map { account -> account?.let { it.serverName.ifBlank { it.url } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sync.account.value?.let { it.serverName.ifBlank { it.url } })

    fun setAuthorship(value: Authorship) = preferences.setAuthorship(value)
}

/** Adds the settings destinations (Settings, its Sync and Storage screens). */
fun NavGraphBuilder.settingsScreen(onBack: () -> Unit, onSync: () -> Unit, onStorage: () -> Unit = {}) {
    composable<SettingsRoute> { SettingsScreen(onBack = onBack, onSync = onSync, onStorage = onStorage) }
    composable<SyncSettingsRoute> { SyncSettingsScreen(onBack = onBack) }
    composable<StorageRoute> { StorageScreen(onBack = onBack) }
}

/** Test tags of the settings screen. */
object SettingsTags {
    const val AUTHOR = "settings_author"
    const val COPYRIGHT = "settings_copyright"
    const val SYNC = "settings_sync"
    const val GALLERY = "settings_gallery"
    const val STORAGE = "settings_storage"
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
    onStorage: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    val gallery by viewModel.galleryAccess.collectAsStateWithLifecycle()
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
    var author by rememberSaveable { mutableStateOf(viewModel.authorship.author) }
    var copyright by rememberSaveable { mutableStateOf(viewModel.authorship.copyright) }
    fun save() = viewModel.setAuthorship(Authorship(author, copyright))
    SettingsContent(
        author = author,
        copyright = copyright,
        onAuthor = {
            author = it
            save()
        },
        onCopyright = {
            copyright = it
            save()
        },
        onBack = onBack,
        modifier = modifier,
        server = server,
        onSync = onSync,
        onStorage = onStorage,
        gallery = gallery,
        onGallery = {
            // Turning it off (or widening "selected photos") happens in the system's page for the app.
            if (gallery == GalleryAccessState.FULL) {
                context.startActivity(appSettings(context))
            } else {
                permission.launch(GalleryAccess.permissions())
            }
        },
    )
}

private fun appSettings(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Stateless settings content (screenshots). */
@Composable
fun SettingsContent(
    author: String,
    copyright: String,
    onAuthor: (String) -> Unit,
    onCopyright: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    server: String? = null,
    onSync: () -> Unit = {},
    gallery: GalleryAccessState = GalleryAccessState.NONE,
    onGallery: () -> Unit = {},
    onStorage: () -> Unit = {},
) {
    RevealScaffold(
        modifier = modifier,
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow(stringResource(R.string.settings_section_sync), Modifier.padding(horizontal = 16.dp))
            RevealListRow(
                title = stringResource(R.string.settings_sync),
                subtitle = server?.let { stringResource(R.string.settings_sync_on, it) }
                    ?: stringResource(R.string.settings_sync_off),
                icon = if (server == null) PhosphorRegular.Cloud else PhosphorRegular.CloudCheck,
                onClick = onSync,
                modifier = Modifier.testTag(SettingsTags.SYNC),
                trailing = {
                    Icon(PhosphorRegular.CaretRight, null, tint = RevealTheme.colors.mutedForeground)
                },
            )
            Eyebrow(stringResource(R.string.settings_section_photos), Modifier.padding(horizontal = 16.dp))
            RevealListRow(
                title = stringResource(R.string.settings_gallery),
                subtitle = stringResource(
                    when (gallery) {
                        GalleryAccessState.FULL -> R.string.settings_gallery_full
                        GalleryAccessState.PARTIAL -> R.string.settings_gallery_partial
                        GalleryAccessState.NONE -> R.string.settings_gallery_off
                    },
                ),
                icon = PhosphorRegular.Image,
                onClick = onGallery,
                modifier = Modifier.testTag(SettingsTags.GALLERY),
                trailing = {
                    Icon(PhosphorRegular.CaretRight, null, tint = RevealTheme.colors.mutedForeground)
                },
            )
            RevealListRow(
                title = stringResource(R.string.storage_title),
                subtitle = stringResource(R.string.settings_storage_subtitle),
                icon = PhosphorRegular.Database,
                onClick = onStorage,
                modifier = Modifier.testTag(SettingsTags.STORAGE),
                trailing = {
                    Icon(PhosphorRegular.CaretRight, null, tint = RevealTheme.colors.mutedForeground)
                },
            )
            ExportSection(author, copyright, onAuthor, onCopyright)
        }
    }
}

@Composable
private fun ExportSection(
    author: String,
    copyright: String,
    onAuthor: (String) -> Unit,
    onCopyright: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(stringResource(R.string.settings_export))
        Field(stringResource(R.string.settings_author), author, onAuthor, SettingsTags.AUTHOR)
        Field(stringResource(R.string.settings_copyright), copyright, onCopyright, SettingsTags.COPYRIGHT)
        Text(
            stringResource(R.string.settings_authorship_note),
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    val c = RevealTheme.colors
    TextField(
        value = value,
        onValueChange = { onChange(it.take(MAX_LENGTH)) },
        singleLine = true,
        label = { Text(label, style = RevealTheme.typography.ui) },
        textStyle = RevealTheme.typography.body.copy(color = c.foreground),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = c.primary,
            unfocusedIndicatorColor = c.input,
            cursorColor = c.primary,
            focusedLabelColor = c.mutedForeground,
            unfocusedLabelColor = c.mutedForeground,
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

private const val MAX_LENGTH = 120
