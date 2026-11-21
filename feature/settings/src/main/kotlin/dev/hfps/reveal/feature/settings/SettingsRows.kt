package dev.hfps.reveal.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme

/** The widest a settings screen's content grows on tablets and in landscape; centred in the card beyond it. */
internal val SettingsMaxWidth = 720.dp

/** The scrolling body of a settings screen: one column, at most [SettingsMaxWidth] wide, centred. */
@Composable
internal fun SettingsBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = SettingsMaxWidth).fillMaxWidth().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A section title inside a settings screen. */
@Composable
internal fun SectionTitle(text: String, first: Boolean = false) {
    Eyebrow(text, Modifier.padding(start = 16.dp, end = 16.dp, top = if (first) 0.dp else 12.dp))
}

/** A muted explanation line under a section or a control. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = RevealTheme.typography.ui,
        color = RevealTheme.colors.mutedForeground,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** A row that opens another screen: icon, title, subtitle and a trailing caret; one TalkBack node. */
@Composable
internal fun NavigationRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    iconTint: Color = RevealTheme.colors.mutedForeground,
) {
    RevealListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        onClick = onClick,
        modifier = modifier.testTag(tag),
        trailing = { Icon(PhosphorRegular.CaretRight, null, tint = iconTint) },
    )
}

/** A titled switch row (Backup and sync, Folders, Editing and XMP, Cache and storage). */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val c = RevealTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = RevealTheme.typography.body, color = if (enabled) c.foreground else c.mutedForeground)
            if (subtitle != null) Text(subtitle, style = RevealTheme.typography.ui, color = c.mutedForeground)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.primary,
                checkedThumbColor = c.primaryForeground,
                uncheckedTrackColor = c.muted,
                uncheckedThumbColor = c.mutedForeground,
                uncheckedBorderColor = c.input,
            ),
            modifier = Modifier.testTag(tag),
        )
    }
}
