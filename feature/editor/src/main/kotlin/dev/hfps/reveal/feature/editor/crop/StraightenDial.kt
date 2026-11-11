@file:Suppress("MagicNumber")

package dev.hfps.reveal.feature.editor.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The straighten dial (docs/plan/product.md, "Straighten"): a ruler of degree ticks under a fixed centre mark,
 * dragged relatively (the ruler follows the finger; it never jumps), -45..+45 with 0.1 precision, a haptic tick and
 * a short snap zone at 0, double-tap to level. The [value] is the angle **as seen** (clockwise positive).
 *
 * @param onDragStart a drag begins (the overlay shows the fine grid); [onValueChange] then gets each new value and
 *   [onDragEnd] closes the gesture.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun StraightenDial(
    value: Float,
    onValueChange: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    val range = RecipeRanges.angle
    val label = stringResource(R.string.editor_crop_straighten)
    val haptics = LocalHapticFeedback.current
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val start by rememberUpdatedState(onDragStart)
    val end by rememberUpdatedState(onDragEnd)
    val reset by rememberUpdatedState(onReset)
    Column(
        modifier
            .testTag(EditorTags.CROP_DIAL)
            .semantics {
                contentDescription = label
                stateDescription = range.format(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value, range.min..range.max)
                setProgress { target ->
                    start()
                    change(range.snap(target, range.fineStep))
                    end()
                    true
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = RevealTheme.typography.ui, color = colors.foreground, modifier = Modifier.weight(1f))
            Text(range.format(value), style = RevealTheme.typography.value, color = colors.foreground)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(DIAL_HEIGHT)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
                .pointerInput(Unit) {
                    val perDegree = PX_PER_DEGREE.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var slopChange: Offset? = null
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ ->
                            slopChange = c.position
                            c.consume()
                        } ?: return@awaitEachGesture
                        start()
                        val origin = currentValue
                        val x0 = down.position.x
                        var last = origin
                        fun update(x: Float) {
                            // Ruler follows the finger: dragging right brings smaller angles under the mark.
                            var v = range.snap(origin - (x - x0) / perDegree, range.fineStep)
                            if (abs(v) < SNAP_ZONE) v = 0f
                            if (ticksAtZero(last, v)) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            if (v != last) {
                                last = v
                                change(v)
                            }
                        }
                        slopChange?.let { update(it.x) }
                        horizontalDrag(drag.id) {
                            it.consume()
                            update(it.position.x)
                        }
                        end()
                    }
                },
        ) {
            val perDegree = PX_PER_DEGREE.toPx()
            val cx = size.width / 2f
            val stroke = 1.dp.toPx()
            val firstDegree = ceil(value - cx / perDegree).toInt().coerceAtLeast(range.min.toInt())
            val lastDegree = floor(value + cx / perDegree).toInt().coerceAtMost(range.max.toInt())
            for (d in firstDegree..lastDegree) {
                val x = cx + (d - value) * perDegree
                val major = d % MAJOR_EVERY == 0
                val h = size.height * if (d == 0) {
                    0.75f
                } else if (major) {
                    0.55f
                } else {
                    0.3f
                }
                val colour = if (major) colors.foreground else colors.mutedForeground
                drawLine(colour, Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f), stroke)
            }
            drawLine(colors.primary, Offset(cx, 0f), Offset(cx, size.height), 2.dp.toPx())
        }
    }
}

private val DIAL_HEIGHT = 40.dp
private val PX_PER_DEGREE = 9.dp
private const val MAJOR_EVERY = 5
private const val SNAP_ZONE = 0.25f

/** A haptic tick when the value reaches 0 or crosses it. */
private fun ticksAtZero(last: Float, v: Float): Boolean = (last != 0f && v == 0f) || last * v < 0f
