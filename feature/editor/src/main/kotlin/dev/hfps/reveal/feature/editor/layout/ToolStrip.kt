package dev.hfps.reveal.feature.editor.layout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.tools.EditorTool
import dev.hfps.reveal.feature.editor.tools.ToolId

/**
 * The tool strip (portrait, horizontal scroll) or rail (landscape/tablet, vertical scroll): icon + label per tool,
 * the active one in the primary colour with its filled icon; tools that are not available yet are dimmed and say
 * so to TalkBack.
 */
@Composable
fun ToolStrip(
    tools: List<EditorTool>,
    active: ToolId?,
    onSelect: (EditorTool) -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.editor_tools)
    val group = modifier
        .testTag(EditorTags.TOOL_STRIP)
        .semantics { contentDescription = description }
        .selectableGroup()
    if (vertical) {
        Column(
            modifier = group
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tools.forEach { ToolItem(it, it.id == active, onSelect) }
        }
    } else {
        Row(
            modifier = group
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            tools.forEach { ToolItem(it, it.id == active, onSelect) }
        }
    }
}

@Composable
private fun ToolItem(tool: EditorTool, active: Boolean, onSelect: (EditorTool) -> Unit, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    val label = stringResource(tool.label)
    val description = if (tool.available) label else stringResource(R.string.editor_tool_unavailable, label)
    val tint = if (active) colors.primary else colors.foreground
    Column(
        modifier = modifier
            .defaultMinSize(minWidth = ITEM_MIN, minHeight = ITEM_MIN)
            .widthIn(max = ITEM_MAX)
            .alpha(if (tool.available) 1f else UNAVAILABLE_ALPHA)
            .selectable(selected = active, enabled = tool.available, role = Role.Tab) { onSelect(tool) }
            .semantics { contentDescription = description }
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag(EditorTags.tool(tool.id)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (active) tool.activeIcon else tool.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = RevealTheme.typography.eyebrow.copy(letterSpacing = RevealTheme.typography.ui.letterSpacing),
            color = if (active) colors.primary else colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private const val UNAVAILABLE_ALPHA = 0.4f
private val ITEM_MIN = 64.dp
private val ITEM_MAX = 96.dp
