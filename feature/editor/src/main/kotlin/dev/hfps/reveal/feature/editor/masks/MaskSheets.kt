@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hfps.reveal.feature.editor.masks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.pipeline.recipe.Mask

/**
 * The "+" chooser (docs/plan/ux.md "Masks"): the AI kinds the models offer (Subject, Sky, Background, People and its
 * parts), then the shapes (linear, radial, brush) and the ranges (colour, luminance). [target] says whether the pick
 * makes a new mask or a component of the selected one.
 */
@Composable
internal fun MaskCreateSheet(
    target: CreateTarget,
    aiKinds: List<MaskKind>,
    onPick: (NewComponent) -> Unit,
    onDismiss: () -> Unit,
) {
    RevealBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(MaskTags.CHOOSER)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val title = when (target) {
                CreateTarget.NewMask -> stringResource(R.string.masks_new)
                is CreateTarget.AddTo -> stringResource(MaskLabels.combine(target.op))
            }
            Eyebrow(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            MaskChoices(aiKinds, onPick)
        }
    }
}

/** Every kind of mask or component, grouped; People opens its parts. Also the empty panel's content. */
@Composable
internal fun MaskChoices(aiKinds: List<MaskKind>, onPick: (NewComponent) -> Unit) {
    var people by rememberSaveable { mutableStateOf(false) }
    Column {
        val top = AiMasksKinds.top.filter { it in aiKinds }
        val parts = AiMasksKinds.people.filter { it in aiKinds }
        if (top.isNotEmpty() || parts.isNotEmpty()) {
            Eyebrow(stringResource(R.string.masks_group_ai), Modifier.padding(start = 16.dp, top = 8.dp))
            for (kind in top) Choice(NewComponent.Ai(kind), onPick)
            if (parts.isNotEmpty()) {
                RevealListRow(
                    title = stringResource(R.string.masks_kind_people),
                    icon = PhosphorRegular.Users,
                    onClick = { people = !people },
                    trailing = {
                        androidx.compose.material3.Icon(
                            if (people) PhosphorRegular.CaretUp else PhosphorRegular.CaretDown,
                            contentDescription = null,
                            tint = RevealTheme.colors.mutedForeground,
                        )
                    },
                    modifier = Modifier.testTag(MaskTags.choice("people-group")),
                )
                if (people) {
                    Column(Modifier.padding(start = 24.dp)) {
                        for (kind in parts) Choice(NewComponent.Ai(kind), onPick)
                    }
                }
            }
        }
        Eyebrow(stringResource(R.string.masks_group_shapes), Modifier.padding(start = 16.dp, top = 8.dp))
        Choice(NewComponent.Linear, onPick)
        Choice(NewComponent.Radial, onPick)
        Choice(NewComponent.Brush, onPick)
        Eyebrow(stringResource(R.string.masks_group_ranges), Modifier.padding(start = 16.dp, top = 8.dp))
        Choice(NewComponent.ColourRange, onPick)
        Choice(NewComponent.LuminanceRange, onPick)
    }
}

@Composable
private fun Choice(kind: NewComponent, onPick: (NewComponent) -> Unit) {
    RevealListRow(
        title = stringResource(MaskLabels.choice(kind)),
        icon = MaskLabels.icon(kind),
        onClick = { onPick(kind) },
        modifier = Modifier.testTag(MaskTags.choice(kind)),
    )
}

/** A mask's ⋯ menu: rename, duplicate, invert, move earlier / later, delete (confirmed). */
@Suppress("LongMethod")
@Composable
internal fun MaskOptionsSheet(mask: Mask, index: Int, count: Int, actions: MaskActions, onDismiss: () -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    RevealBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when {
                renaming -> {
                    var name by rememberSaveable { mutableStateOf(mask.name) }
                    Eyebrow(
                        stringResource(R.string.masks_rename),
                        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    NameField(name) { name = it.take(MaskEdits.MAX_NAME) }
                    Buttons(
                        confirm = stringResource(R.string.masks_save),
                        confirmTag = MaskTags.RENAME_SAVE,
                        onConfirm = {
                            if (name.isNotBlank()) actions.rename(mask.id, name)
                            onDismiss()
                        },
                        onCancel = { renaming = false },
                    )
                }

                deleting -> {
                    Text(
                        stringResource(R.string.masks_delete_confirm, mask.name),
                        style = RevealTheme.typography.body,
                        color = RevealTheme.colors.foreground,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Buttons(
                        confirm = stringResource(R.string.masks_delete),
                        confirmTag = MaskTags.option("confirm"),
                        onConfirm = {
                            actions.delete(mask.id)
                            onDismiss()
                        },
                        onCancel = { deleting = false },
                    )
                }

                else -> {
                    Eyebrow(mask.name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    Option("rename", R.string.masks_rename, PhosphorRegular.PencilSimple) { renaming = true }
                    Option("duplicate", R.string.masks_duplicate, PhosphorRegular.Copy) {
                        actions.duplicate(mask.id)
                        onDismiss()
                    }
                    Option("invert", R.string.masks_invert, PhosphorRegular.SelectionInverse) {
                        actions.invert(mask.id)
                        onDismiss()
                    }
                    if (index > 0) {
                        Option("earlier", R.string.masks_move_earlier, PhosphorRegular.CaretLeft) {
                            actions.move(mask.id, index - 1)
                            onDismiss()
                        }
                    }
                    if (index in 0 until count - 1) {
                        Option("later", R.string.masks_move_later, PhosphorRegular.CaretRight) {
                            actions.move(mask.id, index + 1)
                            onDismiss()
                        }
                    }
                    Option("delete", R.string.masks_delete, PhosphorRegular.Trash) { deleting = true }
                }
            }
        }
    }
}

@Composable
private fun Option(
    key: String,
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    RevealListRow(
        title = stringResource(label),
        icon = icon,
        onClick = onClick,
        modifier = Modifier.testTag(MaskTags.option(key)),
    )
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    val c = RevealTheme.colors
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(R.string.masks_name), style = RevealTheme.typography.ui) },
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(MaskTags.RENAME_FIELD),
    )
}

@Composable
private fun Buttons(confirm: String, confirmTag: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        GhostButton(stringResource(R.string.masks_cancel), onCancel)
        PrimaryButton(confirm, onConfirm, Modifier.testTag(confirmTag))
    }
}
