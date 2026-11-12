package dev.hfps.reveal.feature.editor.presets

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hfps.reveal.data.preset.PresetLinks
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.preset.PresetLimits

/**
 * The "Import from a link" flow on screen (docs/adr/0019 "Import from a link"): the link field, the confirmation
 * naming the host, the download's progress with Cancel. Outcomes are toasts ([PresetMessages]). Hosted by the editor
 * (the presets panel's menu) and by `MainActivity` (a link shared to Reveal).
 */
@Composable
fun PresetLinkHost(viewModel: PresetLinkViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            PresetMessages.text(context, event)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.problems.collect { problem ->
            Toast.makeText(context, problemText(problem), Toast.LENGTH_LONG).show()
        }
    }
    PresetLinkSheet(
        state = state,
        onText = viewModel::onText,
        onSubmit = viewModel::submit,
        onConfirm = viewModel::confirm,
        onCancel = viewModel::cancel,
    )
}

private fun problemText(problem: LinkProblem): Int = when (problem) {
    LinkProblem.NOT_A_LINK -> R.string.presets_link_not_a_link
    LinkProblem.NOT_HTTPS -> R.string.presets_link_not_https
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetLinkSheet(
    state: PresetLinkState,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state is PresetLinkState.Closed) return
    RevealBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Eyebrow(stringResource(R.string.presets_link_title), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            when (state) {
                is PresetLinkState.Entering -> Entering(state, onText, onSubmit, onCancel)
                is PresetLinkState.Confirming -> Confirming(state, onConfirm, onCancel)
                is PresetLinkState.Downloading -> Downloading(state.host, state.received, state.total, onCancel)
                is PresetLinkState.Importing -> Downloading(state.host, null, null, onCancel)
                PresetLinkState.Closed -> Unit
            }
        }
    }
}

@Composable
private fun Entering(
    state: PresetLinkState.Entering,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    PresetTextField(
        label = stringResource(R.string.presets_link_field),
        value = state.text,
        onChange = onText,
        tag = EditorTags.PRESET_LINK_FIELD,
        maxLength = PresetLinks.MAX_LENGTH,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
    )
    val error = state.error
    if (error != null) {
        Body(
            stringResource(problemText(error)),
            Modifier.testTag(EditorTags.PRESET_LINK_ERROR),
            color = RevealTheme.colors.destructive,
        )
    } else {
        Body(stringResource(R.string.presets_link_hint), muted = true)
    }
    Buttons(
        confirm = stringResource(R.string.presets_link_continue),
        onConfirm = onSubmit,
        onCancel = onCancel,
        confirmTag = EditorTags.PRESET_LINK_CONTINUE,
    )
}

@Composable
private fun Confirming(state: PresetLinkState.Confirming, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column {
        val context = LocalContext.current
        Body(
            stringResource(R.string.presets_link_confirm, state.host),
            Modifier.testTag(EditorTags.PRESET_LINK_HOST),
        )
        Body(state.url, muted = true)
        Body(
            stringResource(
                R.string.presets_link_confirm_detail,
                Formatter.formatShortFileSize(context, PresetLimits.PACK_BYTES),
            ),
            muted = true,
        )
        Buttons(
            confirm = stringResource(R.string.presets_link_download),
            onConfirm = onConfirm,
            onCancel = onCancel,
            confirmTag = EditorTags.PRESET_LINK_CONFIRM,
        )
    }
}

@Composable
private fun Downloading(host: String, received: Long?, total: Long?, onCancel: () -> Unit) {
    Column {
        val context = LocalContext.current
        Body(stringResource(R.string.presets_link_downloading, host))
        val progress = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(EditorTags.PRESET_LINK_PROGRESS)
        if (received != null && total != null && total > 0) {
            LinearProgressIndicator(
                progress = { (received.toFloat() / total).coerceIn(0f, 1f) },
                color = RevealTheme.colors.primary,
                trackColor = RevealTheme.colors.muted,
                modifier = progress,
            )
            Body(
                stringResource(
                    R.string.presets_link_progress,
                    Formatter.formatShortFileSize(context, received),
                    Formatter.formatShortFileSize(context, total),
                ),
                muted = true,
            )
        } else {
            LinearProgressIndicator(
                color = RevealTheme.colors.primary,
                trackColor = RevealTheme.colors.muted,
                modifier = progress,
            )
            if (received != null && received > 0) Body(Formatter.formatShortFileSize(context, received), muted = true)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            GhostButton(
                stringResource(R.string.presets_cancel),
                onCancel,
                Modifier.testTag(EditorTags.PRESET_LINK_CANCEL),
            )
        }
    }
}

@Composable
private fun Body(
    text: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Text(
        text,
        style = if (muted) RevealTheme.typography.ui else RevealTheme.typography.body,
        color = color ?: if (muted) RevealTheme.colors.mutedForeground else RevealTheme.colors.foreground,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

@Composable
private fun Buttons(confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, confirmTag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        GhostButton(stringResource(R.string.presets_cancel), onCancel, Modifier.testTag(EditorTags.PRESET_LINK_CANCEL))
        PrimaryButton(confirm, onConfirm, Modifier.testTag(confirmTag))
    }
}
