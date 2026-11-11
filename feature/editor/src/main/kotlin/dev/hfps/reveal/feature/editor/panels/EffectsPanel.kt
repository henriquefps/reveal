package dev.hfps.reveal.feature.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.recipe.ControlId

/**
 * The Effects panel (docs/plan/product.md, "Effects"; docs/adr/0014): texture, clarity and dehaze; the post-crop
 * vignette (amount, midpoint, roundness, feather, highlights); grain (amount, size, roughness). A shaping slider is
 * enabled once its amount is on.
 */
@Composable
fun EffectsPanel(host: PanelHost, modifier: Modifier = Modifier) {
    val effects = host.state.edit.recipe.effects
    Column(modifier) {
        Section(host, R.string.editor_effects_presence)
        ControlSlider(host, ControlId.TEXTURE, R.string.editor_effects_texture)
        ControlSlider(host, ControlId.CLARITY, R.string.editor_effects_clarity)
        ControlSlider(host, ControlId.DEHAZE, R.string.editor_effects_dehaze)
        Section(host, R.string.editor_effects_vignette)
        val vignette = effects.vignetteAmount != 0f
        ControlSlider(host, ControlId.VIGNETTE_AMOUNT, R.string.editor_effects_amount)
        ControlSlider(host, ControlId.VIGNETTE_MIDPOINT, R.string.editor_effects_midpoint, enabled = vignette)
        ControlSlider(host, ControlId.VIGNETTE_ROUNDNESS, R.string.editor_effects_roundness, enabled = vignette)
        ControlSlider(host, ControlId.VIGNETTE_FEATHER, R.string.editor_effects_feather, enabled = vignette)
        ControlSlider(host, ControlId.VIGNETTE_HIGHLIGHTS, R.string.editor_effects_highlights, enabled = vignette)
        Section(host, R.string.editor_effects_grain)
        val grain = effects.grainAmount > 0f
        ControlSlider(host, ControlId.GRAIN_AMOUNT, R.string.editor_effects_amount)
        ControlSlider(host, ControlId.GRAIN_SIZE, R.string.editor_effects_size, enabled = grain)
        ControlSlider(host, ControlId.GRAIN_ROUGHNESS, R.string.editor_effects_roughness, enabled = grain)
    }
}
