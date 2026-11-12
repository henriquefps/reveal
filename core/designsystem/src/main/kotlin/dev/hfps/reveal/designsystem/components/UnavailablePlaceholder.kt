package dev.hfps.reveal.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * What stands where a photo Reveal cannot show at all would be (its file is unreadable and nothing stands in for it):
 * never an empty square. The muted surface, a broken-image icon, the file name on one line and a small [label]
 * ("Unavailable"). Fills its parent; [compact] is for a filmstrip thumbnail (icon only, no text that cannot fit).
 */
@Composable
fun UnavailablePlaceholder(name: String, label: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = RevealTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.muted)
            .padding(if (compact) 2.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = PhosphorRegular.ImageBroken,
            contentDescription = null,
            tint = colors.mutedForeground,
            modifier = Modifier.size(if (compact) COMPACT_ICON else ICON),
        )
        if (!compact) {
            FileNameText(
                name = name,
                style = RevealTheme.typography.monoSmall,
                color = colors.foreground,
                modifier = Modifier.padding(top = 6.dp),
            )
            Eyebrow(text = label, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private val ICON: Dp = 28.dp
private val COMPACT_ICON: Dp = 20.dp
