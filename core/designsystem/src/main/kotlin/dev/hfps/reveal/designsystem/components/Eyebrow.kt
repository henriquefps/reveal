package dev.hfps.reveal.designsystem.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.hfps.reveal.designsystem.theme.RevealTheme

/** Small uppercase section label ("LIGHT", "TONE CURVE"): the only uppercase text in the app. Never for controls. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = RevealTheme.colors.mutedForeground) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = RevealTheme.typography.eyebrow,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
