package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.edit.SnapshotInfo
import dev.hfps.reveal.data.edit.VersionRepository
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealTextDialog
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import java.text.DateFormat
import java.util.Date

/**
 * Snapshots (docs/adr/0025): named saved states of this version's edit, newest first, above the history list. A tap
 * applies one as a history step (undo goes back); each has a menu to rename or delete it; "Save snapshot" keeps the
 * current edit under a name.
 */
@Suppress("LongMethod")
@Composable
fun SnapshotsSection(host: PanelHost, modifier: Modifier = Modifier) {
    val versions = host.state.versions
    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SnapshotInfo?>(null) }
    val enabled = host.state.ready
    Column(modifier.testTag(EditorTags.SNAPSHOTS)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(stringResource(R.string.editor_snapshots_title), Modifier.weight(1f))
            GhostButton(
                text = stringResource(R.string.editor_snapshot_save),
                onClick = { saving = true },
                icon = PhosphorRegular.Camera,
                enabled = enabled,
                modifier = Modifier.testTag(EditorTags.SNAPSHOT_SAVE),
            )
        }
        if (versions.snapshots.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_snapshots_empty),
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        for (snapshot in versions.snapshots) {
            SnapshotRow(
                snapshot = snapshot,
                enabled = enabled,
                onApply = { host.actions.onApplySnapshot(snapshot.id) },
                onRename = { renaming = snapshot },
                onDelete = { host.actions.onDeleteSnapshot(snapshot.id) },
            )
        }
    }
    if (saving) {
        SnapshotNameDialog(
            snapshots = versions.snapshots.size,
            onSave = {
                saving = false
                host.actions.onSaveSnapshot(it)
            },
            onDismiss = { saving = false },
        )
    }
    renaming?.let { snapshot ->
        RevealTextDialog(
            title = stringResource(R.string.editor_snapshot_rename_title),
            label = stringResource(R.string.editor_snapshot_name),
            initial = snapshot.name,
            confirm = stringResource(R.string.editor_snapshot_rename),
            cancel = stringResource(R.string.editor_snapshot_cancel),
            onConfirm = {
                renaming = null
                host.actions.onRenameSnapshot(snapshot.id, it)
            },
            onDismiss = { renaming = null },
            maxLength = VersionRepository.MAX_NAME,
        )
    }
}

/** Asks for a new snapshot's name, starting at "Snapshot N" (N = how many this version has, plus one). */
@Composable
fun SnapshotNameDialog(snapshots: Int, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    RevealTextDialog(
        title = stringResource(R.string.editor_snapshot_save_title),
        label = stringResource(R.string.editor_snapshot_name),
        initial = stringResource(R.string.editor_snapshot_default_name, snapshots + 1),
        confirm = stringResource(R.string.editor_snapshot_save_confirm),
        cancel = stringResource(R.string.editor_snapshot_cancel),
        onConfirm = onSave,
        onDismiss = onDismiss,
        maxLength = VersionRepository.MAX_NAME,
    )
}

@Suppress("LongMethod")
@Composable
private fun SnapshotRow(
    snapshot: SnapshotInfo,
    enabled: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = RevealTheme.colors
    var menu by remember { mutableStateOf(false) }
    val date = remember(snapshot.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(snapshot.createdAt))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RevealTheme.shapes.control)
            .background(colors.card)
            .clickable(enabled = enabled, role = Role.Button, onClick = onApply)
            .padding(start = 8.dp)
            .testTag(EditorTags.snapshot(snapshot.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = PhosphorRegular.Camera,
                contentDescription = null,
                tint = colors.mutedForeground,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(
                text = snapshot.name,
                style = RevealTheme.typography.ui,
                color = colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = date, style = RevealTheme.typography.ui, color = colors.mutedForeground, maxLines = 1)
        }
        Box {
            RevealIconButton(
                icon = PhosphorRegular.DotsThree,
                contentDescription = stringResource(R.string.editor_snapshot_menu, snapshot.name),
                onClick = { menu = true },
                enabled = enabled,
                modifier = Modifier.testTag(EditorTags.snapshotMenu(snapshot.id)),
            )
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = colors.card,
                shape = RevealTheme.shapes.control,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_snapshot_rename), style = RevealTheme.typography.ui) },
                    onClick = {
                        menu = false
                        onRename()
                    },
                    leadingIcon = { Icon(PhosphorRegular.PencilSimple, null, Modifier.size(20.dp)) },
                    modifier = Modifier.testTag(EditorTags.SNAPSHOT_RENAME),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_snapshot_delete), style = RevealTheme.typography.ui) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                    leadingIcon = { Icon(PhosphorRegular.Trash, null, Modifier.size(20.dp)) },
                    modifier = Modifier.testTag(EditorTags.SNAPSHOT_DELETE),
                )
            }
        }
    }
}
