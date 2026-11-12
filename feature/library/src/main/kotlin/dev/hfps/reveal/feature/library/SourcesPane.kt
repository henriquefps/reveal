package dev.hfps.reveal.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import java.text.NumberFormat

/** The sources list: the library entries, the added folders (volume, count, state) and "Add folder". */
@Composable
fun SourcesPane(
    sources: List<SourceItem>,
    selection: LibrarySelection?,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    serverPhotos: Int = 0,
) {
    if (sources.isEmpty() && serverPhotos == 0) {
        EmptyLibrary(actions, modifier)
        return
    }
    var confirmRemove by remember { mutableStateOf<SourceItem?>(null) }
    LazyColumn(modifier.fillMaxSize().testTag(LibraryTags.SOURCES), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SectionLabel(stringResource(R.string.library_section_library)) }
        val specials = SpecialSource.entries.filter { it != SpecialSource.SERVER || serverPhotos > 0 }
        items(specials, key = { it.name }) { which -> SpecialRow(which, selection, actions) }
        item { SectionLabel(stringResource(R.string.library_section_folders)) }
        items(sources, key = { it.id }) { source ->
            SourceRow(
                source = source,
                selected = selection == LibrarySelection.Folder(source.id),
                onClick = { actions.onSelect(LibrarySelection.Folder(source.id)) },
                onRescan = { actions.onRescan(source.id) },
                onRemove = { confirmRemove = source },
            )
        }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.library_add_folder),
                    onClick = actions.onAddFolder,
                    icon = PhosphorRegular.FolderPlus,
                    modifier = Modifier.testTag(LibraryTags.ADD_FOLDER),
                )
            }
        }
    }
    confirmRemove?.let { source ->
        RemoveFolderDialog(
            name = source.name,
            onConfirm = {
                confirmRemove = null
                actions.onRemoveSource(source.id)
            },
            onDismiss = { confirmRemove = null },
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Eyebrow(text, modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SpecialRow(
    which: SpecialSource,
    selection: LibrarySelection?,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val (icon, title, subtitle) = when (which) {
        SpecialSource.ALL -> Triple(PhosphorRegular.Images, R.string.library_all_photos, null)

        SpecialSource.RECENT -> Triple(
            PhosphorRegular.ClockCounterClockwise,
            R.string.library_recent,
            R.string.library_recent_subtitle,
        )

        SpecialSource.FROM_APPS -> Triple(
            PhosphorRegular.ArrowSquareIn,
            R.string.library_from_apps,
            R.string.library_from_apps_subtitle,
        )

        SpecialSource.SERVER -> Triple(
            PhosphorRegular.HardDrives,
            R.string.library_server,
            R.string.library_server_subtitle,
        )
    }
    RevealListRow(
        title = stringResource(title),
        subtitle = subtitle?.let { stringResource(it) },
        icon = icon,
        onClick = { actions.onSelect(LibrarySelection.Special(which)) },
        modifier = modifier.selectedRow(selection == LibrarySelection.Special(which)),
    )
}

@Composable
private fun Modifier.selectedRow(selected: Boolean): Modifier =
    if (selected) background(RevealTheme.colors.accent) else this

@Composable
private fun SourceRow(
    source: SourceItem,
    selected: Boolean,
    onClick: () -> Unit,
    onRescan: () -> Unit,
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
            modifier = Modifier.selectedRow(selected),
            trailing = {
                Row {
                    if (source.indexing != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(12.dp).size(20.dp),
                            color = RevealTheme.colors.primary,
                            strokeWidth = 2.dp,
                        )
                    }
                    SourceMenu(source.name, onRescan, onRemove)
                }
            },
        )
    }
}

@Composable
private fun Row(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun SourceMenu(name: String, onRescan: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RevealIconButton(
            icon = PhosphorRegular.DotsThree,
            contentDescription = stringResource(R.string.library_source_actions, name),
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = RevealTheme.colors.card,
            shape = RevealTheme.shapes.control,
        ) {
            MenuItem(PhosphorRegular.ArrowsClockwise, stringResource(R.string.library_source_rescan)) {
                open = false
                onRescan()
            }
            MenuItem(PhosphorRegular.Trash, stringResource(R.string.library_source_remove)) {
                open = false
                onRemove()
            }
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

@Composable
private fun RemoveFolderDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RevealTheme.colors.card,
        shape = RevealTheme.shapes.card,
        title = {
            Text(stringResource(R.string.library_remove_folder_title, name), style = RevealTheme.typography.title)
        },
        text = {
            Text(
                stringResource(R.string.library_remove_folder_body),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
            )
        },
        confirmButton = { PrimaryButton(stringResource(R.string.library_remove_folder_confirm), onConfirm) },
        dismissButton = { GhostButton(stringResource(R.string.library_cancel), onDismiss) },
    )
}

/** First launch: nothing added yet. One clear action, plus opening a single photo. */
@Composable
private fun EmptyLibrary(actions: LibraryActions, modifier: Modifier = Modifier) {
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
