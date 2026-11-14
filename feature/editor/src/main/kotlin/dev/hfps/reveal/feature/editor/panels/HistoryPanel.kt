package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.HistoryLabels
import dev.hfps.reveal.pipeline.R as PipelineR

/**
 * The history list (docs/plan/product.md, "History"): the committed steps, newest first, with "Original" (the state
 * before the first step) at the bottom. The current step is highlighted; the steps above it are the redo branch,
 * muted. Tapping a step goes back (or forward) to it; a new edit after going back drops the muted steps.
 */
@Composable
fun HistoryPanel(host: PanelHost, modifier: Modifier = Modifier) {
    val history = host.state.edit.history
    val context = LocalContext.current
    val labels = remember(history.entries, context) {
        history.entries.map { entry ->
            val title = HistoryLabels.format(context, entry.label)
            // A named step (Paste, Reset) lists what it changed underneath.
            val detail = if (entry.label.action != null) {
                HistoryLabels.details(context, entry.label).joinToString(" · ").ifEmpty { null }
            } else if (entry.label.changes.size > 1) {
                HistoryLabels.details(context, entry.label).drop(1).joinToString(" · ")
            } else {
                null
            }
            title to detail
        }
    }
    val enabled = host.state.ready
    Column(modifier.testTag(EditorTags.HISTORY_LIST)) {
        // Snapshots of a library version (docs/adr/0025), then the steps.
        if (host.state.versions.library) {
            SnapshotsSection(host)
            Eyebrow(
                stringResource(R.string.editor_history_steps),
                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        if (history.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_history_empty),
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        for (position in history.entries.lastIndex downTo -1) {
            val (title, detail) = if (position < 0) {
                stringResource(PipelineR.string.history_original) to null
            } else {
                labels[position]
            }
            HistoryRow(
                title = title,
                detail = detail,
                current = position == history.cursor,
                undone = position > history.cursor,
                enabled = enabled,
                onClick = { host.actions.onGoTo(position) },
                modifier = Modifier.testTag(EditorTags.historyStep(position)),
            )
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun HistoryRow(
    title: String,
    detail: String?,
    current: Boolean,
    undone: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    val currentLabel = stringResource(R.string.editor_history_current)
    val undoneLabel = stringResource(R.string.editor_history_undone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RevealTheme.shapes.control)
            .background(if (current) colors.muted else colors.card)
            .selectable(selected = current, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                when {
                    current -> stateDescription = currentLabel
                    undone -> stateDescription = undoneLabel
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (current) PhosphorFill.CheckCircle else PhosphorRegular.ClockCounterClockwise,
                contentDescription = null,
                tint = when {
                    current -> colors.primary
                    else -> colors.mutedForeground
                },
                modifier = Modifier.size(if (current) 20.dp else 16.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = title,
                style = if (current) RevealTheme.typography.uiMedium else RevealTheme.typography.ui,
                color = when {
                    current -> colors.primary
                    undone -> colors.mutedForeground
                    else -> colors.foreground
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = RevealTheme.typography.ui,
                    color = colors.mutedForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
