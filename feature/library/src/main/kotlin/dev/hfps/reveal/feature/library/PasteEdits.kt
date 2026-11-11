package dev.hfps.reveal.feature.library

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.hfps.reveal.designsystem.components.ChecklistItem
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealChecklist
import dev.hfps.reveal.pipeline.HistoryLabels
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.RecipeGroup

/** The paste chooser of the library's selection, as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasteEditsSheet(copied: Recipe, groups: Set<RecipeGroup>, targets: Int, actions: LibraryActions) {
    RevealBottomSheet(onDismissRequest = actions.onPasteDismiss, sheetState = rememberModalBottomSheetState(true)) {
        PasteEditsContent(copied, groups, targets, actions)
    }
}

/**
 * Which groups of the copied edit to paste onto the [targets] selected photos (one history step per photo). Each row
 * lists what the copied edit holds for that group; geometry is off by default and the last choice is remembered.
 */
@Composable
fun PasteEditsContent(
    copied: Recipe,
    groups: Set<RecipeGroup>,
    targets: Int,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val noChange = stringResource(R.string.library_paste_no_change)
    val items = remember(copied, context, noChange) {
        val changes = RecipeDiff.between(Recipe.DEFAULT, copied)
        RecipeGroup.entries.map { group ->
            val held = changes.filter { it.field.group == group }.map { HistoryLabels.format(context, it) }
            ChecklistItem(group, HistoryLabels.groupName(context, group), held.joinToString(" · ").ifEmpty { noChange })
        }
    }
    RevealChecklist(
        title = pluralStringResource(R.plurals.library_paste_title_count, targets, targets),
        allLabel = stringResource(R.string.library_paste_all),
        items = items,
        checked = groups,
        onCheckedChange = actions.onPasteGroups,
        confirm = stringResource(R.string.library_paste_confirm),
        cancel = stringResource(R.string.library_cancel),
        onConfirm = actions.onPasteConfirm,
        onCancel = actions.onPasteDismiss,
        modifier = modifier,
        tagPrefix = LibraryTags.PASTE_CHOOSER,
    )
}
