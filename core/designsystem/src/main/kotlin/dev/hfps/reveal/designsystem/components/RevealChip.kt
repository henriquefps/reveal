package dev.hfps.reveal.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * Fully rounded toggle chip (aspect ratios, channels, filters). 36dp visible, 48dp touch target. Selected = accent
 * fill. Sentence case. A disabled chip (a feature of a later milestone) is dimmed and not clickable.
 */
@Composable
fun RevealChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = RevealTheme.colors
    val container by animateColorAsState(if (selected) c.primary else c.card, RevealMotion.fast(), label = "chip")
    val content by animateColorAsState(
        if (selected) c.primaryForeground else c.foreground,
        RevealMotion.fast(),
        label = "chipContent",
    )
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        shape = RevealTheme.shapes.chip,
        color = container,
        contentColor = content,
        border = if (selected) null else BorderStroke(1.dp, c.input),
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 36.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text = label, style = RevealTheme.typography.uiMedium)
        }
    }
}

private const val DISABLED_ALPHA = 0.4f
