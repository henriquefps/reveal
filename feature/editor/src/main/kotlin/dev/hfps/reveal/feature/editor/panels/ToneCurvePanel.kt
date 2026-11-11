package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.edit.CurveEditing
import dev.hfps.reveal.pipeline.HistoryLabels
import dev.hfps.reveal.pipeline.recipe.ColourToolControls
import dev.hfps.reveal.pipeline.recipe.CurveChannel
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup

/** The Tone curve sub-panel of Light (docs/adr/0013); its reset clears the curves only. */
val ToneCurveSubPanel = SubPanel(
    key = "tone_curve",
    title = R.string.editor_tone_curve,
    group = RecipeGroup.LIGHT,
    reset = { r: Recipe -> r.copy(light = r.light.copy(curve = Curves())) },
    expand = true,
) { host -> ToneCurvePanel(host) }

/**
 * Point curve (RGB and per channel, with Linear / Medium / Strong contrast presets) or parametric curve (four regions
 * with movable splits), over the curve editor with the histogram behind it.
 */
@Suppress("LongMethod")
@Composable
fun ToneCurvePanel(host: PanelHost, modifier: Modifier = Modifier) {
    val curves = host.state.edit.recipe.light.curve
    var parametric by rememberSaveable { mutableStateOf(!curves.parametric.isNeutral && Curves.isLinear(curves.rgb)) }
    var channel by rememberSaveable { mutableStateOf(CurveChannel.RGB) }
    var selected by remember(channel, parametric) { mutableStateOf<Int?>(null) }
    val actions = host.actions
    val ready = host.state.ready
    val target = if (parametric) CurveTarget.Parametric else CurveTarget.Points(channel)
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
                    label = stringResource(R.string.editor_curve_mode_point),
                    selected = !parametric,
                    onClick = { parametric = false },
                    modifier = Modifier.testTag(EditorTags.CURVE_MODE_POINT),
                )
                RevealChip(
                    label = stringResource(R.string.editor_curve_mode_parametric),
                    selected = parametric,
                    onClick = { parametric = true },
                    modifier = Modifier.testTag(EditorTags.CURVE_MODE_PARAMETRIC),
                )
            }
        }
        if (!parametric) {
            FocusRow(host.focus, null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CurveChannel.entries.forEach { ch ->
                        RevealChip(
                            label = stringResource(HistoryLabels.channelName(ch)),
                            selected = channel == ch,
                            onClick = { channel = ch },
                            modifier = Modifier.testTag(EditorTags.curveChannel(ch)),
                        )
                    }
                }
            }
        }
        val points = CurveEditing.shown(curves[channel])
        // The hint never changes size while editing (the readout lives inside the editor), so nothing moves under
        // the finger during a drag.
        Text(
            text = stringResource(
                if (parametric) R.string.editor_curve_parametric_hint else R.string.editor_curve_points_hint,
            ),
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, MAX_SIDE)
            CurveEditor(
                target = target,
                curves = curves,
                histogram = host.state.histogram,
                side = side,
                enabled = ready,
                callbacks = CurveCallbacks(
                    onPoints = { actions.onCurve(channel, it) },
                    onRegion = { region, value -> actions.onControl(REGIONS[region], value) },
                    onCommit = actions.onCommit,
                    onReset = {
                        if (parametric) {
                            actions.onEditAndCommit { r ->
                                r.copy(light = r.light.copy(curve = r.light.curve.copy(parametric = ParametricCurve())))
                            }
                        } else {
                            actions.onEditAndCommit { r ->
                                r.copy(light = r.light.copy(curve = r.light.curve.with(channel, emptyList())))
                            }
                        }
                    },
                    onSelect = { selected = it },
                ),
                readout = selected?.let { points.getOrNull(it) }?.takeIf { !parametric }?.let {
                    stringResource(R.string.editor_curve_readout, CurveEditing.level(it.x), CurveEditing.level(it.y))
                },
            )
        }
        if (parametric) {
            ColourToolControls.parametric.forEach { id -> ControlSlider(host, id, labelOf(id)) }
            ColourToolControls.splits.forEach { id -> ControlSlider(host, id, labelOf(id)) }
        } else if (channel == CurveChannel.RGB) {
            FocusRow(host.focus, null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESETS.forEachIndexed { i, (label, preset) ->
                        RevealChip(
                            label = stringResource(label),
                            selected = samePoints(points, CurveEditing.shown(preset)),
                            onClick = { actions.onCurvePreset(preset) },
                            enabled = ready,
                            modifier = Modifier.testTag(EditorTags.curvePreset(i)),
                        )
                    }
                }
            }
        }
    }
}

private val REGIONS = listOf(
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_SHADOWS,
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_DARKS,
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_LIGHTS,
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_HIGHLIGHTS,
)

private val PRESETS = listOf(
    R.string.editor_curve_preset_linear to Curves.LINEAR,
    R.string.editor_curve_preset_medium to Curves.MEDIUM_CONTRAST,
    R.string.editor_curve_preset_strong to Curves.STRONG_CONTRAST,
)

private fun labelOf(id: dev.hfps.reveal.pipeline.recipe.ControlId): Int = when (id) {
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_HIGHLIGHTS -> R.string.editor_curve_highlights
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_LIGHTS -> R.string.editor_curve_lights
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_DARKS -> R.string.editor_curve_darks
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_SHADOWS -> R.string.editor_curve_shadows
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_SPLIT_SHADOWS -> R.string.editor_curve_split_shadows
    dev.hfps.reveal.pipeline.recipe.ControlId.CURVE_SPLIT_MIDTONES -> R.string.editor_curve_split_midtones
    else -> R.string.editor_curve_split_highlights
}

private val MAX_SIDE = 320.dp
