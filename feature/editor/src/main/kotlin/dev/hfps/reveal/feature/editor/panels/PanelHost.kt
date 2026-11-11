package dev.hfps.reveal.feature.editor.panels

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.components.slider.SliderSpec
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.feature.editor.EditorActions
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.edit.RecipeControls
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.ControlRange
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipeRanges

/**
 * Which slider is being dragged. While one is, every other row of the panel fades out and collapses, so the panel
 * shrinks to that slider and the photo gets the room (docs/plan/ux.md); it all comes back on release.
 */
@Stable
class SliderFocus {
    var dragging: ControlId? by mutableStateOf(null)

    /** True while any slider is being dragged. */
    val active: Boolean get() = dragging != null
}

/** A sub-panel (tone curve, colour mixer, profile): a row opens it, a back arrow (or back gesture) closes it. */
class SubPanel(
    val key: String,
    @param:StringRes val title: Int,
    /** The recipe group the title's reset clears, if any. */
    val group: RecipeGroup?,
    val content: @Composable (PanelHost) -> Unit,
)

/** The sub-panel stack of the open panel. */
@Stable
class PanelNavigator {
    private val stack = mutableStateListOf<SubPanel>()

    val top: SubPanel? get() = stack.lastOrNull()

    val depth: Int get() = stack.size

    fun push(panel: SubPanel) {
        stack.add(panel)
    }

    fun pop(): Boolean = stack.removeLastOrNull() != null

    fun clear() = stack.clear()
}

/** Everything a panel's rows need: the state, the actions, the drag focus and the sub-panel navigator. */
@Stable
class PanelHost(
    val state: EditorUiState,
    val actions: EditorActions,
    val focus: SliderFocus,
    val navigator: PanelNavigator,
)

/** A row that hides (fade + collapse) while a slider other than [id] is dragged; `null` = hides for any drag. */
@Composable
fun FocusRow(focus: SliderFocus, id: ControlId?, content: @Composable () -> Unit) {
    val dragging = focus.dragging
    AnimatedVisibility(
        visible = dragging == null || dragging == id,
        enter = fadeIn(RevealMotion.standard()) + expandVertically(RevealMotion.standard()),
        exit = fadeOut(RevealMotion.fast()) + shrinkVertically(RevealMotion.standard()),
    ) {
        content()
    }
}

/** The [SliderSpec] of a recipe control range. */
fun ControlRange.toSliderSpec(): SliderSpec = SliderSpec(min, max, neutral, step, fineStep, bipolar)

/**
 * A [RevealSlider] bound to the recipe control [id]: value via [RecipeControls], range/steps/format via
 * [RecipeRanges], per-frame changes to `onControl`, commit on release, double-tap reset.
 */
@Composable
fun ControlSlider(
    host: PanelHost,
    id: ControlId,
    @StringRes label: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackBrush: Brush? = null,
) {
    val range = RecipeRanges[id]
    val spec = remember(range) { range.toSliderSpec() }
    val edit = host.state.edit
    FocusRow(host.focus, id) {
        RevealSlider(
            label = stringResource(label),
            value = RecipeControls.get(edit.recipe, id, edit.asShot),
            spec = spec,
            format = range::format,
            onValueChange = { host.actions.onControl(id, it) },
            onReset = { host.actions.onResetControl(id) },
            modifier = modifier,
            enabled = enabled && host.state.ready,
            trackBrush = trackBrush,
            testTag = EditorTags.control(id),
            onDragStateChange = { host.focus.dragging = if (it) id else null },
            onValueChangeFinished = host.actions.onCommit,
        )
    }
}
