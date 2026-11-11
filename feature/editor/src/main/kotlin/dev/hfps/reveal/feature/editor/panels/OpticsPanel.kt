package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.optics.LensProfileSource
import dev.hfps.reveal.pipeline.recipe.ControlId

/**
 * The Optics panel (docs/plan/product.md, "Optics"; docs/adr/0015): the lens profile found for the photo (or none),
 * Lens corrections with the distortion and vignetting amounts, Remove chromatic aberration (the profile's data or the
 * automatic estimate) and Defringe (purple and green amounts and hue ranges).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpticsPanel(host: PanelHost, modifier: Modifier = Modifier) {
    val edit = host.state.edit
    val optics = edit.recipe.optics
    val lens = edit.lens
    val ready = host.state.ready
    Column(modifier) {
        FocusRow(host.focus, null) {
            val status = when {
                lens == null -> stringResource(R.string.editor_optics_no_profile)

                lens.source == LensProfileSource.EMBEDDED ->
                    stringResource(R.string.editor_optics_profile_embedded, lens.name)

                else -> stringResource(R.string.editor_optics_profile_lensfun, lens.name)
            }
            Text(
                text = status,
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(EditorTags.OPTICS_PROFILE),
            )
        }
        FocusRow(host.focus, null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RevealChip(
                    label = stringResource(R.string.editor_optics_lens_corrections),
                    selected = optics.lensCorrections,
                    enabled = ready && lens != null,
                    icon = PhosphorRegular.Aperture,
                    onClick = {
                        host.actions.onEditAndCommit {
                            it.copy(optics = it.optics.copy(lensCorrections = !it.optics.lensCorrections))
                        }
                    },
                    modifier = Modifier.testTag(EditorTags.OPTICS_LENS),
                )
                RevealChip(
                    label = stringResource(R.string.editor_optics_remove_ca),
                    selected = optics.removeCa,
                    enabled = ready,
                    onClick = { host.actions.onRemoveCa(!optics.removeCa) },
                    modifier = Modifier.testTag(EditorTags.OPTICS_CA),
                )
            }
        }
        val corrections = optics.lensCorrections && lens != null
        Section(host, R.string.editor_optics_amounts)
        ControlSlider(
            host,
            ControlId.LENS_DISTORTION,
            R.string.editor_optics_distortion,
            enabled = corrections && lens.hasDistortion,
        )
        ControlSlider(
            host,
            ControlId.LENS_VIGNETTING,
            R.string.editor_optics_vignetting,
            enabled = corrections && lens.hasVignetting,
        )
        Section(host, R.string.editor_optics_defringe)
        val purple = optics.purpleAmount > 0f
        ControlSlider(host, ControlId.DEFRINGE_PURPLE_AMOUNT, R.string.editor_optics_purple_amount)
        ControlSlider(host, ControlId.DEFRINGE_PURPLE_HUE_LOW, R.string.editor_optics_purple_hue_low, enabled = purple)
        ControlSlider(
            host,
            ControlId.DEFRINGE_PURPLE_HUE_HIGH,
            R.string.editor_optics_purple_hue_high,
            enabled = purple,
        )
        val green = optics.greenAmount > 0f
        ControlSlider(host, ControlId.DEFRINGE_GREEN_AMOUNT, R.string.editor_optics_green_amount)
        ControlSlider(host, ControlId.DEFRINGE_GREEN_HUE_LOW, R.string.editor_optics_green_hue_low, enabled = green)
        ControlSlider(host, ControlId.DEFRINGE_GREEN_HUE_HIGH, R.string.editor_optics_green_hue_high, enabled = green)
    }
}
