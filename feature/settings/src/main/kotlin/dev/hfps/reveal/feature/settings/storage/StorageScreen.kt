package dev.hfps.reveal.feature.settings.storage

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.data.storage.AppStorage
import dev.hfps.reveal.data.storage.CacheBudget
import dev.hfps.reveal.data.storage.StorageKind
import dev.hfps.reveal.data.storage.StorageUsage
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealRowDivider
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.R
import dev.hfps.reveal.sync.files.ServerFiles
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Storage screen shows: the usage (null while measuring), the cache cap and the last clear. */
@Immutable
data class StorageUiState(
    val usage: StorageUsage? = null,
    val maxBytes: Long = CacheBudget.DEFAULT_MAX_BYTES,
    val clearing: Boolean = false,
    /** Bytes the last "Clear cache" freed; null before any. */
    val freed: Long? = null,
)

/** Test tags of the Storage screen. */
object StorageTags {
    const val SCREEN = "storage_screen"
    const val CLEAR = "storage_clear"
    const val CLEAR_CONFIRM = "storage_clear_confirm"
    const val FREED = "storage_freed"

    fun kind(kind: StorageKind) = "storage_kind_${kind.name.lowercase()}"

    fun max(bytes: Long) = "storage_max_$bytes"
}

/** Settings -> Storage (ADR 0018 amendment FIX/W): what Reveal stores, "Clear cache" and the maximum cache size. */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val storage: AppStorage,
    private val budget: CacheBudget,
    // Made here so its caches are registered with the budget before they are measured or trimmed.
    @Suppress("unused") private val serverFiles: ServerFiles,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState(maxBytes = budget.maxBytes.value))
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val usage = storage.usage()
            _state.update { it.copy(usage = usage) }
        }
    }

    fun setMaxBytes(bytes: Long) {
        budget.setMaxBytes(bytes)
        _state.update { it.copy(maxBytes = bytes) }
        viewModelScope.launch {
            budget.trim()
            refresh()
        }
    }

    fun clearCache() {
        _state.update { it.copy(clearing = true) }
        viewModelScope.launch {
            val freed = storage.clearCache()
            _state.update { it.copy(clearing = false, freed = freed, usage = storage.usage()) }
        }
    }
}

@Composable
fun StorageScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: StorageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    StorageContent(
        state = state,
        onBack = onBack,
        onMaxBytes = viewModel::setMaxBytes,
        onClear = viewModel::clearCache,
        modifier = modifier,
    )
}

/** Stateless Storage screen (screenshots, tests). */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod")
@Composable
fun StorageContent(
    state: StorageUiState,
    onBack: () -> Unit,
    onMaxBytes: (Long) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = RevealTheme.colors
    var confirm by rememberSaveable { mutableStateOf(false) }
    RevealScaffold(
        modifier = modifier.testTag(StorageTags.SCREEN),
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.storage_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val usage = state.usage
            Text(
                if (usage == null) {
                    stringResource(R.string.storage_measuring)
                } else {
                    stringResource(R.string.storage_total, size(context, usage.total))
                },
                style = RevealTheme.typography.title,
                color = colors.foreground,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.storage_photos_note),
                style = RevealTheme.typography.body,
                color = colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Eyebrow(
                stringResource(R.string.storage_section_used),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            for (kind in KINDS) {
                RevealListRow(
                    title = stringResource(kind.title()),
                    subtitle = subtitle(kind, usage, context),
                    icon = kind.icon(),
                    modifier = Modifier.testTag(StorageTags.kind(kind)),
                    trailing = {
                        Text(
                            usage?.let { size(context, it[kind]) } ?: "",
                            style = RevealTheme.typography.mono,
                            color = colors.foreground,
                        )
                    },
                )
            }
            RevealRowDivider()

            Eyebrow(
                stringResource(R.string.storage_section_max),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (choice in CacheBudget.CHOICES) {
                    RevealChip(
                        label = size(context, choice),
                        selected = choice == state.maxBytes,
                        onClick = { onMaxBytes(choice) },
                        modifier = Modifier.testTag(StorageTags.max(choice)),
                    )
                }
            }
            Text(
                stringResource(R.string.storage_max_note),
                style = RevealTheme.typography.ui,
                color = colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Eyebrow(
                stringResource(R.string.storage_section_clear),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            Text(
                stringResource(R.string.storage_clear_note),
                style = RevealTheme.typography.ui,
                color = colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.storage_clear),
                    onClick = { confirm = true },
                    icon = PhosphorRegular.Trash,
                    enabled = !state.clearing,
                    modifier = Modifier.testTag(StorageTags.CLEAR),
                )
                state.freed?.let { freed ->
                    Text(
                        stringResource(R.string.storage_freed, size(context, freed)),
                        style = RevealTheme.typography.ui,
                        color = colors.mutedForeground,
                        modifier = Modifier.testTag(StorageTags.FREED),
                    )
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = colors.card,
            shape = RevealTheme.shapes.card,
            title = { Text(stringResource(R.string.storage_clear_title), style = RevealTheme.typography.title) },
            text = {
                Text(
                    stringResource(R.string.storage_clear_body),
                    style = RevealTheme.typography.body,
                    color = colors.mutedForeground,
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = stringResource(R.string.storage_clear),
                    onClick = {
                        confirm = false
                        onClear()
                    },
                    modifier = Modifier.testTag(StorageTags.CLEAR_CONFIRM),
                )
            },
            dismissButton = { GhostButton(stringResource(R.string.storage_cancel), { confirm = false }) },
        )
    }
}

private val KINDS = listOf(
    StorageKind.THUMBNAILS,
    StorageKind.SERVER_FILES,
    StorageKind.MASKS,
    StorageKind.HANDOFF_PREVIEWS,
    StorageKind.OTHER,
    StorageKind.LIBRARY,
)

private fun StorageKind.title(): Int = when (this) {
    StorageKind.THUMBNAILS -> R.string.storage_thumbnails
    StorageKind.SERVER_FILES -> R.string.storage_server_files
    StorageKind.MASKS -> R.string.storage_masks
    StorageKind.HANDOFF_PREVIEWS -> R.string.storage_handoff_previews
    StorageKind.OTHER -> R.string.storage_other
    StorageKind.LIBRARY -> R.string.storage_library
}

private fun StorageKind.icon() = when (this) {
    StorageKind.THUMBNAILS -> PhosphorRegular.SquaresFour
    StorageKind.SERVER_FILES -> PhosphorRegular.Cloud
    StorageKind.MASKS -> PhosphorRegular.Sparkle
    StorageKind.HANDOFF_PREVIEWS -> PhosphorRegular.ArrowSquareIn
    StorageKind.OTHER -> PhosphorRegular.Package
    StorageKind.LIBRARY -> PhosphorRegular.Database
}

@Composable
private fun subtitle(kind: StorageKind, usage: StorageUsage?, context: Context): String = when (kind) {
    StorageKind.THUMBNAILS -> stringResource(R.string.storage_thumbnails_note)

    StorageKind.SERVER_FILES -> stringResource(R.string.storage_server_files_note)

    StorageKind.MASKS -> stringResource(R.string.storage_masks_note)

    StorageKind.HANDOFF_PREVIEWS -> if (usage != null && usage.keptPreviews > 0) {
        stringResource(R.string.storage_handoff_previews_kept, size(context, usage.keptPreviews))
    } else {
        stringResource(R.string.storage_handoff_previews_note)
    }

    StorageKind.OTHER -> stringResource(R.string.storage_other_note)

    StorageKind.LIBRARY -> stringResource(R.string.storage_library_note)
}

private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
