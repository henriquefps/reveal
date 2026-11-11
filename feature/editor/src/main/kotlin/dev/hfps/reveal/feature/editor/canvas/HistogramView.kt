package dev.hfps.reveal.feature.editor.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.HistogramColors
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.Histogram
import java.util.Locale

/**
 * The live histogram (RGB + luminance) over the top-left of the photo. Its two top corners are the clipping
 * toggles (docs/plan/product.md): the left one turns the blue shadow-clipping overlay on the photo on and off, the
 * right one the red highlight overlay. A corner lights up in its colour while that end of the photo is clipped,
 * and is filled while its overlay is on.
 */
@Composable
fun HistogramView(
    histogram: Histogram?,
    clipping: ClippingWarnings,
    onClipping: (ClippingWarnings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    Surface(
        modifier = modifier
            .size(HISTOGRAM_WIDTH, HISTOGRAM_HEIGHT)
            .testTag(EditorTags.HISTOGRAM),
        shape = RevealTheme.shapes.control,
        color = colors.card.copy(alpha = BACKGROUND_ALPHA),
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        val description = stringResource(R.string.editor_histogram)
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .semantics { contentDescription = description },
            ) {
                if (histogram != null) drawHistogram(histogram)
            }
            ClipCorner(
                label = stringResource(R.string.editor_histogram_shadows),
                on = clipping.shadows,
                clipped = (histogram?.shadowClipped ?: 0f) > CLIPPED_SHARE,
                share = histogram?.shadowClipped ?: 0f,
                color = colors.primary,
                leading = true,
                onToggle = { onClipping(clipping.copy(shadows = !clipping.shadows)) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .testTag(EditorTags.HISTOGRAM_SHADOWS),
            )
            ClipCorner(
                label = stringResource(R.string.editor_histogram_highlights),
                on = clipping.highlights,
                clipped = (histogram?.highlightClipped ?: 0f) > CLIPPED_SHARE,
                share = histogram?.highlightClipped ?: 0f,
                color = colors.destructive,
                leading = false,
                onToggle = { onClipping(clipping.copy(highlights = !clipping.highlights)) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .testTag(EditorTags.HISTOGRAM_HIGHLIGHTS),
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ClipCorner(
    label: String,
    on: Boolean,
    clipped: Boolean,
    share: Float,
    color: Color,
    leading: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = RevealTheme.colors.mutedForeground
    val percent = String.format(Locale.ROOT, "%.1f %%", share * PERCENT)
    Box(
        modifier = modifier
            .size(CORNER_TARGET)
            .semantics {
                contentDescription = label
                stateDescription = percent
                toggleableState = ToggleableState(on)
            }
            .clickable(role = Role.Switch, onClick = onToggle),
        contentAlignment = if (leading) Alignment.TopStart else Alignment.TopEnd,
    ) {
        Canvas(
            Modifier
                .padding(4.dp)
                .size(CORNER_MARK),
        ) {
            val tint = if (clipped || on) color else muted
            val path = Path().apply {
                if (leading) {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(0f, size.height)
                } else {
                    moveTo(size.width, 0f)
                    lineTo(0f, 0f)
                    lineTo(size.width, size.height)
                }
                close()
            }
            if (on) drawPath(path, tint) else drawPath(path, tint, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

private fun DrawScope.drawHistogram(h: Histogram) {
    val peak = h.peak.toFloat()
    drawChannel(h.luma, peak, HistogramColors.Luma.copy(alpha = LUMA_ALPHA), BlendMode.SrcOver)
    drawChannel(h.red, peak, HistogramColors.Red.copy(alpha = RGB_ALPHA), BlendMode.Plus)
    drawChannel(h.green, peak, HistogramColors.Green.copy(alpha = RGB_ALPHA), BlendMode.Plus)
    drawChannel(h.blue, peak, HistogramColors.Blue.copy(alpha = RGB_ALPHA), BlendMode.Plus)
}

private fun DrawScope.drawChannel(bins: IntArray, peak: Float, color: Color, blend: BlendMode) {
    val path = Path()
    val w = size.width
    val h = size.height
    val dx = w / (Histogram.BINS - 1)
    path.moveTo(0f, h)
    for (i in 0 until Histogram.BINS) {
        val y = h - h * (bins[i] / peak).coerceAtMost(1f)
        path.lineTo(i * dx, y)
    }
    path.lineTo(w, h)
    path.close()
    drawPath(path, color, blendMode = blend)
}

private const val BACKGROUND_ALPHA = 0.85f
private const val LUMA_ALPHA = 0.35f
private const val RGB_ALPHA = 0.55f
private const val CLIPPED_SHARE = 0.001f
private const val PERCENT = 100f
private val HISTOGRAM_WIDTH = 168.dp
private val HISTOGRAM_HEIGHT = 88.dp
private val CORNER_TARGET = 48.dp
private val CORNER_MARK = 12.dp
