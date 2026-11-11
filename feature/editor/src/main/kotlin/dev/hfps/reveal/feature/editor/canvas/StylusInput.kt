package dev.hfps.reveal.feature.editor.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import kotlin.math.roundToInt

/**
 * Reports where a hovering pen is over this element ([onHover] with the position, `null` when it leaves, lands or
 * hovers out of range). Hover is a stylus (and mouse) feature: fingers never hover, so phones never see it. Does not
 * consume anything: the element's own gestures run as before.
 */
fun Modifier.penHover(key: Any?, onHover: (Offset?) -> Unit): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            val hovering = change.type == PointerType.Stylus || change.type == PointerType.Mouse
            when {
                !hovering -> Unit
                event.type == PointerEventType.Exit || change.pressed -> onHover(null)
                event.type == PointerEventType.Enter || event.type == PointerEventType.Move -> onHover(change.position)
            }
        }
    }
}

/** The hovering pen's position over an element, as state ([Modifier.penHover] feeds it). */
class PenHover {
    var at: Offset? by mutableStateOf(null)
}

/** A [PenHover] remembered for the composition. */
@Composable
fun rememberPenHover(): PenHover = remember { PenHover() }

/** [Modifier.penHover] writing into [hover]. */
@Composable
fun Modifier.penHover(hover: PenHover): Modifier {
    val current by rememberUpdatedState(hover)
    return penHover(hover) { current.at = it }
}

/**
 * A small value tooltip next to the pen at [at] (element pixels), kept inside the element: above and to the right of
 * the tip, flipped when it would leave the element. Place it in a [Box] filling the element.
 */
@Composable
fun HoverTooltip(text: String, at: Offset, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    Layout(
        content = {
            Surface(
                shape = RevealTheme.shapes.control,
                color = colors.card,
                border = BorderStroke(1.dp, colors.hairline),
                modifier = Modifier.testTag(EditorTags.HOVER_TOOLTIP),
            ) {
                Text(
                    text = text,
                    style = RevealTheme.typography.value,
                    color = colors.foreground,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(Constraints(maxWidth = constraints.maxWidth))
        val gap = TIP_GAP.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            var x = at.x.roundToInt() + gap
            var y = at.y.roundToInt() - gap - placeable.height
            if (x + placeable.width > constraints.maxWidth) x = at.x.roundToInt() - gap - placeable.width
            if (y < 0) y = at.y.roundToInt() + gap
            placeable.place(
                x.coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0)),
                y.coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0)),
            )
        }
    }
}

/** A [Box] filling its parent that shows [text] at [at] when both are set. */
@Composable
fun HoverTooltipLayer(text: String?, at: Offset?, modifier: Modifier = Modifier) {
    if (text == null || at == null) return
    Box(modifier) { HoverTooltip(text, at) }
}

private val TIP_GAP = 16.dp
