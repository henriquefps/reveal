package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.hfps.reveal.designsystem.components.ChecklistItem
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealChecklist
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.HistoryLabels
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.RecipeGroup

/** The paste chooser as a bottom sheet (phones and tablets alike). */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
fun PasteChooserSheet(
    copied: Recipe,
    groups: Set<RecipeGroup>,
    onGroupsChange: (Set<RecipeGroup>) -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    RevealBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(true)) {
        PasteChooserContent(copied, groups, onGroupsChange, onPaste, onDismiss)
    }
}

/**
 * Which groups of the copied edit to paste (docs/plan/product.md, "copy/paste edits with a chooser"): all, or per
 * group; each row lists what the copied edit holds for that group ("No change" = pasting it resets that group).
 * Geometry is off by default, the last choice is remembered.
 */
@Composable
fun PasteChooserContent(
    copied: Recipe,
    groups: Set<RecipeGroup>,
    onGroupsChange: (Set<RecipeGroup>) -> Unit,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val noChange = stringResource(R.string.editor_paste_no_change)
    val items = remember(copied, context, noChange) {
        val changes = RecipeDiff.between(Recipe.DEFAULT, copied)
        RecipeGroup.entries.map { group ->
            val held = changes.filter { it.field.group == group }.map { HistoryLabels.format(context, it) }
            ChecklistItem(group, HistoryLabels.groupName(context, group), held.joinToString(" · ").ifEmpty { noChange })
        }
    }
    Column(modifier) {
        RevealChecklist(
            title = stringResource(R.string.editor_paste_title),
            allLabel = stringResource(R.string.editor_paste_all),
            items = items,
            checked = groups,
            onCheckedChange = onGroupsChange,
            confirm = stringResource(R.string.editor_paste_confirm),
            cancel = stringResource(R.string.editor_cancel),
            onConfirm = onPaste,
            onCancel = onCancel,
            tagPrefix = EditorTags.PASTE_CHOOSER,
        )
    }
}
