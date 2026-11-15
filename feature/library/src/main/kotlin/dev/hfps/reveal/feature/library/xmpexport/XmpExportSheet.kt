@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hfps.reveal.feature.library.xmpexport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.library.XmpExportScope
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.R

/**
 * "Export XMP" (ADR 0026 "Export XMP"): where the `.xmp` sidecars of [scope]'s photos go. Next to the photos when
 * every photo is a folder file under a folder Reveal may write to; for the one folder that lacks a write grant the
 * user can pick it for a one-off write; or flat files in a folder the user chooses (the only choice for photos that
 * only the server holds or that other apps handed over).
 */
@Composable
fun XmpExportSheet(
    scope: XmpExportScope,
    onNextToPhotos: () -> Unit,
    onPickFolderToWrite: (sourceId: Long) -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RevealBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        XmpExportContent(scope, onNextToPhotos, onPickFolderToWrite, onChooseFolder, onDismiss)
    }
}

/** The sheet's body (also what the screenshot tests draw). */
@Composable
fun XmpExportContent(
    scope: XmpExportScope,
    onNextToPhotos: () -> Unit,
    onPickFolderToWrite: (sourceId: Long) -> Unit,
    onChooseFolder: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(LibraryTags.XMP_SHEET)) {
        Eyebrow(stringResource(R.string.xmp_export_title), Modifier.padding(horizontal = 24.dp))
        Text(
            pluralStringResource(R.plurals.xmp_export_for, scope.photos, scope.photos),
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Column {
            when {
                scope.nextToPhotos -> RevealListRow(
                    title = stringResource(R.string.xmp_export_next),
                    subtitle = stringResource(R.string.xmp_export_next_note),
                    icon = PhosphorRegular.FolderOpen,
                    onClick = onNextToPhotos,
                    modifier = Modifier.testTag(LibraryTags.XMP_NEXT),
                )

                scope.needsGrant.size == 1 -> {
                    val (sourceId, name) = scope.needsGrant.single()
                    RevealListRow(
                        title = stringResource(R.string.xmp_export_next_pick, name),
                        subtitle = stringResource(R.string.xmp_export_next_pick_note),
                        icon = PhosphorRegular.FolderOpen,
                        onClick = { onPickFolderToWrite(sourceId) },
                        modifier = Modifier.testTag(LibraryTags.XMP_NEXT_PICK),
                    )
                }
            }
            RevealListRow(
                title = stringResource(R.string.xmp_export_choose),
                subtitle = stringResource(
                    if (scope.canOfferNext) R.string.xmp_export_choose_note else R.string.xmp_export_choose_only_note,
                ),
                icon = PhosphorRegular.FolderPlus,
                onClick = onChooseFolder,
                modifier = Modifier.testTag(LibraryTags.XMP_CHOOSE),
            )
        }
        Text(
            stringResource(R.string.xmp_export_what),
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            GhostButton(stringResource(R.string.xmp_export_cancel), onCancel)
        }
    }
}
