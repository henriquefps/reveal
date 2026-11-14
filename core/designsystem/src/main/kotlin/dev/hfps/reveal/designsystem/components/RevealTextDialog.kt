@file:Suppress("MatchingDeclarationName")

package dev.hfps.reveal.designsystem.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import dev.hfps.reveal.designsystem.theme.RevealTheme

/** Test tags of [RevealTextDialog] (one dialog at a time on screen). */
object RevealTextDialogTags {
    const val FIELD = "text_dialog_field"
    const val CONFIRM = "text_dialog_confirm"
    const val CANCEL = "text_dialog_cancel"
}

/**
 * A small dialog that asks for one line of text (a name): [title], a field labelled [label] starting at [initial]
 * (selected, so typing replaces it), and [confirm] / [cancel]. Confirm is disabled while the text is blank;
 * [onConfirm] gets the trimmed text. Strings come from the caller (each feature owns its own).
 */
@Suppress("LongParameterList")
@Composable
fun RevealTextDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    cancel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    val c = RevealTheme.colors
    val text = value.text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        shape = RevealTheme.shapes.card,
        title = { Text(title, style = RevealTheme.typography.title, color = c.foreground) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it.copy(text = it.text.take(maxLength)) },
                singleLine = true,
                label = { Text(label, style = RevealTheme.typography.ui) },
                textStyle = RevealTheme.typography.body.copy(color = c.foreground),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotEmpty()) onConfirm(text) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = c.primary,
                    unfocusedIndicatorColor = c.input,
                    cursorColor = c.primary,
                    focusedLabelColor = c.mutedForeground,
                    unfocusedLabelColor = c.mutedForeground,
                ),
                modifier = Modifier.focusRequester(focus).testTag(RevealTextDialogTags.FIELD),
            )
        },
        confirmButton = {
            PrimaryButton(
                text = confirm,
                onClick = { onConfirm(text) },
                enabled = text.isNotEmpty(),
                modifier = Modifier.testTag(RevealTextDialogTags.CONFIRM),
            )
        },
        dismissButton = {
            GhostButton(text = cancel, onClick = onDismiss, modifier = Modifier.testTag(RevealTextDialogTags.CANCEL))
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private const val DEFAULT_MAX_LENGTH = 80
