package dev.hfps.reveal.feature.editor.crop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.tools.ModeHost
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.recipe.Aspect
import dev.hfps.reveal.pipeline.recipe.AspectPreset
import dev.hfps.reveal.pipeline.recipe.Geometry

/**
 * The Crop mode's controls in place of the tool strip (docs/plan/ux.md): the Crop | Transform tabs; Crop shows the
 * straighten dial, the aspect chips (after Auto straighten) and the tool buttons: rotate left/right, flip horizontal/vertical, the horizon
 * line, lock, swap orientation, constrain to image and reset. Portrait lays the chips and buttons out in scrolling
 * rows; the landscape/tablet column wraps them.
 */
@Composable
fun CropControls(host: ModeHost, modifier: Modifier = Modifier) {
    val ready = host.state.phase as? EditorPhase.Ready ?: return
    val w = ready.width
    val h = ready.height
    val session = host.crop
    val g = host.state.edit.recipe.geometry
    val set = host.actions.onGeometry
    var dialStart by remember { mutableStateOf<Geometry?>(null) }
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Tabs(session)
        if (session.tab == CropTab.TRANSFORM) {
            TransformControls(host)
            return@Column
        }
        StraightenDial(
            value = CropEditing.seenAngle(g),
            onValueChange = { seen ->
                val start = dialStart ?: g
                set(CropEditing.straighten(start, CropEditing.recipeAngle(start, seen), w, h))
            },
            onDragStart = {
                dialStart = g
                session.grid = CropGrid.FINE
            },
            onDragEnd = {
                dialStart = null
                session.grid = CropGrid.NONE
            },
            onReset = { set(CropEditing.straighten(g, 0f, w, h)) },
        )
        AspectChips(g, w, h, session, set, host.vertical, host.actions.onAutoStraighten)
        ToolButtons(g, w, h, session, set, host.vertical)
    }
    if (session.customAspect) {
        CustomAspectSheet(
            initial = g.aspect.takeIf { it.preset == AspectPreset.CUSTOM },
            onDismiss = { session.customAspect = false },
            onApply = { cw, ch ->
                session.customAspect = false
                set(CropEditing.withAspect(g, Aspect(AspectPreset.CUSTOM, cw, ch, locked = true), w, h))
            },
        )
    }
}

/** Crop | Transform. */
@Composable
private fun Tabs(session: CropSession) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RevealChip(
            label = stringResource(R.string.editor_crop_tab_crop),
            selected = session.tab == CropTab.CROP,
            onClick = {
                session.tab = CropTab.CROP
                session.guideTool = false
            },
            icon = PhosphorRegular.Crop,
            modifier = Modifier.testTag(EditorTags.CROP_TAB_CROP),
        )
        RevealChip(
            label = stringResource(R.string.editor_crop_tab_transform),
            selected = session.tab == CropTab.TRANSFORM,
            onClick = {
                session.tab = CropTab.TRANSFORM
                session.horizonTool = false
            },
            icon = PhosphorRegular.Perspective,
            modifier = Modifier.testTag(EditorTags.CROP_TAB_TRANSFORM),
        )
    }
}

/** One row scrolling sideways (portrait), or wrapping into lines in the side column ([vertical]). */
private fun Modifier.cropRow(vertical: Boolean, scroll: ScrollState): Modifier = this
    .fillMaxWidth()
    .then(if (vertical) Modifier else Modifier.horizontalScroll(scroll))
    .padding(horizontal = 8.dp)

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList")
@Composable
private fun AspectChips(
    g: Geometry,
    w: Int,
    h: Int,
    session: CropSession,
    set: (Geometry) -> Unit,
    vertical: Boolean,
    onAutoStraighten: () -> Unit,
) = FlowRow(
    Modifier.cropRow(vertical, rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
) {
    // Auto-straighten (docs/adr/0015): the dial's angle from the photo's own lines.
    RevealChip(
        label = stringResource(R.string.editor_crop_auto),
        selected = false,
        onClick = onAutoStraighten,
        icon = PhosphorRegular.MagicWand,
        modifier = Modifier.testTag(EditorTags.CROP_AUTO),
    )
    for (preset in AspectPreset.entries) {
        RevealChip(
            label = stringResource(preset.label()),
            selected = g.aspect.preset == preset,
            onClick = {
                if (preset == AspectPreset.CUSTOM) {
                    session.customAspect = true
                } else {
                    set(CropEditing.withAspect(g, Aspect(preset, locked = preset != AspectPreset.FREE), w, h))
                }
            },
            modifier = Modifier.testTag(EditorTags.aspect(preset)),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun ToolButtons(
    g: Geometry,
    w: Int,
    h: Int,
    session: CropSession,
    set: (Geometry) -> Unit,
    vertical: Boolean,
) = FlowRow(
    Modifier.cropRow(vertical, rememberScrollState()),
) {
    val locked = g.aspect.locked || g.aspect.preset != AspectPreset.FREE
    RevealIconButton(
        icon = PhosphorRegular.ArrowArcLeft,
        contentDescription = stringResource(R.string.editor_crop_rotate_left),
        onClick = { set(CropEditing.rotate90(g, clockwise = false)) },
        modifier = Modifier.testTag(EditorTags.CROP_ROTATE_LEFT),
    )
    RevealIconButton(
        icon = PhosphorRegular.ArrowArcRight,
        contentDescription = stringResource(R.string.editor_crop_rotate_right),
        onClick = { set(CropEditing.rotate90(g, clockwise = true)) },
        modifier = Modifier.testTag(EditorTags.CROP_ROTATE_RIGHT),
    )
    RevealIconButton(
        icon = PhosphorRegular.FlipHorizontal,
        contentDescription = stringResource(R.string.editor_crop_flip_h),
        onClick = { set(CropEditing.flip(g, horizontal = true)) },
        modifier = Modifier.testTag(EditorTags.CROP_FLIP_H),
    )
    RevealIconButton(
        icon = PhosphorRegular.FlipVertical,
        contentDescription = stringResource(R.string.editor_crop_flip_v),
        onClick = { set(CropEditing.flip(g, horizontal = false)) },
        modifier = Modifier.testTag(EditorTags.CROP_FLIP_V),
    )
    RevealIconButton(
        icon = if (session.horizonTool) PhosphorFill.LineSegment else PhosphorRegular.LineSegment,
        contentDescription = stringResource(R.string.editor_crop_horizon),
        onClick = { session.horizonTool = !session.horizonTool },
        active = session.horizonTool,
        modifier = Modifier.testTag(EditorTags.CROP_HORIZON),
    )
    RevealIconButton(
        icon = if (locked) PhosphorFill.LockSimple else PhosphorRegular.LockSimpleOpen,
        contentDescription = stringResource(if (locked) R.string.editor_crop_unlock else R.string.editor_crop_lock),
        onClick = { set(CropEditing.setLocked(g, !locked)) },
        active = locked,
        modifier = Modifier.testTag(EditorTags.CROP_LOCK),
    )
    RevealIconButton(
        icon = PhosphorRegular.Swap,
        contentDescription = stringResource(R.string.editor_crop_swap),
        onClick = { set(CropEditing.swapOrientation(g, w, h)) },
        modifier = Modifier.testTag(EditorTags.CROP_SWAP),
    )
    RevealIconButton(
        icon = if (g.constrain) PhosphorFill.FrameCorners else PhosphorRegular.FrameCorners,
        contentDescription = stringResource(R.string.editor_crop_constrain),
        onClick = { set(CropEditing.setConstrain(g, !g.constrain, w, h)) },
        active = g.constrain,
        modifier = Modifier.testTag(EditorTags.CROP_CONSTRAIN),
    )
    RevealIconButton(
        icon = PhosphorRegular.ArrowCounterClockwise,
        contentDescription = stringResource(R.string.editor_crop_reset),
        onClick = { set(CropEditing.reset(g)) },
        modifier = Modifier.testTag(EditorTags.CROP_RESET),
    )
}

/** Width and height steppers (1..99) for the custom aspect, then Apply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomAspectSheet(initial: Aspect?, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var cw by remember { mutableIntStateOf(initial?.w ?: DEFAULT_W) }
    var ch by remember { mutableIntStateOf(initial?.h ?: DEFAULT_H) }
    RevealBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.editor_crop_custom_title),
                style = RevealTheme.typography.title,
                color = RevealTheme.colors.foreground,
            )
            Stepper(stringResource(R.string.editor_crop_custom_width), cw) { cw = it }
            Stepper(stringResource(R.string.editor_crop_custom_height), ch) { ch = it }
            Spacer(Modifier.padding(4.dp))
            PrimaryButton(
                text = stringResource(R.string.editor_crop_custom_apply),
                onClick = { onApply(cw, ch) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.foreground,
            modifier = Modifier.weight(1f),
        )
        RevealIconButton(
            icon = PhosphorRegular.Minus,
            contentDescription = stringResource(R.string.editor_crop_custom_less, label),
            onClick = { onChange((value - 1).coerceAtLeast(1)) },
        )
        Text(
            value.toString(),
            style = RevealTheme.typography.value,
            color = RevealTheme.colors.foreground,
            modifier = Modifier.widthIn(min = 32.dp),
        )
        RevealIconButton(
            icon = PhosphorRegular.Plus,
            contentDescription = stringResource(R.string.editor_crop_custom_more, label),
            onClick = { onChange((value + 1).coerceAtMost(MAX_SIDE)) },
        )
        Spacer(Modifier.width(0.dp))
    }
}

private fun AspectPreset.label(): Int = when (this) {
    AspectPreset.FREE -> R.string.editor_crop_aspect_free
    AspectPreset.ORIGINAL -> R.string.editor_crop_aspect_original
    AspectPreset.SQUARE -> R.string.editor_crop_aspect_1_1
    AspectPreset.R4_5 -> R.string.editor_crop_aspect_4_5
    AspectPreset.R5_4 -> R.string.editor_crop_aspect_5_4
    AspectPreset.R3_2 -> R.string.editor_crop_aspect_3_2
    AspectPreset.R2_3 -> R.string.editor_crop_aspect_2_3
    AspectPreset.R16_9 -> R.string.editor_crop_aspect_16_9
    AspectPreset.R9_16 -> R.string.editor_crop_aspect_9_16
    AspectPreset.CUSTOM -> R.string.editor_crop_aspect_custom
}

private const val DEFAULT_W = 7
private const val DEFAULT_H = 5
private const val MAX_SIDE = 99
