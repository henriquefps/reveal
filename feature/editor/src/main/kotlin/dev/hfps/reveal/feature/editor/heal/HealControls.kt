package dev.hfps.reveal.feature.editor.heal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.components.slider.SliderSpec
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.tools.ModeHost
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.ValueFormat
import dev.hfps.reveal.pipeline.recipe.ValueKind

/**
 * The Heal mode's controls in place of the tool strip (docs/plan/ux.md "Crop, Masks and Heal are modes"; docs/adr/0023
 * "Editor"): the kind (Heal, Clone, Remove, Red eye), the size and the other settings of new spots and of the selected
 * one, Visualise spots, and the selected spot's New source and Delete. Portrait shows one setting's slider at a time
 * (chips choose it) so the photo keeps its room; the landscape/tablet column shows them all.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun HealControls(host: ModeHost, modifier: Modifier = Modifier) {
    val state = host.state.heal
    val actions = host.actions.heal
    val settings = state.settings
    val selected = HealEdits.find(host.state.edit.recipe, state.selected)
    Column(modifier.fillMaxWidth().padding(top = 8.dp).testTag(HealTags.CONTROLS)) {
        if (host.vertical) {
            WrapRow { for (kind in KINDS) KindChip(kind, settings.kind, actions) }
        } else {
            ChipRow { for (kind in KINDS) KindChip(kind, settings.kind, actions) }
        }
        val ai = state.aiRemove
        if (settings.kind == HealKind.REMOVE && ai != null) RemoveMethodChips(settings.method(ai), ai, actions)
        val shown = if (host.vertical) {
            HealSetting.entries.filter { it != HealSetting.DARKEN || settings.kind == HealKind.RED_EYE }
        } else {
            ChipRow {
                for (setting in HealSetting.entries) {
                    if (setting == HealSetting.DARKEN && settings.kind != HealKind.RED_EYE) continue
                    RevealChip(
                        label = stringResource(settingLabel(setting)),
                        selected = state.setting == setting,
                        onClick = { actions.setSetting(setting) },
                        modifier = Modifier.testTag(HealTags.setting(setting)),
                    )
                }
            }
            listOf(state.setting)
        }
        for (setting in shown) SettingSlider(setting, settings, actions)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RevealChip(
                label = stringResource(R.string.heal_visualise),
                selected = state.visualise,
                onClick = { actions.setVisualise(!state.visualise) },
                icon = PhosphorRegular.MagnifyingGlass,
                modifier = Modifier.testTag(HealTags.VISUALISE),
            )
            Spacer(Modifier.weight(1f))
            val count = host.state.edit.recipe.heals.size
            Text(
                text = pluralStringResource(R.plurals.heal_spot_count, count, count),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 8.dp).testTag(HealTags.COUNT),
            )
            if (selected != null && selected.hasSource) {
                RevealIconButton(
                    icon = PhosphorRegular.ArrowsClockwise,
                    contentDescription = stringResource(R.string.heal_new_source),
                    onClick = { actions.newSource(selected.id) },
                    modifier = Modifier.testTag(HealTags.NEW_SOURCE),
                )
            }
            if (selected != null) {
                RevealIconButton(
                    icon = PhosphorRegular.Trash,
                    contentDescription = stringResource(R.string.heal_delete),
                    onClick = { actions.delete(selected.id) },
                    modifier = Modifier.testTag(HealTags.DELETE),
                )
            }
        }
        if (state.visualise) {
            RevealSlider(
                label = stringResource(R.string.heal_visualise_strength),
                value = state.visualiseStrength,
                spec = PERCENT_SPEC,
                format = ::plain,
                onValueChange = actions::setVisualiseStrength,
                onReset = { actions.setVisualiseStrength(HealUiState.DEFAULT_VISUALISE) },
                testTag = HealTags.VISUALISE_STRENGTH,
            )
        }
        Status(state)
    }
}

/** Remove's AI model or the patch fill (docs/adr/0024), for new spots and the selected one. */
@Composable
private fun RemoveMethodChips(method: String, ai: String, actions: HealActions) {
    ChipRow {
        RevealChip(
            label = stringResource(R.string.heal_remove_ai),
            selected = method == ai,
            onClick = { actions.setRemoveMethod(ai) },
            icon = PhosphorRegular.Sparkle,
            modifier = Modifier.testTag(HealTags.REMOVE_AI),
        )
        RevealChip(
            label = stringResource(R.string.heal_remove_patch),
            selected = method != ai,
            onClick = { actions.setRemoveMethod(Heal.METHOD_PATCH) },
            modifier = Modifier.testTag(HealTags.REMOVE_PATCH),
        )
    }
}

/** Remove's progress and failures, the limit, or the hint. */
@Composable
private fun Status(state: HealUiState) {
    val colors = RevealTheme.colors
    when {
        state.pending.isNotEmpty() || state.picking -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (state.pending.isNotEmpty()) {
                Text(
                    stringResource(R.string.heal_removing),
                    style = RevealTheme.typography.body,
                    color = colors.mutedForeground,
                )
            }
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(HealTags.PROGRESS),
                color = colors.primary,
                trackColor = colors.muted,
            )
        }

        state.failed.isNotEmpty() -> Text(
            stringResource(R.string.heal_remove_failed),
            style = RevealTheme.typography.body,
            color = colors.destructive,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(HealTags.FAILED),
        )

        state.fellBack.isNotEmpty() -> Text(
            stringResource(R.string.heal_remove_fell_back),
            style = RevealTheme.typography.body,
            color = colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(HealTags.FELL_BACK),
        )

        state.full -> Text(
            stringResource(R.string.heal_full),
            style = RevealTheme.typography.body,
            color = colors.destructive,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        else -> Text(
            stringResource(R.string.heal_hint),
            style = RevealTheme.typography.body,
            color = colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SettingSlider(setting: HealSetting, settings: HealSettings, actions: HealActions) {
    when (setting) {
        HealSetting.SIZE -> RevealSlider(
            label = stringResource(R.string.heal_size),
            value = settings.size * PERCENT,
            spec = SIZE_SPEC,
            format = ::sizePercent,
            onValueChange = { actions.setSettings(settings.copy(size = it / PERCENT)) },
            onReset = {
                actions.setSettings(settings.copy(size = HealSettings.DEFAULT_SIZE))
                actions.commit()
            },
            testTag = HealTags.SIZE,
            onValueChangeFinished = actions::commit,
        )

        HealSetting.FEATHER -> RevealSlider(
            label = stringResource(R.string.heal_feather),
            value = settings.feather,
            spec = PERCENT_SPEC,
            format = ::plain,
            onValueChange = { actions.setSettings(settings.copy(feather = it)) },
            onReset = {
                actions.setSettings(settings.copy(feather = HealSettings.DEFAULT_FEATHER))
                actions.commit()
            },
            testTag = HealTags.FEATHER,
            onValueChangeFinished = actions::commit,
        )

        HealSetting.OPACITY -> RevealSlider(
            label = stringResource(R.string.heal_opacity),
            value = settings.opacity,
            spec = OPACITY_SPEC,
            format = ::plain,
            onValueChange = { actions.setSettings(settings.copy(opacity = it)) },
            onReset = {
                actions.setSettings(settings.copy(opacity = HealSettings.FULL))
                actions.commit()
            },
            testTag = HealTags.OPACITY,
            onValueChangeFinished = actions::commit,
        )

        HealSetting.DARKEN -> RevealSlider(
            label = stringResource(R.string.heal_darken),
            value = settings.darken,
            spec = PERCENT_SPEC,
            format = ::plain,
            onValueChange = { actions.setSettings(settings.copy(darken = it)) },
            onReset = {
                actions.setSettings(settings.copy(darken = HealSettings.DEFAULT_DARKEN))
                actions.commit()
            },
            testTag = HealTags.DARKEN,
            onValueChangeFinished = actions::commit,
        )
    }
}

@Composable
private fun KindChip(kind: HealKind, current: HealKind, actions: HealActions) {
    RevealChip(
        label = stringResource(kindLabel(kind)),
        selected = current == kind,
        onClick = { actions.setKind(kind) },
        icon = kindIcon(kind),
        modifier = Modifier.testTag(HealTags.kind(kind)),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.width(1.dp).size(1.dp))
    }
}

private val KINDS = listOf(HealKind.HEAL, HealKind.CLONE, HealKind.REMOVE, HealKind.RED_EYE)

internal fun kindLabel(kind: HealKind): Int = when (kind) {
    HealKind.CLONE -> R.string.heal_kind_clone
    HealKind.REMOVE -> R.string.heal_kind_remove
    HealKind.RED_EYE -> R.string.heal_kind_red_eye
    else -> R.string.heal_kind_heal
}

private fun kindIcon(kind: HealKind) = when (kind) {
    HealKind.CLONE -> PhosphorRegular.Copy
    HealKind.REMOVE -> PhosphorRegular.Eraser
    HealKind.RED_EYE -> PhosphorRegular.Eye
    else -> PhosphorRegular.Bandaids
}

private fun settingLabel(setting: HealSetting): Int = when (setting) {
    HealSetting.SIZE -> R.string.heal_size
    HealSetting.FEATHER -> R.string.heal_feather
    HealSetting.OPACITY -> R.string.heal_opacity
    HealSetting.DARKEN -> R.string.heal_darken
}

private fun plain(v: Float): String = ValueFormat.format(ValueKind.INT, v)

private fun sizePercent(v: Float): String = ValueFormat.format(ValueKind.DECIMAL1, v) + " %"

private const val PERCENT = 100f
private val PERCENT_SPEC = SliderSpec(0f, 100f, 0f, 1f, 1f, bipolar = false)
private val OPACITY_SPEC = SliderSpec(1f, 100f, 100f, 1f, 1f, bipolar = false)
private val SIZE_SPEC = SliderSpec(
    HealSettings.MIN_SIZE * PERCENT,
    HealSettings.MAX_SIZE * PERCENT,
    HealSettings.DEFAULT_SIZE * PERCENT,
    0.1f,
    0.1f,
    bipolar = false,
)

/** Test tags of the Heal mode. */
object HealTags {
    const val CONTROLS = "heal_controls"
    const val VISUALISE = "heal_visualise"
    const val VISUALISE_STRENGTH = "heal_visualise_strength"
    const val COUNT = "heal_count"
    const val NEW_SOURCE = "heal_new_source"
    const val DELETE = "heal_delete"
    const val PROGRESS = "heal_progress"
    const val FAILED = "heal_failed"
    const val FELL_BACK = "heal_fell_back"
    const val REMOVE_AI = "heal_remove_ai"
    const val REMOVE_PATCH = "heal_remove_patch"
    const val SIZE = "heal_size"
    const val FEATHER = "heal_feather"
    const val OPACITY = "heal_opacity"
    const val DARKEN = "heal_darken"
    const val PHOTO_LAYER = "heal_photo_layer"
    const val OVERLAY = "heal_overlay"

    fun kind(kind: HealKind): String = "heal_kind_${kind.key}"

    fun setting(setting: HealSetting): String = "heal_setting_${setting.name.lowercase()}"
}
