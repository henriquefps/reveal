@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hfps.reveal.feature.library.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.preset.GroupKind
import dev.hfps.reveal.data.preset.LibraryPreset
import dev.hfps.reveal.data.preset.PresetLibrary
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.components.slider.SliderSpec
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.R
import kotlin.math.roundToInt

/**
 * "Apply preset" on the library selection (docs/adr/0019 "Batch"): the presets by group (favourites first), a
 * strength (0-200 %) and Apply, which queues the background batch. Each photo gets its own undoable step.
 */
@Suppress("LongMethod")
@Composable
fun ApplyPresetSheet(
    library: PresetLibrary,
    count: Int,
    onApply: (uuid: String, strength: Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var strength by rememberSaveable { mutableFloatStateOf(FULL) }
    val c = RevealTheme.colors
    RevealBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Eyebrow(stringResource(R.string.presets_batch_title), Modifier.padding(horizontal = 24.dp))
        Text(
            pluralStringResource(R.plurals.presets_batch_for, count, count),
            style = RevealTheme.typography.ui,
            color = c.mutedForeground,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            if (library.all.isEmpty()) {
                Text(
                    stringResource(R.string.presets_batch_empty),
                    style = RevealTheme.typography.body,
                    color = c.mutedForeground,
                    modifier = Modifier.padding(24.dp),
                )
            }
            if (library.favourites.isNotEmpty()) {
                Eyebrow(stringResource(R.string.presets_batch_favourites), Modifier.padding(start = 24.dp, top = 12.dp))
                library.favourites.forEach { PresetRow(it, chosen == it.uuid) { chosen = it.uuid } }
            }
            for (group in library.groups.filter { it.presets.isNotEmpty() }) {
                val name = if (group.kind == GroupKind.USER && group.name.isEmpty()) {
                    stringResource(R.string.presets_batch_default_group)
                } else {
                    group.name
                }
                Eyebrow(name, Modifier.padding(start = 24.dp, top = 12.dp))
                group.presets.forEach { PresetRow(it, chosen == it.uuid) { chosen = it.uuid } }
            }
        }
        RevealSlider(
            label = stringResource(R.string.presets_batch_strength),
            value = strength * PERCENT,
            spec = SPEC,
            format = { "${it.roundToInt()} %" },
            onValueChange = { strength = it / PERCENT },
            onReset = { strength = FULL },
            testTag = LibraryTags.PRESET_STRENGTH,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            GhostButton(stringResource(R.string.presets_batch_cancel), onDismiss)
            PrimaryButton(
                text = stringResource(R.string.presets_batch_apply),
                onClick = { chosen?.let { onApply(it, strength) } },
                enabled = chosen != null && count > 0,
                modifier = Modifier.testTag(LibraryTags.PRESET_APPLY_CONFIRM),
            )
        }
    }
}

@Composable
private fun PresetRow(preset: LibraryPreset, selected: Boolean, onClick: () -> Unit) {
    RevealListRow(
        title = preset.name,
        icon = if (selected) PhosphorFill.CheckCircle else PhosphorRegular.SquaresFour,
        iconTint = if (selected) RevealTheme.colors.primary else RevealTheme.colors.mutedForeground,
        onClick = onClick,
        modifier = Modifier.testTag(LibraryTags.preset(preset.uuid)),
        trailing = if (preset.favourite) {
            { Icon(PhosphorFill.Star, contentDescription = null, tint = RevealTheme.colors.warning) }
        } else {
            null
        },
    )
}

private const val FULL = 1f
private const val PERCENT = 100f
private val SPEC = SliderSpec(min = 0f, max = 200f, neutral = 100f, step = 1f, fineStep = 1f, bipolar = false)
