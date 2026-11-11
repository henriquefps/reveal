package dev.hfps.reveal.feature.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.gl.CompareLayout
import dev.hfps.reveal.pipeline.gl.CompareMode
import dev.hfps.reveal.pipeline.gl.ViewFrame
import kotlin.math.roundToInt

/**
 * The chrome of the before/after views over the photo frame: for a split, the divider with a handle (drag it, or
 * TalkBack adjust) and "Before"/"After" labels; for side by side, the two labels. The pixels themselves are drawn by
 * the renderer ([CompareLayout]).
 */
@Composable
fun CompareOverlay(mode: CompareMode, onCompare: (CompareMode) -> Unit, modifier: Modifier = Modifier) {
    when (mode) {
        CompareMode.Off -> Unit

        is CompareMode.Split -> SplitDivider(mode, onCompare, modifier)

        CompareMode.SideBySide -> BoxWithConstraints(modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val (first, second) = with(density) {
                CompareLayout.sideBySide(ViewFrame(0f, 0f, maxWidth.toPx(), maxHeight.toPx()))
            }
            CompareLabel(R.string.editor_before, first)
            CompareLabel(R.string.editor_after, second)
        }
    }
}

@Composable
private fun CompareLabel(label: Int, pane: ViewFrame, modifier: Modifier = Modifier) {
    Eyebrow(
        text = stringResource(label),
        color = RevealTheme.colors.foreground,
        modifier = modifier
            .offset { IntOffset(pane.left.roundToInt(), pane.top.roundToInt()) }
            .padding(8.dp)
            .background(RevealTheme.colors.background.copy(alpha = LABEL_ALPHA), RevealTheme.shapes.chip)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Suppress("LongMethod")
@Composable
private fun SplitDivider(mode: CompareMode.Split, onCompare: (CompareMode) -> Unit, modifier: Modifier = Modifier) {
    val currentMode by rememberUpdatedState(mode)
    val colors = RevealTheme.colors
    val description = stringResource(R.string.editor_compare_divider)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val lengthPx = with(density) { if (mode.leftRight) maxWidth.toPx() else maxHeight.toPx() }
        val at: Dp = (if (mode.leftRight) maxWidth else maxHeight) * mode.position
        val drag = Modifier.pointerInput(mode.leftRight, lengthPx) {
            detectDragGestures { change, amount ->
                change.consume()
                val delta = (if (currentMode.leftRight) amount.x else amount.y) / lengthPx
                onCompare(currentMode.copy(position = (currentMode.position + delta).coerceIn(MIN_POS, MAX_POS)))
            }
        }
        // Labels at the outer ends of each side.
        Eyebrow(
            text = stringResource(R.string.editor_before),
            color = colors.foreground,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(colors.background.copy(alpha = LABEL_ALPHA), RevealTheme.shapes.chip)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Eyebrow(
            text = stringResource(R.string.editor_after),
            color = colors.foreground,
            modifier = Modifier
                .align(if (mode.leftRight) Alignment.TopEnd else Alignment.BottomStart)
                .padding(8.dp)
                .background(colors.background.copy(alpha = LABEL_ALPHA), RevealTheme.shapes.chip)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        val line = if (mode.leftRight) {
            Modifier
                .fillMaxHeight()
                .offset(x = at - LINE / 2)
                .width(LINE)
        } else {
            Modifier
                .fillMaxWidth()
                .offset(y = at - LINE / 2)
                .height(LINE)
        }
        Box(line.background(colors.foreground))
        val handleOffset = if (mode.leftRight) {
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = at - HANDLE / 2)
        } else {
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = at - HANDLE / 2)
        }
        Box(
            modifier = handleOffset
                .size(HANDLE)
                .testTag(EditorTags.COMPARE_DIVIDER)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(currentMode.position, MIN_POS..MAX_POS)
                    setProgress {
                        onCompare(currentMode.copy(position = it.coerceIn(MIN_POS, MAX_POS)))
                        true
                    }
                }
                .then(drag),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(KNOB)
                    .background(colors.foreground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (mode.leftRight) {
                        PhosphorRegular.SquareSplitHorizontal
                    } else {
                        PhosphorRegular.SquareSplitVertical
                    },
                    contentDescription = null,
                    tint = colors.background,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private const val LABEL_ALPHA = 0.7f
private const val MIN_POS = 0.02f
private const val MAX_POS = 0.98f
private val LINE = 2.dp
private val HANDLE = 48.dp
private val KNOB = 32.dp
