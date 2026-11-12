package dev.hfps.reveal.designsystem.components.slider

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.R
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * The editor slider (docs/plan/ux.md, "Sliders"): a full-width row with the [label] (14sp) on the left, the value
 * (tabular 14sp, written by [format]) on the right and the track below.
 *
 * - **Relative drag anywhere on the row**: the thumb never jumps to the finger; the value moves with the finger's
 *   travel ([SliderDrag]). Moving the finger vertically away from the row gives fine control, the further the finer,
 *   independent of speed ([FineControl]).
 * - Haptic tick when crossing [SliderSpec.neutral] and at the ends; a short snap zone holds the neutral value.
 * - **Double-tap** = [onReset]; **tap the value** = numeric input with a ± stepper ([NumericInputSheet]).
 * - Bipolar sliders fill from the centre with a centre tick, unipolar ones from the left; [trackBrush] draws a
 *   gradient track instead (white balance, HSL) with no fill.
 * - TalkBack: name, value and range, adjust actions (step and fine step), reset and "type a value".
 * - At least 48dp tall; the label wraps and the value never clips at 200 % font.
 *
 * @param onValueChange every new value while dragging (one per frame at most); [onValueChangeFinished] once the
 *   gesture, the numeric input or an accessibility action is done (commit point for history).
 * @param onDragStateChange `true` when a drag starts, `false` when it ends (the panel fades the other rows).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun RevealSlider(
    label: String,
    value: Float,
    spec: SliderSpec,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackBrush: Brush? = null,
    testTag: String? = null,
    onDragStateChange: (Boolean) -> Unit = {},
    onValueChangeFinished: () -> Unit = {},
) {
    val colors = RevealTheme.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val currentOnReset by rememberUpdatedState(onReset)
    val currentOnDragState by rememberUpdatedState(onDragStateChange)
    var trackWidth by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var showInput by rememberSaveable { mutableStateOf(false) }
    val geometry = remember { RowGeometry() }
    val valueSlop = with(density) { VALUE_SLOP.toPx() }
    val drag = remember(spec, trackWidth, density) {
        with(density) {
            SliderDrag(
                spec = spec,
                trackWidthPx = trackWidth,
                fineStartPx = FineControl.START_DP.dp.toPx(),
                fineHalvingPx = FineControl.HALVING_DP.dp.toPx(),
                snapZonePx = SNAP_ZONE.toPx(),
            )
        }
    }
    val valueText = format(value)
    val increase = stringResource(R.string.slider_increase)
    val decrease = stringResource(R.string.slider_decrease)
    val increaseFine = stringResource(R.string.slider_increase_fine)
    val decreaseFine = stringResource(R.string.slider_decrease_fine)
    val reset = stringResource(R.string.slider_reset)
    val typeValue = stringResource(R.string.slider_type_value)
    val rangeText = stringResource(R.string.slider_range, format(spec.min), format(spec.max))

    fun nudge(by: Float, granularity: Float): Boolean {
        val next = spec.snap(currentValue + by, granularity)
        currentOnChange(next)
        currentOnFinished()
        return true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .onGloballyPositioned { geometry.row = it }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = "$valueText, $rangeText"
                progressBarRangeInfo = ProgressBarRangeInfo(value, spec.min..spec.max)
                if (!enabled) {
                    disabled()
                } else {
                    setProgress { target ->
                        currentOnChange(spec.snap(target, spec.fineStep))
                        currentOnFinished()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(increase) { nudge(spec.step, spec.step) },
                        CustomAccessibilityAction(decrease) { nudge(-spec.step, spec.step) },
                        CustomAccessibilityAction(increaseFine) { nudge(spec.fineStep, spec.fineStep) },
                        CustomAccessibilityAction(decreaseFine) { nudge(-spec.fineStep, spec.fineStep) },
                        CustomAccessibilityAction(reset) {
                            currentOnReset()
                            true
                        },
                        CustomAccessibilityAction(typeValue) {
                            showInput = true
                            true
                        },
                    )
                }
            }
            .pointerInput(enabled, drag) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    val row = geometry.row ?: return@awaitEachGesture
                    val startY = row.localToRoot(down.position).y
                    var lastX = row.localToRoot(slop.position).x
                    drag.start(currentValue)
                    dragging = true
                    currentOnDragState(true)
                    try {
                        drag(slop.id) { change ->
                            val coords = geometry.row ?: return@drag
                            val root = coords.localToRoot(change.position)
                            val step = drag.move(root.x - lastX, root.y - startY)
                            lastX = root.x
                            change.consume()
                            if (step.value != currentValue) currentOnChange(step.value)
                            when (step.tick) {
                                SliderTick.NEUTRAL -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                SliderTick.END -> haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                null -> Unit
                            }
                        }
                    } finally {
                        dragging = false
                        currentOnDragState(false)
                        currentOnFinished()
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { currentOnReset() },
                    onTap = { offset -> if (geometry.valueBounds?.contains(offset) == true) showInput = true },
                )
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = label,
                style = RevealTheme.typography.ui,
                color = colors.foreground,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
                    .clearAndSetSemantics {},
            )
            Text(
                text = valueText,
                style = RevealTheme.typography.value,
                color = if (dragging) colors.primary else colors.foreground,
                softWrap = false,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .onGloballyPositioned { geometry.valueBounds = it.boundsInRow(geometry, valueSlop) }
                    .clearAndSetSemantics { testTag?.let { this.testTag = it + RevealSliderTags.VALUE_SUFFIX } },
            )
        }
        SliderTrack(
            spec = spec,
            value = value,
            dragging = dragging,
            trackBrush = trackBrush,
            onTrackWidth = { trackWidth = it },
        )
    }
    if (showInput) {
        NumericInputSheet(
            label = label,
            value = value,
            spec = spec,
            format = format,
            onDismiss = { showInput = false },
            onConfirm = { typed ->
                showInput = false
                currentOnChange(typed)
                currentOnFinished()
            },
        )
    }
}

/** Layout coordinates the gesture code needs (not state: they never drive recomposition). */
private class RowGeometry {
    var row: LayoutCoordinates? = null

    /** Bounds of the value text in the row's coordinates, padding included, grown to a 48dp-ish target. */
    var valueBounds: Rect? = null
}

/**
 * The value's tap target in the row: its text grown by [slop] px ([VALUE_SLOP], in dp, so the target ends at the same
 * place on every density: as raw pixels it reached the track's end on a 1.75x tablet, where a tap there opened the
 * numeric input).
 */
private fun LayoutCoordinates.boundsInRow(geometry: RowGeometry, slop: Float): Rect? {
    val row = geometry.row ?: return null
    val local = row.localBoundingBoxOf(this, clipBounds = false)
    return Rect(
        left = local.left - slop,
        top = local.top - slop,
        right = row.size.width.toFloat(),
        bottom = local.bottom + slop,
    )
}

@Composable
private fun SliderTrack(
    spec: SliderSpec,
    value: Float,
    dragging: Boolean,
    trackBrush: Brush?,
    onTrackWidth: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    val thumb by animateDpAsState(if (dragging) THUMB_DRAGGING else THUMB, RevealMotion.fast(), label = "thumb")
    val density = LocalDensity.current
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_AREA)
            .onSizeChanged { size ->
                val thumb = with(density) { THUMB_DRAGGING.toPx() }
                onTrackWidth((size.width - thumb).coerceAtLeast(1f))
            },
    ) {
        val radius = THUMB_DRAGGING.toPx() / 2f
        val left = radius
        val width = (size.width - 2f * radius).coerceAtLeast(1f)
        val cy = size.height / 2f
        val trackHeight = (if (trackBrush != null) GRADIENT_HEIGHT else TRACK_HEIGHT).toPx()
        val corner = CornerRadius(trackHeight / 2f)
        val top = cy - trackHeight / 2f
        if (trackBrush != null) {
            drawRoundRect(trackBrush, Offset(left, top), Size(width, trackHeight), corner)
        } else {
            drawRoundRect(colors.muted, Offset(left, top), Size(width, trackHeight), corner)
            val from = if (spec.bipolar) spec.fraction(spec.neutral) else 0f
            val to = spec.fraction(value)
            val a = left + width * minOf(from, to)
            val b = left + width * maxOf(from, to)
            if (b > a) drawRoundRect(colors.primary, Offset(a, top), Size(b - a, trackHeight), corner)
        }
        if (spec.bipolar) drawCentreTick(left + width * spec.fraction(spec.neutral), cy, colors.mutedForeground)
        val x = left + width * spec.fraction(value)
        drawCircle(colors.card, radius = thumb.toPx() / 2f + RING.toPx(), center = Offset(x, cy))
        drawCircle(colors.foreground, radius = thumb.toPx() / 2f, center = Offset(x, cy))
    }
}

private fun DrawScope.drawCentreTick(x: Float, cy: Float, color: Color) {
    val h = TICK_HEIGHT.toPx()
    val w = TICK_WIDTH.toPx()
    drawRoundRect(color, Offset(x - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(w / 2f))
}

private const val DISABLED_ALPHA = 0.4f

/** How far around the value text a tap still opens the numeric input (what 24 px were on the phone, ~2.8x). */
private val VALUE_SLOP = 8.dp
private val MIN_HEIGHT = 48.dp
private val TRACK_AREA = 24.dp
private val TRACK_HEIGHT = 4.dp
private val GRADIENT_HEIGHT = 6.dp
private val THUMB = 16.dp
private val THUMB_DRAGGING = 20.dp
private val RING = 2.dp
private val TICK_HEIGHT = 12.dp
private val TICK_WIDTH = 2.dp
private val SNAP_ZONE = 8.dp
