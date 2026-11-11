package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.recipe.ControlId

/**
 * The Detail panel (docs/plan/product.md, "Detail"; docs/adr/0012): sharpening (amount, radius, detail, masking) and
 * noise reduction (luminance with its detail and contrast, colour with its detail and smoothness). A shaping slider is
 * enabled once its amount is on. While Masking is dragged the photo shows the sharpening mask (white = sharpened).
 */
@Composable
fun DetailPanel(host: PanelHost, modifier: Modifier = Modifier) {
    val detail = host.state.edit.recipe.detail
    val masking = host.focus.dragging == ControlId.SHARPEN_MASKING
    val onMask = host.actions.onDetailMask
    LaunchedEffect(masking) { onMask(masking) }
    DisposableEffect(onMask) { onDispose { onMask(false) } }
    Column(modifier) {
        FocusRow(host.focus, null) {
            Text(
                text = stringResource(R.string.editor_detail_hint),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(EditorTags.DETAIL_HINT),
            )
        }
        Section(host, R.string.editor_detail_sharpening)
        val sharpen = detail.sharpenAmount > 0f
        ControlSlider(host, ControlId.SHARPEN_AMOUNT, R.string.editor_detail_amount)
        ControlSlider(host, ControlId.SHARPEN_RADIUS, R.string.editor_detail_radius, enabled = sharpen)
        ControlSlider(host, ControlId.SHARPEN_DETAIL, R.string.editor_detail_detail, enabled = sharpen)
        ControlSlider(host, ControlId.SHARPEN_MASKING, R.string.editor_detail_masking, enabled = sharpen)
        Section(host, R.string.editor_detail_noise)
        val luminance = detail.nrLuminance > 0f
        ControlSlider(host, ControlId.NR_LUMINANCE, R.string.editor_detail_luminance)
        ControlSlider(
            host,
            ControlId.NR_LUMINANCE_DETAIL,
            R.string.editor_detail_luminance_detail,
            enabled = luminance,
        )
        ControlSlider(
            host,
            ControlId.NR_LUMINANCE_CONTRAST,
            R.string.editor_detail_luminance_contrast,
            enabled = luminance,
        )
        val colour = detail.nrColour > 0f
        ControlSlider(host, ControlId.NR_COLOUR, R.string.editor_detail_colour)
        ControlSlider(host, ControlId.NR_COLOUR_DETAIL, R.string.editor_detail_colour_detail, enabled = colour)
        ControlSlider(
            host,
            ControlId.NR_COLOUR_SMOOTHNESS,
            R.string.editor_detail_colour_smoothness,
            enabled = colour,
        )
    }
}

/** A section title of the panel; hidden while a slider is dragged. */
@Composable
private fun Section(host: PanelHost, title: Int) {
    FocusRow(host.focus, null) {
        Eyebrow(
            stringResource(title),
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}
