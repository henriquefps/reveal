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
import dev.hfps.reveal.pipeline.recipe.Recipe
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
    /** The title's reset when narrower than a whole group (the tone curve, the colour mixer); wins over [group]. */
    val reset: ((Recipe) -> Recipe)? = null,
    /** Opening it snaps the portrait panel to its tall position (curves, mixers, wheels need the room). */
    val expand: Boolean = false,
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
    val value = RecipeControls.get(edit.recipe, id, edit.asShot)
    val ready = host.state.ready
    // Stable callbacks (keyed by what they capture) so a drag of one slider does not recompose the others.
    val actions = host.actions
    val focus = host.focus
    val format = remember(range) { range::format }
    val onChange = remember(actions, id) { { v: Float -> actions.onControl(id, v) } }
    val onReset = remember(actions, id) { { actions.onResetControl(id) } }
    val onDrag = remember(focus, id) { { dragging: Boolean -> focus.dragging = if (dragging) id else null } }
    FocusRow(focus, id) {
        RevealSlider(
            label = stringResource(label),
            value = value,
            spec = spec,
            format = format,
            onValueChange = onChange,
            onReset = onReset,
            modifier = modifier,
            enabled = enabled && ready,
            trackBrush = trackBrush,
            testTag = EditorTags.control(id),
            onDragStateChange = onDrag,
            onValueChangeFinished = actions.onCommit,
        )
    }
}
