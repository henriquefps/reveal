@file:Suppress("MagicNumber")

package dev.hfps.reveal.ui.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hfps.reveal.R
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * A mock of the darkroom (always inside `EditorTheme`): zero-chroma surround, a stand-in "photo" (a neutral grey
 * gradient, never a real image) and the panel card with static rows. Sliders themselves are M1/M2.
 */
@Composable
internal fun EditorSurfaceSample(modifier: Modifier = Modifier) {
    val c = RevealTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
            .clip(RevealTheme.shapes.card)
            .background(c.background)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF3D3D3D), Color(0xFF8C8C8C), Color(0xFFD6D6D6)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.editor_photo_area),
                style = RevealTheme.typography.monoSmall,
                color = Color.Black,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).align(Alignment.CenterHorizontally),
            shape = RevealTheme.shapes.card,
            color = c.card,
            border = BorderStroke(1.dp, c.hairline),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow(stringResource(R.string.editor_panel_light))
                ValueRow(R.string.editor_exposure, R.string.editor_exposure_value, 0.7f)
                ValueRow(R.string.editor_contrast, R.string.editor_contrast_value, 0.44f)
                ValueRow(R.string.editor_highlights, R.string.editor_highlights_value, 0.18f)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tool(PhosphorFill.Sun, R.string.tool_light, active = true)
            Tool(PhosphorRegular.Palette, R.string.tool_colour)
            Tool(PhosphorRegular.Sparkle, R.string.tool_effects)
            Tool(PhosphorRegular.Crop, R.string.tool_crop)
            Tool(PhosphorRegular.MagicWand, R.string.tool_masks)
            Tool(PhosphorRegular.Drop, R.string.tool_heal)
        }
    }
}

@Composable
private fun ValueRow(@StringRes label: Int, @StringRes value: Int, fraction: Float) {
    val c = RevealTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(label), style = RevealTheme.typography.ui, color = c.cardForeground)
            Text(stringResource(value), style = RevealTheme.typography.value, color = c.cardForeground)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.muted),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(c.primary),
            )
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, @StringRes label: Int, active: Boolean = false) {
    val color = if (active) RevealTheme.colors.primary else RevealTheme.colors.mutedForeground
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
        Text(stringResource(label), style = RevealTheme.typography.ui.copy(fontSize = 12.sp), color = color)
    }
}
