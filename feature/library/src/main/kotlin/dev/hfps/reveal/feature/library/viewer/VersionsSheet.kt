package dev.hfps.reveal.feature.library.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.edit.CopySource
import dev.hfps.reveal.data.edit.VersionInfo
import dev.hfps.reveal.data.edit.VersionRepository
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealRowDivider
import dev.hfps.reveal.designsystem.components.RevealTextDialog
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.R

/** Test tags of the versions sheet (locale-independent instrumented tests). */
object VersionsTags {
    const val SHEET = "versions_sheet"
    const val COPY_CURRENT = "versions_copy_current"
    const val COPY_ORIGINAL = "versions_copy_original"

    fun row(versionId: Long) = "versions_row_$versionId"

    fun rename(versionId: Long) = "versions_rename_$versionId"

    fun delete(versionId: Long) = "versions_delete_$versionId"

    const val DELETE_CONFIRM = "versions_delete_confirm"
}

/**
 * Asks before deleting [count] virtual copies ([name] when there is one): their edit, history and snapshots go too and
 * cannot be undone.
 */
@Composable
fun DeleteCopiesDialog(count: Int, name: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RevealTheme.colors.card,
        shape = RevealTheme.shapes.card,
        title = {
            Text(
                if (name != null && count == 1) {
                    stringResource(R.string.versions_delete_question_named, name)
                } else {
                    pluralStringResource(R.plurals.versions_delete_question, count, count)
                },
                style = RevealTheme.typography.title,
                color = RevealTheme.colors.foreground,
            )
        },
        text = {
            Text(
                stringResource(R.string.versions_delete_body),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
            )
        },
        confirmButton = {
            PrimaryButton(
                stringResource(R.string.versions_delete),
                onConfirm,
                modifier = Modifier.testTag(VersionsTags.DELETE_CONFIRM),
            )
        },
        dismissButton = { GhostButton(stringResource(R.string.library_cancel), onDismiss) },
    )
}

/** What the versions sheet shows: the photo's versions, which one is on screen, and the photo's name. */
@Immutable
data class VersionsUi(val photoName: String, val versions: List<VersionInfo>, val currentVersionId: Long)

/** Everything the versions sheet can ask for. */
class VersionsActions(
    val onShow: (VersionInfo) -> Unit = {},
    val onCreate: (CopySource) -> Unit = {},
    val onRename: (VersionInfo, String) -> Unit = { _, _ -> },
    val onDelete: (VersionInfo) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** The name a version shows: a copy's own name, "Main version" for the photo's first one. */
@Composable
fun VersionInfo.label(): String = when {
    conflict -> stringResource(R.string.library_badge_conflict)

    else ->
        name
            ?: if (position ==
                0
            ) {
                stringResource(R.string.versions_main)
            } else {
                stringResource(R.string.library_badge_copy)
            }
}

/** The viewer's versions sheet (docs/adr/0025): a bottom sheet on phones, a dialog from 840 dp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsSheet(ui: VersionsUi, actions: VersionsActions, modifier: Modifier = Modifier) {
    RevealBottomSheet(onDismissRequest = actions.onDismiss, modifier = modifier) {
        VersionsContent(ui, actions, Modifier.navigationBarsPadding())
    }
}

/**
 * The photo's versions: the first one, then its virtual copies (rename, delete) and any sync conflict copies; a tap
 * shows that version. "Copy this edit" makes a virtual copy of the version on screen, "Copy of the original" one
 * without an edit.
 */
@Suppress("LongMethod")
@Composable
fun VersionsContent(ui: VersionsUi, actions: VersionsActions, modifier: Modifier = Modifier) {
    var renaming by remember { mutableStateOf<VersionInfo?>(null) }
    var deleting by remember { mutableStateOf<VersionInfo?>(null) }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
            .testTag(VersionsTags.SHEET),
    ) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow(stringResource(R.string.versions_title))
            Text(ui.photoName, style = RevealTheme.typography.title, color = RevealTheme.colors.foreground)
            Text(
                stringResource(R.string.versions_hint),
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.mutedForeground,
            )
        }
        for ((index, version) in ui.versions.withIndex()) {
            if (index > 0) RevealRowDivider(Modifier.padding(start = 54.dp))
            val current = version.id == ui.currentVersionId
            RevealListRow(
                title = version.label(),
                subtitle = if (current) stringResource(R.string.versions_on_screen) else null,
                icon = if (current) PhosphorFill.CheckCircle else PhosphorRegular.CopySimple,
                iconTint = if (current) RevealTheme.colors.primary else RevealTheme.colors.mutedForeground,
                onClick = { actions.onShow(version) },
                modifier = Modifier.testTag(VersionsTags.row(version.id)),
                trailing = if (version.isCopy) {
                    {
                        RevealIconButton(
                            icon = PhosphorRegular.PencilSimple,
                            contentDescription = stringResource(R.string.versions_rename),
                            onClick = { renaming = version },
                            modifier = Modifier.testTag(VersionsTags.rename(version.id)),
                        )
                        RevealIconButton(
                            icon = PhosphorRegular.Trash,
                            contentDescription = stringResource(R.string.versions_delete),
                            onClick = { deleting = version },
                            modifier = Modifier.testTag(VersionsTags.delete(version.id)),
                        )
                    }
                } else {
                    null
                },
            )
        }
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = stringResource(R.string.versions_copy_current),
                onClick = { actions.onCreate(CopySource.CURRENT) },
                icon = PhosphorRegular.CopySimple,
                modifier = Modifier.fillMaxWidth().testTag(VersionsTags.COPY_CURRENT),
            )
            SecondaryButton(
                text = stringResource(R.string.versions_copy_original),
                onClick = { actions.onCreate(CopySource.ORIGINAL) },
                modifier = Modifier.fillMaxWidth().testTag(VersionsTags.COPY_ORIGINAL),
            )
        }
    }
    deleting?.let { version ->
        DeleteCopiesDialog(
            count = 1,
            name = version.name,
            onConfirm = {
                deleting = null
                actions.onDelete(version)
            },
            onDismiss = { deleting = null },
        )
    }
    renaming?.let { version ->
        RevealTextDialog(
            title = stringResource(R.string.versions_rename_title),
            label = stringResource(R.string.versions_name),
            initial = version.name.orEmpty(),
            confirm = stringResource(R.string.versions_rename),
            cancel = stringResource(R.string.library_cancel),
            onConfirm = {
                renaming = null
                actions.onRename(version, it)
            },
            onDismiss = { renaming = null },
            maxLength = VersionRepository.MAX_NAME,
        )
    }
}
