package dev.hfps.reveal.designsystem.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.designsystem.theme.ThemeMode

private val ThemeLabels = listOf(
    ThemeMode.System to "System",
    ThemeMode.Light to "Light",
    ThemeMode.Dark to "Dark",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FoundationsPage(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val c = RevealTheme.colors
    val swatches = listOf(
        "Background" to c.background,
        "Card" to c.card,
        "Primary" to c.primary,
        "Muted" to c.muted,
        "Muted text" to c.mutedForeground,
        "Destructive" to c.destructive,
        "Warning" to c.warning,
        "Border" to c.border,
        "Ring" to c.ring,
    )
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section("Theme") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeLabels.forEach { (mode, label) ->
                    RevealChip(
                        label = label,
                        selected = mode == themeMode,
                        onClick = { onThemeModeChange(mode) },
                    )
                }
            }
        }
        Section("Colour") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                swatches.forEach { (label, color) -> Swatch(label, color) }
            }
        }
        Section("Type scale") {
            val t = RevealTheme.typography
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Eyebrow 12")
                TypeLine("UI 14: buttons, labels, menus", t.ui)
                TypeLine("Body 15: detail values and row titles", t.body)
                TypeLine("Body 16: longer reading text", t.bodyLarge)
                TypeLine("Title 18 semibold", t.title)
                TypeLine("20 tabular: 1,284 photos", t.number)
                TypeLine("24 tabular: +0.70 EV", t.numberLarge)
                TypeLine("Mono 13: DSC01234.ARW", t.mono)
                TypeLine("Mono 12: 5600 K · f/2.8 · 1/250", t.monoSmall)
            }
        }
    }
}

@Composable
private fun TypeLine(text: String, style: TextStyle) {
    Text(text = text, style = style, color = RevealTheme.colors.cardForeground)
}

@Composable
private fun Swatch(label: String, color: Color) {
    Column(modifier = Modifier.width(88.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .height(48.dp)
                .width(88.dp)
                .background(color, RevealTheme.shapes.control)
                .border(1.dp, RevealTheme.colors.hairline, RevealTheme.shapes.control),
        )
        Text(
            text = label,
            style = RevealTheme.typography.monoSmall.copy(fontFamily = RevealTheme.typography.ui.fontFamily),
            color = RevealTheme.colors.mutedForeground,
        )
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Eyebrow(title)
        content()
    }
}
