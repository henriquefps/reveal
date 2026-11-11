package dev.hfps.reveal.ui.gallery

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.R
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.designsystem.theme.ThemeMode

private val ThemeLabels = listOf(
    ThemeMode.System to R.string.theme_system,
    ThemeMode.Light to R.string.theme_light,
    ThemeMode.Dark to R.string.theme_dark,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FoundationsPage(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val c = RevealTheme.colors
    val swatches = listOf(
        R.string.swatch_background to c.background,
        R.string.swatch_card to c.card,
        R.string.swatch_primary to c.primary,
        R.string.swatch_muted to c.muted,
        R.string.swatch_muted_foreground to c.mutedForeground,
        R.string.swatch_destructive to c.destructive,
        R.string.swatch_warning to c.warning,
        R.string.swatch_border to c.border,
        R.string.swatch_ring to c.ring,
    )
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section(R.string.gallery_section_theme) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeLabels.forEach { (mode, label) ->
                    RevealChip(
                        label = stringResource(label),
                        selected = mode == themeMode,
                        onClick = { onThemeModeChange(mode) },
                    )
                }
            }
        }
        Section(R.string.gallery_section_colour) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                swatches.forEach { (label, color) -> Swatch(stringResource(label), color) }
            }
        }
        Section(R.string.gallery_section_type) {
            val t = RevealTheme.typography
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(stringResource(R.string.type_eyebrow))
                TypeLine(R.string.type_ui, t.ui)
                TypeLine(R.string.type_body, t.body)
                TypeLine(R.string.type_body_large, t.bodyLarge)
                TypeLine(R.string.type_title, t.title)
                TypeLine(R.string.type_number, t.number)
                TypeLine(R.string.type_number_large, t.numberLarge)
                TypeLine(R.string.type_mono, t.mono)
                TypeLine(R.string.type_mono_small, t.monoSmall)
            }
        }
    }
}

@Composable
private fun TypeLine(@StringRes text: Int, style: TextStyle) {
    Text(text = stringResource(text), style = style, color = RevealTheme.colors.cardForeground)
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
internal fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Eyebrow(stringResource(title))
        content()
    }
}
