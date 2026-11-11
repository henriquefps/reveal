package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.theme.OkLabColors
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.edit.WheelMath
import dev.hfps.reveal.pipeline.HistoryLabels
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.GradeRegion
import dev.hfps.reveal.pipeline.recipe.MixerProperty
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import kotlin.math.roundToInt

/** The Colour grading sub-panel (docs/adr/0013); its reset clears grading only. */
val GradingSubPanel = SubPanel(
    key = "colour_grading",
    title = R.string.editor_colour_grading,
    group = RecipeGroup.COLOUR,
    reset = { r: Recipe -> r.copy(colour = r.colour.copy(grading = ColourGrading())) },
    expand = true,
) { host -> GradingPanel(host) }

/**
 * Colour grading (docs/plan/ux.md "Colour wheels"): the 3-way view (shadows, midtones, highlights wheels with their
 * luminance sliders) or one large wheel per region with hue, saturation and luminance sliders; blending and balance
 * below. Wheels drag relative to where the thumb lands (it never covers the puck), double-tap resets a wheel.
 */
@Suppress("LongMethod")
@Composable
fun GradingPanel(host: PanelHost, modifier: Modifier = Modifier) {
    var view by rememberSaveable { mutableStateOf<GradeRegion?>(null) }
    val grading = host.state.edit.recipe.colour.grading
    Column(modifier) {
        FocusRow(host.focus, null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RevealChip(
                    label = stringResource(R.string.editor_grading_three_way),
                    selected = view == null,
                    onClick = { view = null },
                    modifier = Modifier.testTag(EditorTags.GRADING_THREE_WAY),
                )
                GradeRegion.entries.forEach { region ->
                    RevealChip(
                        label = stringResource(HistoryLabels.regionName(region)),
                        selected = view == region,
                        onClick = { view = region },
                        modifier = Modifier.testTag(EditorTags.gradeRegion(region)),
                    )
                }
            }
        }
        val single = view
        if (single == null) {
            FocusRow(host.focus, null) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    val side = minOf((maxWidth - 16.dp) / THREE_WAY, SMALL_WHEEL)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(GradeRegion.SHADOWS, GradeRegion.MIDTONES, GradeRegion.HIGHLIGHTS).forEach { region ->
                            Column(Modifier.width(side), horizontalAlignment = Alignment.CenterHorizontally) {
                                ColourWheel(host, region, side)
                                Text(
                                    text = stringResource(HistoryLabels.regionName(region)),
                                    style = RevealTheme.typography.ui,
                                    color = RevealTheme.colors.mutedForeground,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            listOf(GradeRegion.SHADOWS, GradeRegion.MIDTONES, GradeRegion.HIGHLIGHTS).forEach { region ->
                ControlSlider(host, ControlId.grade(region, MixerProperty.LUMINANCE), luminanceLabel(region))
            }
        } else {
            FocusRow(host.focus, null) {
                BoxWithConstraints(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ColourWheel(host, single, minOf(maxWidth - 32.dp, LARGE_WHEEL))
                }
            }
            val wheel = grading[single]
            ControlSlider(
                host,
                ControlId.grade(single, MixerProperty.HUE),
                R.string.editor_grading_hue,
                trackBrush = remember { OkLabColors.hueGradient((0..HUE_STOPS).map { it * FULL_TURN / HUE_STOPS }) },
            )
            ControlSlider(
                host,
                ControlId.grade(single, MixerProperty.SATURATION),
                R.string.editor_grading_saturation,
                trackBrush = remember(wheel.hue) { OkLabColors.saturationGradient(wheel.hue) },
            )
            ControlSlider(host, ControlId.grade(single, MixerProperty.LUMINANCE), R.string.editor_grading_luminance)
        }
        ControlSlider(host, ControlId.GRADE_BLENDING, R.string.editor_grading_blending)
        ControlSlider(host, ControlId.GRADE_BALANCE, R.string.editor_grading_balance)
    }
}

private fun luminanceLabel(region: GradeRegion): Int = when (region) {
    GradeRegion.SHADOWS -> R.string.editor_grading_shadows_luminance
    GradeRegion.MIDTONES -> R.string.editor_grading_midtones_luminance
    GradeRegion.HIGHLIGHTS -> R.string.editor_grading_highlights_luminance
    GradeRegion.GLOBAL -> R.string.editor_grading_luminance
}

/** One grading wheel: hue around (OkLab hue, counter-clockwise from the right), saturation outwards. */
@Suppress("LongMethod")
@Composable
private fun ColourWheel(host: PanelHost, region: GradeRegion, side: Dp) {
    val colors = RevealTheme.colors
    val wheel = host.state.edit.recipe.colour.grading[region]
    val currentWheel by rememberUpdatedState(wheel)
    val actions = host.actions
    val enabled = host.state.ready
    val hueId = ControlId.grade(region, MixerProperty.HUE)
    val satId = ControlId.grade(region, MixerProperty.SATURATION)
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val name = stringResource(R.string.editor_grading_wheel, stringResource(HistoryLabels.regionName(region)))
    val state = stringResource(
        R.string.editor_grading_wheel_state,
        wheel.hue.roundToInt(),
        wheel.saturation.roundToInt(),
    )
    val huePlus = stringResource(R.string.editor_grading_hue_plus)
    val hueMinus = stringResource(R.string.editor_grading_hue_minus)
    val satPlus = stringResource(R.string.editor_grading_saturation_plus)
    val satMinus = stringResource(R.string.editor_grading_saturation_minus)
    val resetLabel = stringResource(R.string.editor_grading_wheel_reset)
    fun set(hue: Float, saturation: Float) = actions.onControls(mapOf(hueId to hue, satId to saturation))
    fun step(dh: Float, ds: Float): Boolean {
        val w = currentWheel
        set(((w.hue + dh) % FULL_TURN + FULL_TURN) % FULL_TURN, (w.saturation + ds).coerceIn(0f, MAX))
        actions.onCommit()
        return true
    }
    fun reset() {
        set(0f, 0f)
        actions.onCommit()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val ring = remember { Brush.sweepGradient(OkLabColors.hueCircle().reversed()) }
    val centre = remember { OkLabColors.color(OkLabColors.SWATCH_L, 0f, 0f) }
    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(region, side) {
            val radius = size.width / 2f
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (drag == null) {
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                        awaitFirstDown(requireUnconsumed = false).also { it.consume() }
                    }
                    if (second != null) reset()
                    return@awaitEachGesture
                }
                val w = currentWheel
                val (px, py) = WheelMath.puck(w.hue, w.saturation, radius)
                var change: PointerInputChange = drag
                while (change.pressed) {
                    val x = px + (change.position.x - down.position.x)
                    val y = py + (change.position.y - down.position.y)
                    val (h, s) = WheelMath.fromPuck(x, y, radius, w.hue)
                    set(h, s)
                    change.consume()
                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                }
                actions.onCommit()
            }
        }
    }
    Canvas(
        Modifier
            .size(side)
            .border(BorderStroke(1.dp, colors.hairline), CircleShape)
            .testTag(EditorTags.gradeWheel(region))
            .semantics {
                contentDescription = name
                stateDescription = state
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction(huePlus) { step(HUE_STEP, 0f) },
                        CustomAccessibilityAction(hueMinus) { step(-HUE_STEP, 0f) },
                        CustomAccessibilityAction(satPlus) { step(0f, SAT_STEP) },
                        CustomAccessibilityAction(satMinus) { step(0f, -SAT_STEP) },
                        CustomAccessibilityAction(resetLabel) { reset().let { true } },
                    )
                }
            }
            .then(gestures),
    ) {
        val r = size.width / 2f
        val c = Offset(r, r)
        drawCircle(ring, r, c)
        drawCircle(Brush.radialGradient(listOf(centre, centre.copy(alpha = 0f)), c, r), r, c)
        drawLine(colors.hairline, Offset(r, 0f), Offset(r, size.height))
        drawLine(colors.hairline, Offset(0f, r), Offset(size.width, r))
        val (px, py) = WheelMath.puck(wheel.hue, wheel.saturation, r)
        val puck = Offset(r + px, r + py)
        drawCircle(colors.card, PUCK.toPx(), puck)
        drawCircle(colors.foreground, PUCK.toPx(), puck, style = Stroke(PUCK_STROKE.toPx()))
    }
}

private const val FULL_TURN = 360f
private const val HUE_STOPS = 12
private const val THREE_WAY = 3
private const val MAX = 100f
private const val HUE_STEP = 5f
private const val SAT_STEP = 5f
private val SMALL_WHEEL = 120.dp
private val LARGE_WHEEL = 240.dp
private val PUCK = 9.dp
private val PUCK_STROKE = 2.dp
