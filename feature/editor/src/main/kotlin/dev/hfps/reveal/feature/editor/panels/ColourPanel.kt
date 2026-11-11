package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.slider.SliderGradients
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.R as PipelineR
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.WbMode

/**
 * The Colour panel: the treatment (colour or black & white), white balance (mode chips, eyedropper, temperature and
 * tint with gradient tracks: absolute kelvin/tint for RAW, the relative pair for display-referred photos), vibrance
 * and saturation (off in black & white), and the rows of the colour mixer (the B&W mix in black & white) and colour
 * grading.
 */
@Suppress("LongMethod")
@Composable
fun ColourPanel(host: PanelHost, modifier: Modifier = Modifier) {
    val edit = host.state.edit
    val wb = edit.recipe.colour.whiteBalance
    val modes = if (edit.isRaw) WbMode.entries else listOf(WbMode.AS_SHOT, WbMode.CUSTOM)
    val colour = edit.recipe.colour
    val blackWhite = edit.recipe.profile.treatment == Treatment.BLACK_WHITE
    Column(modifier) {
        FocusRow(host.focus, null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Treatment.entries.forEach { treatment ->
                    RevealChip(
                        label = stringResource(treatmentName(treatment)),
                        selected = edit.recipe.profile.treatment == treatment,
                        onClick = { host.actions.onTreatment(treatment) },
                        enabled = host.state.ready,
                        modifier = Modifier.testTag(EditorTags.treatment(treatment)),
                    )
                }
            }
        }
        FocusRow(host.focus, null) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Eyebrow(stringResource(R.string.editor_white_balance), Modifier.weight(1f))
                    val eyedropper = host.state.view.eyedropper
                    RevealIconButton(
                        icon = if (eyedropper) PhosphorFill.Eyedropper else PhosphorRegular.Eyedropper,
                        contentDescription = stringResource(
                            if (edit.isRaw) R.string.editor_eyedropper else R.string.editor_eyedropper_raw_only,
                        ),
                        onClick = { host.actions.onEyedropper(!eyedropper) },
                        enabled = edit.isRaw && host.state.ready,
                        active = eyedropper,
                        modifier = Modifier.testTag(EditorTags.EYEDROPPER),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(PaddingValues(horizontal = 16.dp)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    modes.forEach { mode ->
                        val available = mode != WbMode.AUTO || edit.autoWb != null
                        if (available) {
                            RevealChip(
                                label = stringResource(wbModeName(mode)),
                                selected = wb.mode == mode,
                                onClick = { host.actions.onWbMode(mode) },
                                modifier = Modifier.testTag(EditorTags.wbMode(mode)),
                            )
                        }
                    }
                }
            }
        }
        if (edit.isRaw) {
            ControlSlider(
                host,
                ControlId.TEMPERATURE,
                R.string.editor_temperature,
                trackBrush = SliderGradients.Temperature,
            )
            ControlSlider(host, ControlId.TINT, R.string.editor_tint, trackBrush = SliderGradients.Tint)
        } else {
            ControlSlider(
                host,
                ControlId.REL_TEMPERATURE,
                R.string.editor_temperature,
                trackBrush = SliderGradients.Temperature,
            )
            ControlSlider(host, ControlId.REL_TINT, R.string.editor_tint, trackBrush = SliderGradients.Tint)
        }
        ControlSlider(host, ControlId.VIBRANCE, R.string.editor_vibrance, enabled = !blackWhite)
        ControlSlider(host, ControlId.SATURATION, R.string.editor_saturation, enabled = !blackWhite)
        FocusRow(host.focus, null) {
            Column {
                if (blackWhite) {
                    NavigationRow(
                        label = stringResource(R.string.editor_bw_mix),
                        value = stringResource(editedOrOff(!colour.greyMixer.isNeutral)),
                        onClick = { host.navigator.push(GreyMixerSubPanel) },
                        modifier = Modifier.testTag(EditorTags.GREY_MIXER_ROW),
                    )
                } else {
                    NavigationRow(
                        label = stringResource(R.string.editor_colour_mixer),
                        value = stringResource(editedOrOff(!colour.mixer.isNeutral)),
                        onClick = { host.navigator.push(ColourMixerSubPanel) },
                        modifier = Modifier.testTag(EditorTags.MIXER_ROW),
                    )
                }
                NavigationRow(
                    label = stringResource(R.string.editor_colour_grading),
                    value = stringResource(editedOrOff(!colour.grading.isNeutral)),
                    onClick = { host.navigator.push(GradingSubPanel) },
                    modifier = Modifier.testTag(EditorTags.GRADING_ROW),
                )
            }
        }
    }
}

/** Localised treatment name (shared with the history labels of `:core:pipeline`). */
internal fun treatmentName(treatment: Treatment): Int = when (treatment) {
    Treatment.COLOUR -> PipelineR.string.history_value_treatment_colour
    Treatment.BLACK_WHITE -> PipelineR.string.history_value_treatment_black_white
}

/** Localised white-balance mode name (shared with the history labels of `:core:pipeline`). */
internal fun wbModeName(mode: WbMode): Int = when (mode) {
    WbMode.AS_SHOT -> PipelineR.string.history_value_wb_as_shot
    WbMode.AUTO -> PipelineR.string.history_value_wb_auto
    WbMode.DAYLIGHT -> PipelineR.string.history_value_wb_daylight
    WbMode.CLOUDY -> PipelineR.string.history_value_wb_cloudy
    WbMode.SHADE -> PipelineR.string.history_value_wb_shade
    WbMode.TUNGSTEN -> PipelineR.string.history_value_wb_tungsten
    WbMode.FLUORESCENT -> PipelineR.string.history_value_wb_fluorescent
    WbMode.FLASH -> PipelineR.string.history_value_wb_flash
    WbMode.CUSTOM -> PipelineR.string.history_value_wb_custom
}
