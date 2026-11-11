package dev.hfps.reveal.designsystem.components.slider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.R
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import java.util.Locale

/**
 * The numeric input of a [RevealSlider], as a bottom sheet: the value in a decimal field (the locale's separator),
 * − / + stepping by [SliderSpec.step], the range, Cancel and Done. The typed value is clamped and snapped to
 * [SliderSpec.fineStep] before [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumericInputSheet(
    label: String,
    value: Float,
    spec: SliderSpec,
    format: (Float) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    RevealBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        NumericInputContent(label, value, spec, format, onDismiss, onConfirm, Modifier.navigationBarsPadding())
    }
}

/** The sheet's content (public for screenshot tests, which cannot capture a sheet window). */
@Suppress("LongMethod")
@Composable
fun NumericInputContent(
    label: String,
    value: Float,
    spec: SliderSpec,
    format: (Float) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
) {
    val colors = RevealTheme.colors
    val locale: Locale = LocalConfiguration.current.locales[0]
    val initial = remember(value, spec, locale) { NumericInput.format(value, spec.decimals, locale) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val parsed = NumericInput.parse(field.text, locale)
    val focus = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { focus.requestFocus() }

    fun set(number: Float) {
        val text = NumericInput.format(spec.snap(number, spec.fineStep), spec.decimals, locale)
        field = TextFieldValue(text, TextRange(text.length))
    }

    fun confirm() {
        parsed?.let { onConfirm(spec.snap(it, spec.fineStep)) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Eyebrow(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RevealIconButton(
                icon = PhosphorRegular.Minus,
                contentDescription = stringResource(R.string.slider_decrease),
                onClick = { set((parsed ?: value) - spec.step) },
                modifier = Modifier.testTag(NumericInputTags.MINUS),
            )
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 96.dp)
                    .focusRequester(focus)
                    .testTag(NumericInputTags.FIELD),
                textStyle = RevealTheme.typography.number.copy(textAlign = TextAlign.Center),
                singleLine = true,
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                shape = RevealTheme.shapes.control,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.ring,
                    unfocusedBorderColor = colors.input,
                    cursorColor = colors.primary,
                    focusedTextColor = colors.foreground,
                    unfocusedTextColor = colors.foreground,
                ),
            )
            RevealIconButton(
                icon = PhosphorRegular.Plus,
                contentDescription = stringResource(R.string.slider_increase),
                onClick = { set((parsed ?: value) + spec.step) },
                modifier = Modifier.testTag(NumericInputTags.PLUS),
            )
        }
        Text(
            text = if (parsed == null) {
                stringResource(R.string.slider_input_invalid)
            } else {
                stringResource(R.string.slider_range, format(spec.min), format(spec.max))
            },
            style = RevealTheme.typography.ui,
            color = if (parsed == null) colors.destructive else colors.mutedForeground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            GhostButton(text = stringResource(R.string.slider_input_cancel), onClick = onDismiss)
            PrimaryButton(
                text = stringResource(R.string.slider_input_done),
                onClick = { confirm() },
                enabled = parsed != null,
                modifier = Modifier.testTag(NumericInputTags.DONE),
            )
        }
    }
}
