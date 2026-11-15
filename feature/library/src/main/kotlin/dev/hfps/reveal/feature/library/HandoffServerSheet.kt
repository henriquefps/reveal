package dev.hfps.reveal.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.handoff.ServerIssue
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme

/** Test tags of the server-reference hand-off notice (ADR 0011 "Amendment W3/H"). */
object HandoffServerTags {
    const val SHEET = "handoff-server-sheet"
    const val PAIR = "handoff-server-pair"
    const val CANCEL = "handoff-server-cancel"
    const val OK = "handoff-server-ok"
}

/**
 * What a hand-off of server references could not do: not paired (offer to pair), paired with another server (name
 * it), the server not answering, and/or [notFound] items the server does not have.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoffServerSheet(
    issue: ServerIssue?,
    notFound: Int,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    RevealBottomSheet(onDismissRequest = onDismiss) {
        HandoffServerContent(issue, notFound, onPair, onDismiss)
    }
}

@Composable
fun HandoffServerContent(
    issue: ServerIssue?,
    notFound: Int,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    val notPaired = issue is ServerIssue.NotPaired
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag(HandoffServerTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (notPaired) PhosphorRegular.CloudArrowUp else PhosphorRegular.Warning,
                contentDescription = null,
                tint = colors.mutedForeground,
                modifier = Modifier.size(28.dp),
            )
            Text(
                stringResource(
                    when (issue) {
                        is ServerIssue.NotPaired -> R.string.handoff_server_title_not_paired
                        is ServerIssue.OtherServer -> R.string.handoff_server_title_other
                        is ServerIssue.Unreachable -> R.string.handoff_server_title_unreachable
                        null -> R.string.handoff_server_title_missing
                    },
                ),
                style = RevealTheme.typography.title,
                color = colors.foreground,
            )
        }
        when (issue) {
            is ServerIssue.NotPaired -> Body(stringResource(R.string.handoff_server_not_paired))

            is ServerIssue.OtherServer ->
                Body(stringResource(R.string.handoff_server_other, issue.serverName.ifBlank { "?" }))

            is ServerIssue.Unreachable -> Body(stringResource(R.string.handoff_server_unreachable))

            null -> Unit
        }
        if (notFound > 0) Body(pluralStringResource(R.plurals.handoff_server_missing, notFound, notFound))
        if (notPaired) {
            PrimaryButton(
                text = stringResource(R.string.handoff_server_pair),
                onClick = onPair,
                icon = PhosphorRegular.QrCode,
                modifier = Modifier.fillMaxWidth().testTag(HandoffServerTags.PAIR),
            )
            GhostButton(
                text = stringResource(R.string.handoff_server_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(HandoffServerTags.CANCEL),
            )
        } else {
            PrimaryButton(
                text = stringResource(R.string.handoff_server_ok),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(HandoffServerTags.OK),
            )
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = RevealTheme.typography.body, color = RevealTheme.colors.mutedForeground)
}
