package dev.hfps.reveal.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import java.text.NumberFormat

/** "Manage folders" (FIX/L): a sheet on phones, a centred dialog on wide windows (`RevealBottomSheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManageFoldersSheet(state: LibraryUiState, actions: LibraryActions) {
    RevealBottomSheet(onDismissRequest = { actions.onFoldersOpen(false) }) {
        ManageFoldersContent(state.sources, state.paired, actions)
    }
}

/**
 * The added folders (volume, count, state, indexing) and "Add folder". A tap shows the folder's photos in the home
 * grid (filtered to it); its menu rescans, sets its backup mode while paired, and removes it (with FIX/V's question
 * about the server).
 */
@Composable
fun ManageFoldersContent(
    sources: List<SourceItem>,
    paired: Boolean,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(LibraryTags.FOLDERS_SHEET)) {
        Text(
            stringResource(R.string.library_manage_folders),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        if (sources.isEmpty()) {
            Text(
                stringResource(R.string.library_manage_folders_empty),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f, fill = false).testTag(LibraryTags.SOURCES),
            contentPadding = PaddingValues(top = 8.dp),
        ) {
            items(sources, key = { it.id }) { source ->
                SourceRow(
                    source = source,
                    paired = paired,
                    onClick = { actions.onShowFolder(source.id) },
                    onRescan = { actions.onRescan(source.id) },
                    onBackup = { everything -> actions.onFolderBackup(source.id, everything) },
                    onRemove = { actions.onRemoveSource(source.id) },
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            SecondaryButton(
                text = stringResource(R.string.library_add_folder),
                onClick = actions.onAddFolder,
                icon = PhosphorRegular.FolderPlus,
                modifier = Modifier.testTag(LibraryTags.ADD_FOLDER),
            )
        }
    }
}

@Composable
private fun SourceRow(
    source: SourceItem,
    paired: Boolean,
    onClick: () -> Unit,
    onRescan: () -> Unit,
    onBackup: (everything: Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = NumberFormat.getIntegerInstance().format(source.photoCount)
    val photos = pluralStringResource(R.plurals.library_photo_count, source.photoCount, count)
    val subtitle = when (source.state) {
        SourceState.UNPLUGGED -> listOfNotNull(source.volumeName, stringResource(R.string.library_source_unplugged))
            .joinToString(" · ")

        SourceState.PERMISSION_LOST -> stringResource(R.string.library_source_permission_lost)

        SourceState.AVAILABLE -> when {
            source.indexing != null -> stringResource(R.string.library_source_indexing)
            source.volumeName != null -> stringResource(R.string.library_source_subtitle, source.volumeName!!, photos)
            else -> photos
        }
    }
    val icon = when {
        source.state != SourceState.AVAILABLE -> PhosphorRegular.Warning
        source.removableVolume -> PhosphorRegular.SimCard
        else -> PhosphorRegular.Folder
    }
    Box(modifier.testTag(LibraryTags.source(source.id))) {
        RevealListRow(
            title = source.name,
            subtitle = subtitle,
            icon = icon,
            // The state is in the subtitle text too; the warning tint marks the row at a glance.
            iconTint = if (source.state ==
                SourceState.AVAILABLE
            ) {
                RevealTheme.colors.mutedForeground
            } else {
                RevealTheme.colors.warning
            },
            onClick = onClick,
            trailing = {
                Row {
                    if (source.indexing != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(12.dp).size(20.dp),
                            color = RevealTheme.colors.primary,
                            strokeWidth = 2.dp,
                        )
                    }
                    SourceMenu(source, paired, onClick, onRescan, onBackup, onRemove)
                }
            },
        )
    }
}

@Composable
private fun Row(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) { content() }
}

@Suppress("LongParameterList")
@Composable
private fun SourceMenu(
    source: SourceItem,
    paired: Boolean,
    onShow: () -> Unit,
    onRescan: () -> Unit,
    onBackup: (everything: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RevealIconButton(
            icon = PhosphorRegular.DotsThree,
            contentDescription = stringResource(R.string.library_source_actions, source.name),
            onClick = { open = true },
            modifier = Modifier.testTag(LibraryTags.folderMenu(source.id)),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = RevealTheme.colors.card,
            shape = RevealTheme.shapes.control,
        ) {
            Box(Modifier.testTag(LibraryTags.FOLDER_SHOW)) {
                MenuItem(PhosphorRegular.Images, stringResource(R.string.library_folder_show)) {
                    open = false
                    onShow()
                }
            }
            MenuItem(PhosphorRegular.ArrowsClockwise, stringResource(R.string.library_source_rescan)) {
                open = false
                onRescan()
            }
            if (paired) {
                BackupItems(source.backup) { everything ->
                    open = false
                    onBackup(everything)
                }
            }
            Box(Modifier.testTag(LibraryTags.FOLDER_REMOVE)) {
                MenuItem(PhosphorRegular.Trash, stringResource(R.string.library_source_remove)) {
                    open = false
                    onRemove()
                }
            }
        }
    }
}

/** The folder's backup mode (FIX/V), the current one ticked. */
@Composable
private fun ColumnScope.BackupItems(everything: Boolean, onChoose: (everything: Boolean) -> Unit) {
    MenuItem(PhosphorRegular.CloudArrowUp, stringResource(R.string.library_folder_backup), enabled = false) {}
    Box(Modifier.testTag(LibraryTags.FOLDER_BACKUP_EDITED)) {
        MenuItem(if (!everything) PhosphorRegular.Check else null, stringResource(R.string.library_backup_edited)) {
            onChoose(false)
        }
    }
    Box(Modifier.testTag(LibraryTags.FOLDER_BACKUP_EVERYTHING)) {
        MenuItem(if (everything) PhosphorRegular.Check else null, stringResource(R.string.library_backup_everything)) {
            onChoose(true)
        }
    }
}

@Composable
internal fun MenuItem(icon: ImageVector?, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = RevealTheme.typography.ui) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        enabled = enabled,
        onClick = onClick,
    )
}

/** First launch: nothing added yet. One clear action, plus opening a single photo. */
@Composable
internal fun EmptyLibrary(actions: LibraryActions, modifier: Modifier = Modifier) {
    EmptyState(
        icon = PhosphorRegular.FolderOpen,
        title = stringResource(R.string.library_empty_title),
        body = stringResource(R.string.library_empty_body),
        modifier = modifier,
    ) {
        PrimaryButton(
            text = stringResource(R.string.library_add_folder),
            onClick = actions.onAddFolder,
            icon = PhosphorRegular.FolderPlus,
            modifier = Modifier.testTag(LibraryTags.ADD_FOLDER),
        )
        GhostButton(
            text = stringResource(R.string.library_empty_open),
            onClick = actions.onOpenPhoto,
            icon = PhosphorRegular.Image,
        )
    }
}

/** A centred empty state: icon, title, body and its actions. Scrolls at large font scales. */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Box(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(LibraryTags.EMPTY),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = RevealTheme.colors.primary, modifier = Modifier.size(48.dp))
            Text(
                title,
                style = RevealTheme.typography.title,
                color = RevealTheme.colors.foreground,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            actions()
        }
    }
}
