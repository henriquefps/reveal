package dev.hfps.reveal.feature.editor.crop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.panels.toSliderSpec
import dev.hfps.reveal.feature.editor.tools.ModeHost
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.OpticsControls
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.UprightMode

/**
 * The Crop mode's Transform tab (docs/plan/product.md, "Transform/perspective"; docs/adr/0015): the Upright modes,
 * then the vertical, horizontal, rotate, aspect, scale and offset sliders. Everything edits the working geometry (the
 * constrained crop refits as the sliders move); the mode's Done commits it as one step. Guided arms drawing 2-4 guides
 * on the photo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod")
@Composable
fun TransformControls(host: ModeHost, modifier: Modifier = Modifier) {
    val ready = host.state.phase as? EditorPhase.Ready ?: return
    val g = host.state.edit.recipe.geometry
    val t = g.transform
    val session = host.crop
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            Modifier
                .fillMaxWidth()
                .then(if (host.vertical) Modifier else Modifier.horizontalScrollRow())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (mode in UprightMode.entries) {
                RevealChip(
                    label = stringResource(mode.label()),
                    selected = if (mode ==
                        UprightMode.GUIDED
                    ) {
                        session.guideTool || t.upright == mode
                    } else {
                        t.upright == mode
                    },
                    onClick = {
                        if (mode == UprightMode.GUIDED) {
                            session.guideTool = !session.guideTool
                        } else {
                            session.guideTool = false
                            host.actions.onUpright(mode)
                        }
                    },
                    icon = if (mode == UprightMode.GUIDED) PhosphorRegular.LineSegments else null,
                    modifier = Modifier.testTag(EditorTags.upright(mode)),
                )
            }
        }
        if (host.state.edit.uprightFailed != null) {
            Hint(stringResource(R.string.editor_transform_no_lines), EditorTags.UPRIGHT_FAILED)
        }
        if (session.guideTool) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Hint(
                    stringResource(R.string.editor_transform_guides_hint, t.guides.size),
                    EditorTags.GUIDES_HINT,
                    Modifier.weight(1f),
                )
                RevealIconButton(
                    icon = PhosphorRegular.ArrowCounterClockwise,
                    contentDescription = stringResource(R.string.editor_transform_guides_clear),
                    onClick = host.actions.onClearGuides,
                    enabled = t.guides.isNotEmpty(),
                    modifier = Modifier.testTag(EditorTags.GUIDES_CLEAR),
                )
            }
        }
        var start by remember { mutableStateOf<Geometry?>(null) }
        for ((id, label) in SLIDERS) {
            val range = RecipeRanges[id]
            val spec = remember(range) { range.toSliderSpec() }
            RevealSlider(
                label = stringResource(label),
                value = OpticsControls.get(host.state.edit.recipe, id),
                spec = spec,
                format = range::format,
                onValueChange = { v ->
                    val from = start ?: g
                    host.actions.onGeometry(transformed(from, id, range.clamp(v), ready.width, ready.height))
                },
                onReset = {
                    host.actions.onGeometry(transformed(g, id, range.neutral, ready.width, ready.height))
                },
                enabled = host.state.ready,
                testTag = EditorTags.control(id),
                onDragStateChange = { dragging ->
                    start = if (dragging) g else null
                    session.grid = if (dragging) CropGrid.FINE else CropGrid.NONE
                },
            )
        }
    }
}

/** [from] with transform control [id] at [value], the crop refitted when constrained. */
private fun transformed(from: Geometry, id: ControlId, value: Float, width: Int, height: Int): Geometry {
    val t = OpticsControls.set(Recipe(geometry = from), id, value).geometry.transform
    return CropEditing.transform(from, t, width, height)
}

@Composable
private fun Hint(text: String, tag: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = RevealTheme.typography.body,
        color = RevealTheme.colors.mutedForeground,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
    )
}

@Composable
private fun Modifier.horizontalScrollRow(): Modifier = horizontalScroll(rememberScrollState())

private fun UprightMode.label(): Int = when (this) {
    UprightMode.OFF -> R.string.editor_upright_off
    UprightMode.AUTO -> R.string.editor_upright_auto
    UprightMode.LEVEL -> R.string.editor_upright_level
    UprightMode.VERTICAL -> R.string.editor_upright_vertical
    UprightMode.FULL -> R.string.editor_upright_full
    UprightMode.GUIDED -> R.string.editor_upright_guided
}

private val SLIDERS = listOf(
    ControlId.TRANSFORM_VERTICAL to R.string.editor_transform_vertical,
    ControlId.TRANSFORM_HORIZONTAL to R.string.editor_transform_horizontal,
    ControlId.TRANSFORM_ROTATE to R.string.editor_transform_rotate,
    ControlId.TRANSFORM_ASPECT to R.string.editor_transform_aspect,
    ControlId.TRANSFORM_SCALE to R.string.editor_transform_scale,
    ControlId.TRANSFORM_OFFSET_X to R.string.editor_transform_offset_x,
    ControlId.TRANSFORM_OFFSET_Y to R.string.editor_transform_offset_y,
)
