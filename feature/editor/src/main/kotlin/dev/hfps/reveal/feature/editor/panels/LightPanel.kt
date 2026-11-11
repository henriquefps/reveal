package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.R as PipelineR
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.RecipeGroup

/**
 * The Light panel: the profile row (opens the Profile sub-panel; Lightroom-style, the base look sits at the top of
 * the basic adjustments rather than in a tool of its own) and exposure, contrast, highlights, shadows, whites, blacks.
 */
@Composable
fun LightPanel(host: PanelHost, modifier: Modifier = Modifier) {
    Column(modifier) {
        FocusRow(host.focus, null) {
            NavigationRow(
                label = stringResource(R.string.editor_profile),
                value = stringResource(lookName(host.state.edit.recipe.profile.base)),
                onClick = { host.navigator.push(ProfileSubPanel) },
                modifier = Modifier.testTag(EditorTags.PROFILE_ROW),
            )
        }
        ControlSlider(host, ControlId.EXPOSURE, R.string.editor_exposure)
        ControlSlider(host, ControlId.CONTRAST, R.string.editor_contrast)
        ControlSlider(host, ControlId.HIGHLIGHTS, R.string.editor_highlights)
        ControlSlider(host, ControlId.SHADOWS, R.string.editor_shadows)
        ControlSlider(host, ControlId.WHITES, R.string.editor_whites)
        ControlSlider(host, ControlId.BLACKS, R.string.editor_blacks)
    }
}

/** The Profile sub-panel: look chips and the amount (0-200 %, ignored by Standard). */
val ProfileSubPanel = SubPanel("profile", R.string.editor_profile, RecipeGroup.PROFILE) { host -> ProfilePanel(host) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePanel(host: PanelHost, modifier: Modifier = Modifier) {
    val profile = host.state.edit.recipe.profile
    Column(modifier) {
        FocusRow(host.focus, null) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BaseLook.entries.forEach { look ->
                    RevealChip(
                        label = stringResource(lookName(look)),
                        selected = profile.base == look,
                        onClick = { host.actions.onLook(look) },
                        modifier = Modifier.testTag(EditorTags.look(look)),
                    )
                }
            }
        }
        ControlSlider(
            host = host,
            id = ControlId.PROFILE_AMOUNT,
            label = R.string.editor_profile_amount,
            enabled = profile.base != BaseLook.STANDARD,
        )
    }
}

/** A row that opens a sub-panel: label, current value, chevron (56dp, the hfps list row in the panel). */
@Composable
internal fun NavigationRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RevealTheme.typography.ui, color = colors.foreground, modifier = Modifier.weight(1f))
        Text(value, style = RevealTheme.typography.ui, color = colors.mutedForeground)
        Icon(
            imageVector = PhosphorRegular.CaretRight,
            contentDescription = null,
            tint = colors.mutedForeground,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(16.dp),
        )
    }
}

/** Localised look name (shared with the history labels of `:core:pipeline`). */
internal fun lookName(look: BaseLook): Int = when (look) {
    BaseLook.STANDARD -> PipelineR.string.history_value_look_standard
    BaseLook.NEUTRAL -> PipelineR.string.history_value_look_neutral
    BaseLook.VIVID -> PipelineR.string.history_value_look_vivid
    BaseLook.PORTRAIT -> PipelineR.string.history_value_look_portrait
    BaseLook.LANDSCAPE -> PipelineR.string.history_value_look_landscape
    BaseLook.MONOCHROME -> PipelineR.string.history_value_look_monochrome
    BaseLook.CAMERA_MATCH -> PipelineR.string.history_value_look_camera_match
}
