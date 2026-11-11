package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.OkLabColors
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.TargetedTarget
import dev.hfps.reveal.pipeline.HistoryLabels
import dev.hfps.reveal.pipeline.cpu.HueBands
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.MixerProperty
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeGroup

/** The Colour mixer sub-panel (HSL, docs/adr/0013); its reset clears the mixer only. */
val ColourMixerSubPanel = SubPanel(
    key = "colour_mixer",
    title = R.string.editor_colour_mixer,
    group = RecipeGroup.COLOUR,
    reset = { r: Recipe -> r.copy(colour = r.colour.copy(mixer = ColourMixer())) },
    expand = true,
) { host -> ColourMixerPanel(host) }

/** The black & white mix sub-panel: the grey mixer with Auto; its reset clears the grey mixer only. */
val GreyMixerSubPanel = SubPanel(
    key = "grey_mixer",
    title = R.string.editor_bw_mix,
    group = RecipeGroup.COLOUR,
    reset = { r: Recipe -> r.copy(colour = r.colour.copy(greyMixer = BandValues())) },
    expand = true,
) { host -> GreyMixerPanel(host) }

/** The OkLab hue (degrees) of [band]'s centre: what its swatch and slider tracks show. */
internal fun bandHue(band: ColourBand): Float = HueBands.CENTRES_DEGREES[band.ordinal].toFloat()

/**
 * The colour mixer: the 8 band swatches (a dot marks edited bands), hue / saturation / luminance of the selected band
 * on gradient tracks, and the targeted adjustment (drag on the photo; the hint names the band it moves, and the panel
 * follows it).
 */
@Composable
fun ColourMixerPanel(host: PanelHost, modifier: Modifier = Modifier) {
    val mixer = host.state.edit.recipe.colour.mixer
    var band by rememberSaveable { mutableStateOf(ColourBand.RED) }
    val lead = host.state.edit.targetedBand
    LaunchedEffect(lead) { if (lead != null) band = lead }
    val edited = ColourBand.entries.filter { b ->
        mixer.hue[b] != 0f || mixer.saturation[b] != 0f || mixer.luminance[b] != 0f
    }.toSet()
    val hue = bandHue(band)
    Column(modifier) {
        FocusRow(host.focus, null) {
            Column {
                TargetedRow(host, mixer = true)
                BandSwatches(selected = band, edited = edited, onSelect = { band = it })
            }
        }
        val step = FULL_TURN / HueBands.COUNT
        ControlSlider(
            host,
            ControlId.mixer(MixerProperty.HUE, band),
            R.string.editor_mixer_hue,
            trackBrush = remember(band) { OkLabColors.hueGradient(listOf(hue - step, hue, hue + step)) },
        )
        ControlSlider(
            host,
            ControlId.mixer(MixerProperty.SATURATION, band),
            R.string.editor_mixer_saturation,
            trackBrush = remember(band) { OkLabColors.saturationGradient(hue) },
        )
        ControlSlider(
            host,
            ControlId.mixer(MixerProperty.LUMINANCE, band),
            R.string.editor_mixer_luminance,
            trackBrush = remember(band) { OkLabColors.luminanceGradient(hue) },
        )
    }
}

/** The black & white mix: Auto, the targeted adjustment, and one grey slider per source colour. */
@Composable
fun GreyMixerPanel(host: PanelHost, modifier: Modifier = Modifier) {
    Column(modifier) {
        FocusRow(host.focus, null) { TargetedRow(host, mixer = false) }
        ColourBand.entries.forEach { band ->
            ControlSlider(
                host,
                ControlId.grey(band),
                bandLabel(band),
                trackBrush = remember(band) { OkLabColors.luminanceGradient(bandHue(band)) },
            )
        }
    }
}

/** Localised band name for a slider label. */
private fun bandLabel(band: ColourBand): Int = HistoryLabels.bandName(band)

/**
 * The targeted adjustment toggle (and, for the colour mixer, which property it changes); the grey mixer's row also
 * holds Auto. Leaving the panel turns the targeted adjustment off.
 */
@Suppress("LongMethod")
@Composable
private fun TargetedRow(host: PanelHost, mixer: Boolean) {
    val actions = host.actions
    val targeted = host.state.view.targeted
    val on = targeted != null && (targeted == TargetedTarget.GREY) != mixer
    var property by rememberSaveable { mutableStateOf(TargetedTarget.SATURATION) }
    val currentOn by rememberUpdatedState(on)
    DisposableEffect(Unit) { onDispose { if (currentOn) actions.onTargeted(null) } }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(stringResource(R.string.editor_targeted), Modifier.weight(1f))
            if (!mixer) {
                GhostButton(
                    text = stringResource(R.string.editor_auto_mix),
                    onClick = actions.onAutoMix,
                    enabled = host.state.edit.autoReady,
                    modifier = Modifier.testTag(EditorTags.AUTO_MIX),
                )
            }
            RevealIconButton(
                icon = if (on) PhosphorFill.CrosshairSimple else PhosphorRegular.CrosshairSimple,
                contentDescription = stringResource(R.string.editor_targeted_toggle),
                onClick = {
                    actions.onTargeted(
                        if (on) {
                            null
                        } else if (mixer) {
                            property
                        } else {
                            TargetedTarget.GREY
                        },
                    )
                },
                enabled = host.state.ready && host.state.edit.autoReady,
                active = on,
                modifier = Modifier.testTag(EditorTags.TARGETED),
            )
        }
        if (mixer && on) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    TargetedTarget.HUE to R.string.editor_mixer_hue,
                    TargetedTarget.SATURATION to R.string.editor_mixer_saturation,
                    TargetedTarget.LUMINANCE to R.string.editor_mixer_luminance,
                ).forEach { (t, label) ->
                    RevealChip(
                        label = stringResource(label),
                        selected = targeted == t,
                        onClick = {
                            property = t
                            actions.onTargeted(t)
                        },
                        modifier = Modifier.testTag(EditorTags.targetedTarget(t)),
                    )
                }
            }
        }
    }
}

/** The 8 band swatches: a filled circle in the band's colour, a ring when selected, a dot when the band is edited. */
@Composable
private fun BandSwatches(selected: ColourBand, edited: Set<ColourBand>, onSelect: (ColourBand) -> Unit) {
    val colors = RevealTheme.colors
    // Eight equal cells across the panel (about 46 dp each on a phone): every swatch visible, no scrolling.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        ColourBand.entries.forEach { band ->
            val name = stringResource(HistoryLabels.bandName(band))
            val swatch = remember(band) { OkLabColors.color(OkLabColors.SWATCH_L, OkLabColors.SWATCH_C, bandHue(band)) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = SWATCH_TARGET)
                    .selectable(selected = band == selected, role = Role.RadioButton, onClick = { onSelect(band) })
                    .semantics { contentDescription = name }
                    .testTag(EditorTags.band(band)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(SWATCH)
                        .border(
                            BorderStroke(2.dp, if (band == selected) colors.primary else colors.hairline),
                            CircleShape,
                        )
                        .padding(4.dp)
                        .background(swatch, CircleShape),
                )
                if (band in edited) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.dp)
                            .size(4.dp)
                            .background(colors.foreground, CircleShape),
                    )
                }
            }
        }
    }
}

private const val FULL_TURN = 360f
private val SWATCH = 32.dp
private val SWATCH_TARGET = 48.dp
