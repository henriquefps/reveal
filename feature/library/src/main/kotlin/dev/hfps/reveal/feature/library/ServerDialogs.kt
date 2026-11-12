package dev.hfps.reveal.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealCheckRow
import dev.hfps.reveal.designsystem.components.RevealChoiceRow
import dev.hfps.reveal.designsystem.theme.RevealTheme

/** Test tags of the server's view dialogs (FIX/V): instrumented tests find them in any language. */
object ServerViewTags {
    const val BACKUP_SHEET = "add-folder-backup"
    const val BACKUP_EDITED = "add-folder-backup-edited"
    const val BACKUP_EVERYTHING = "add-folder-backup-everything"
    const val BACKUP_CONFIRM = "add-folder-backup-confirm"
    const val REMOVE_DIALOG = "remove-folder-dialog"
    const val REMOVE_ALSO_SERVER = "remove-folder-also-server"
    const val REMOVE_CONFIRM = "remove-folder-confirm"
    const val DELETE_FROM_SERVER = "library-delete-from-server"
    const val DELETE_DIALOG = "delete-from-server-dialog"
    const val DELETE_CONFIRM = "delete-from-server-confirm"
}

/**
 * Adding a folder while paired with a sync server asks once what it backs up (ADR 0018 "Originals backup", FIX/V):
 * "Only photos you edit" (preselected) or "Everything". The per-folder setting can be changed later in Settings → Sync.
 * Dismissing it adds nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddFolderBackupSheet(onChoose: (everything: Boolean) -> Unit, onDismiss: () -> Unit) {
    RevealBottomSheet(onDismissRequest = onDismiss) {
        AddFolderBackupContent(onChoose, onDismiss)
    }
}

@Composable
fun AddFolderBackupContent(
    onChoose: (everything: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var everything by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag(ServerViewTags.BACKUP_SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.library_backup_title),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
        )
        Text(
            stringResource(R.string.library_backup_body),
            style = RevealTheme.typography.body,
            color = RevealTheme.colors.mutedForeground,
        )
        Column(Modifier.selectableGroup()) {
            RevealChoiceRow(
                label = stringResource(R.string.library_backup_edited),
                detail = stringResource(R.string.library_backup_edited_detail),
                selected = !everything,
                onSelect = { everything = false },
                modifier = Modifier.testTag(ServerViewTags.BACKUP_EDITED),
            )
            RevealChoiceRow(
                label = stringResource(R.string.library_backup_everything),
                detail = stringResource(R.string.library_backup_everything_detail),
                selected = everything,
                onSelect = { everything = true },
                modifier = Modifier.testTag(ServerViewTags.BACKUP_EVERYTHING),
            )
        }
        PrimaryButton(
            text = stringResource(R.string.library_add_folder),
            onClick = { onChoose(everything) },
            modifier = Modifier.fillMaxWidth().testTag(ServerViewTags.BACKUP_CONFIRM),
        )
        GhostButton(
            text = stringResource(R.string.library_cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
    }
}

/**
 * Removing a folder. While paired it also asks "Also delete from the server?" (ADR 0018 "Deleting from the server"):
 * unchecked when the folder has edited photos (their edits would go everywhere), checked otherwise.
 */
@Composable
internal fun RemoveFolderDialog(prompt: RemovePrompt, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var alsoServer by rememberSaveable(prompt.sourceId) { mutableStateOf(prompt.edited == 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RevealTheme.colors.card,
        shape = RevealTheme.shapes.card,
        modifier = Modifier.testTag(ServerViewTags.REMOVE_DIALOG),
        title = {
            Text(
                stringResource(R.string.library_remove_folder_title, prompt.name),
                style = RevealTheme.typography.title,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.library_remove_folder_body),
                    style = RevealTheme.typography.body,
                    color = RevealTheme.colors.mutedForeground,
                )
                if (prompt.server) {
                    RevealCheckRow(
                        label = stringResource(R.string.library_remove_folder_server),
                        detail = stringResource(
                            if (alsoServer) {
                                R.string.library_remove_folder_server_on
                            } else {
                                R.string.library_remove_folder_server_off
                            },
                        ),
                        checked = alsoServer,
                        onCheckedChange = { alsoServer = it },
                        modifier = Modifier.testTag(ServerViewTags.REMOVE_ALSO_SERVER),
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                stringResource(R.string.library_remove_folder_confirm),
                { onConfirm(prompt.server && alsoServer) },
                modifier = Modifier.testTag(ServerViewTags.REMOVE_CONFIRM),
            )
        },
        dismissButton = { GhostButton(stringResource(R.string.library_cancel), onDismiss) },
    )
}

/** "Delete from server" for [count] Server photos: says that their edits go too, on every device. */
@Composable
internal fun DeleteFromServerDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RevealTheme.colors.card,
        shape = RevealTheme.shapes.card,
        modifier = Modifier.testTag(ServerViewTags.DELETE_DIALOG),
        title = {
            Text(
                pluralStringResource(R.plurals.library_delete_server_title, count, count),
                style = RevealTheme.typography.title,
            )
        },
        text = {
            Text(
                stringResource(R.string.library_delete_server_body),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
            )
        },
        confirmButton = {
            PrimaryButton(
                stringResource(R.string.library_delete_server_confirm),
                onConfirm,
                modifier = Modifier.testTag(ServerViewTags.DELETE_CONFIRM),
            )
        },
        dismissButton = { GhostButton(stringResource(R.string.library_cancel), onDismiss) },
    )
}
