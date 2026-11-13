@file:Suppress("TooManyFunctions")

package dev.hfps.reveal.feature.editor.masks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.mask.AiMaskState
import dev.hfps.reveal.data.mask.AiMasks
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.components.slider.SliderSpec
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.OkLabColors
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.panels.FocusRow
import dev.hfps.reveal.feature.editor.panels.PanelHost
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.pipeline.gl.MaskOverlay
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.StrokePressure
import dev.hfps.reveal.pipeline.recipe.ValueFormat
import dev.hfps.reveal.pipeline.recipe.ValueKind
import kotlin.math.roundToInt

/**
 * The Masks panel (docs/plan/ux.md "Masks", docs/adr/0022 "M4/B (editor)"): the photo's masks as a row of coverage
 * thumbnails with "+", then the selected mask: its name, eye, overlay and ⋯ menu, its amount, its components (type,
 * how each combines, invert, delete; add / subtract / intersect another), the selected component's own controls
 * (brush, range, feather, subject taps) and the local adjustments grouped like the global panels.
 */
@Composable
fun MasksPanel(host: PanelHost, modifier: Modifier = Modifier) {
    val state = host.state.masks
    val actions = host.actions.masks
    val recipe = host.state.edit.recipe
    val mask = MaskEdits.find(recipe, state.selected)
    Column(modifier.testTag(MaskTags.PANEL)) {
        FocusRow(host.focus, null) { MaskStrip(recipe.masks, state, actions, host.state.ready) }
        if (state.full) FocusRow(host.focus, null) { Note(stringResource(R.string.masks_full), MaskTags.FULL) }
        when {
            recipe.masks.isEmpty() -> FocusRow(host.focus, null) {
                Column(Modifier.testTag(MaskTags.EMPTY)) {
                    Note(stringResource(R.string.masks_empty))
                    MaskChoices(state.aiKinds, onPick = { kind -> actions.create(kind) })
                }
            }

            mask == null -> FocusRow(host.focus, null) { Note(stringResource(R.string.masks_select)) }

            else -> SelectedMask(host, mask, state)
        }
    }
    val creating = state.creating
    if (creating != null) {
        MaskCreateSheet(
            target = creating,
            aiKinds = state.aiKinds,
            onPick = actions::create,
            onDismiss = { actions.openCreate(null) },
        )
    }
}

// ---------------------------------------------------------------------------------------------- the strip

/** The masks as thumbnails (a long press drags one to a new place) and the "+" that opens the chooser. */
@Composable
private fun MaskStrip(masks: List<Mask>, state: MasksUiState, actions: MaskActions, enabled: Boolean) {
    val density = LocalDensity.current
    val cell = with(density) { (CELL + CELL_GAP).toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(MaskTags.STRIP),
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
    ) {
        masks.forEachIndexed { index, mask ->
            val currentIndex by rememberUpdatedState(index)
            val isDragged = dragging == mask.id
            MaskCell(
                mask = mask,
                selected = mask.id == state.selected,
                thumbnail = state.thumbnails[mask.id],
                busy = mask.components.any { c ->
                    c is MaskComponent.Ai && state.ai[AiMasks.key(c)] == AiMaskState.COMPUTING
                },
                onClick = { actions.select(mask.id) },
                modifier = Modifier
                    .testTag(MaskTags.mask(index))
                    .graphicsLayer { translationX = if (isDragged) offset else 0f }
                    .pointerInput(mask.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = mask.id
                                offset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                offset += amount.x
                            },
                            onDragEnd = {
                                val to = currentIndex + (offset / cell).roundToInt()
                                if (to != currentIndex) actions.move(mask.id, to)
                                dragging = null
                                offset = 0f
                            },
                            onDragCancel = {
                                dragging = null
                                offset = 0f
                            },
                        )
                    },
            )
        }
        AddCell(enabled && masks.size < MaskEdits.MAX_MASKS) { actions.openCreate(CreateTarget.NewMask) }
    }
}

@Composable
private fun MaskCell(
    mask: Mask,
    selected: Boolean,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    Column(
        modifier = modifier
            .width(CELL)
            .clip(RevealTheme.shapes.control)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(CELL)
                .clip(RevealTheme.shapes.control)
                .background(colors.muted)
                .border(
                    BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.hairline),
                    RevealTheme.shapes.control,
                )
                .alpha(if (mask.enabled) 1f else DISABLED_ALPHA),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(CELL),
                )
            } else {
                val first = mask.components.firstOrNull()
                Icon(
                    imageVector = first?.let(MaskLabels::icon) ?: PhosphorRegular.Selection,
                    contentDescription = null,
                    tint = colors.mutedForeground,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (!mask.enabled) {
                Icon(
                    PhosphorRegular.EyeSlash,
                    contentDescription = null,
                    tint = colors.foreground,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(14.dp),
                )
            }
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(4.dp),
                    color = colors.primary,
                    trackColor = colors.muted,
                )
            }
        }
        Text(
            text = mask.name,
            style = RevealTheme.typography.eyebrow.copy(letterSpacing = RevealTheme.typography.ui.letterSpacing),
            color = if (selected) colors.foreground else colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun AddCell(enabled: Boolean, onClick: () -> Unit) {
    val colors = RevealTheme.colors
    val description = stringResource(R.string.masks_add)
    Box(
        modifier = Modifier
            .size(CELL)
            .clip(RevealTheme.shapes.control)
            .border(BorderStroke(1.dp, colors.input), RevealTheme.shapes.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(MaskTags.ADD),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            PhosphorRegular.Plus,
            contentDescription = null,
            tint = if (enabled) colors.primary else colors.mutedForeground,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------- the selected mask

@Suppress("LongMethod")
@Composable
private fun SelectedMask(host: PanelHost, mask: Mask, state: MasksUiState) {
    val actions = host.actions.masks
    var options by remember { mutableStateOf(false) }
    FocusRow(host.focus, null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp)
                .testTag(MaskTags.HEADER),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = mask.name,
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { options = true }
                    .testTag(MaskTags.NAME),
            )
            RevealIconButton(
                icon = if (mask.enabled) PhosphorRegular.Eye else PhosphorRegular.EyeSlash,
                contentDescription = stringResource(if (mask.enabled) R.string.masks_hide else R.string.masks_show),
                onClick = { actions.setEnabled(mask.id, !mask.enabled) },
                modifier = Modifier.testTag(MaskTags.EYE),
            )
            RevealIconButton(
                icon = if (state.overlay.shown) PhosphorFill.SelectionAll else PhosphorRegular.SelectionAll,
                contentDescription = stringResource(R.string.masks_overlay),
                onClick = { actions.toggleOverlay() },
                active = state.overlay.shown,
                modifier = Modifier.testTag(MaskTags.OVERLAY),
            )
            RevealIconButton(
                icon = PhosphorRegular.DotsThree,
                contentDescription = stringResource(R.string.masks_options, mask.name),
                onClick = { options = true },
                modifier = Modifier.testTag(MaskTags.MENU),
            )
        }
    }
    if (state.overlay.shown) FocusRow(host.focus, null) { OverlayOptions(state.overlay, actions) }
    MaskSlider(
        host = host,
        key = MaskTags.AMOUNT,
        label = stringResource(R.string.masks_amount),
        value = mask.amount,
        spec = AMOUNT_SPEC,
        format = { ValueFormat.format(ValueKind.PERCENT, it) },
        onChange = { actions.setAmount(mask.id, it) },
        onReset = {
            actions.setAmount(mask.id, AMOUNT_SPEC.max)
            actions.commit()
        },
    )
    FocusRow(host.focus, null) { Components(mask, state, actions) }
    mask.components.getOrNull(state.component ?: -1)?.let { component ->
        ComponentControls(host, mask, checkNotNull(state.component), component, state)
    }
    for (group in AdjustmentGroup.entries) {
        FocusRow(host.focus, null) {
            Eyebrow(stringResource(group.title), Modifier.padding(start = 16.dp, top = 12.dp))
        }
        for (adjustment in group.adjustments) AdjustmentSlider(host, mask, adjustment)
    }
    FocusRow(host.focus, null) {
        GhostButton(
            text = stringResource(R.string.masks_reset_adjustments),
            onClick = { actions.resetAdjustments(mask.id) },
            enabled = !mask.adjustments.isNeutral || mask.amount != AMOUNT_SPEC.max,
            icon = PhosphorRegular.ArrowCounterClockwise,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .testTag(MaskTags.RESET),
        )
    }
    if (options) {
        MaskOptionsSheet(
            mask = mask,
            index = host.state.edit.recipe.masks.indexOfFirst { it.id == mask.id },
            count = host.state.edit.recipe.masks.size,
            actions = actions,
            onDismiss = { options = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayOptions(overlay: MaskOverlayOptions, actions: MaskActions) {
    val colors = RevealTheme.colors
    Column(Modifier.testTag(MaskTags.OVERLAY_OPTIONS)) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (rgb in MaskOverlayOptions.COLOURS) {
                val chosen = overlay.mode == MaskOverlay.Mode.COLOUR && overlay.colour == rgb
                val description = stringResource(R.string.masks_overlay_colour)
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(swatch(rgb))
                        .border(
                            BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) colors.primary else colors.hairline),
                            CircleShape,
                        )
                        .clickable(role = Role.RadioButton) {
                            actions.setOverlay(overlay.copy(mode = MaskOverlay.Mode.COLOUR, colour = rgb))
                        }
                        .semantics {
                            contentDescription = description
                            selected = chosen
                        },
                )
            }
            RevealChip(
                label = stringResource(R.string.masks_overlay_grey),
                selected = overlay.mode == MaskOverlay.Mode.GREYSCALE,
                onClick = {
                    val grey = overlay.mode != MaskOverlay.Mode.GREYSCALE
                    actions.setOverlay(
                        overlay.copy(mode = if (grey) MaskOverlay.Mode.GREYSCALE else MaskOverlay.Mode.COLOUR),
                    )
                },
                modifier = Modifier.testTag(MaskTags.OVERLAY_GREY),
            )
        }
        RevealSlider(
            label = stringResource(R.string.masks_overlay_opacity),
            value = overlay.opacity * PERCENT,
            spec = OPACITY_SPEC,
            format = { ValueFormat.format(ValueKind.PERCENT, it) },
            onValueChange = { actions.setOverlay(overlay.copy(opacity = it / PERCENT)) },
            onReset = { actions.setOverlay(overlay.copy(opacity = MaskOverlay.DEFAULT_OPACITY)) },
            enabled = overlay.mode == MaskOverlay.Mode.COLOUR,
        )
    }
}

/** An overlay colour as a swatch (the colour is the user's setting, not a theme colour). */
private fun swatch(rgb: Int): Color = Color(OPAQUE or rgb)

// ---------------------------------------------------------------------------------------------- components

@Suppress("LongMethod")
@Composable
private fun Components(mask: Mask, state: MasksUiState, actions: MaskActions) {
    val colors = RevealTheme.colors
    Column {
        Eyebrow(stringResource(R.string.masks_components), Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        mask.components.forEachIndexed { index, c ->
            val selected = index == state.component
            val ai = (c as? MaskComponent.Ai)?.let { state.ai[AiMasks.key(it)] }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable(role = Role.Tab) { actions.selectComponent(index) }
                    .semantics { this.selected = selected }
                    .padding(start = 16.dp, end = 4.dp)
                    .testTag(MaskTags.component(index)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index > 0) {
                    Icon(
                        MaskLabels.opIcon(c.op),
                        contentDescription = stringResource(MaskLabels.op(c.op)),
                        tint = colors.mutedForeground,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    MaskLabels.icon(c),
                    contentDescription = null,
                    tint = colors.foreground,
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = stringResource(MaskLabels.component(c)),
                        style = RevealTheme.typography.ui,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    aiStatus(ai, c)?.let { status ->
                        Text(
                            text = stringResource(status),
                            style = RevealTheme.typography.eyebrow.copy(
                                letterSpacing = RevealTheme.typography.ui.letterSpacing,
                            ),
                            color = if (ai == AiMaskState.FAILED) colors.destructive else colors.mutedForeground,
                            modifier = Modifier.testTag(MaskTags.AI_STATUS),
                        )
                    }
                }
                if (ai == AiMaskState.FAILED) {
                    RevealIconButton(
                        icon = PhosphorRegular.ArrowsClockwise,
                        contentDescription = stringResource(R.string.masks_ai_retry),
                        onClick = { actions.retryAi() },
                        modifier = Modifier.testTag(MaskTags.AI_RETRY),
                    )
                }
                RevealIconButton(
                    icon = PhosphorRegular.SelectionInverse,
                    contentDescription = stringResource(R.string.masks_component_invert),
                    onClick = { actions.toggleComponentInvert(index) },
                    active = c.invert,
                    modifier = Modifier.testTag(MaskTags.componentInvert(index)),
                )
                RevealIconButton(
                    icon = PhosphorRegular.Trash,
                    contentDescription = stringResource(R.string.masks_component_delete),
                    onClick = { actions.removeComponent(index) },
                    enabled = mask.components.size > 1,
                    modifier = Modifier.testTag(MaskTags.componentDelete(index)),
                )
            }
            if (ai == AiMaskState.COMPUTING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = colors.primary,
                    trackColor = colors.muted,
                )
            }
        }
        val index = state.component
        if (index != null && index > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val current = mask.components.getOrNull(index)?.op
                for (op in MaskOp.entries) {
                    RevealChip(
                        label = stringResource(MaskLabels.op(op)),
                        selected = op == current,
                        onClick = { actions.setComponentOp(index, op) },
                        icon = MaskLabels.opIcon(op),
                        modifier = Modifier.testTag(MaskTags.componentOp(index, op)),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            for (op in MaskOp.entries) {
                GhostButton(
                    text = stringResource(MaskLabels.combine(op)),
                    onClick = { actions.openCreate(CreateTarget.AddTo(op)) },
                    icon = MaskLabels.opIcon(op),
                    modifier = Modifier.testTag(MaskTags.combine(op)),
                )
            }
        }
    }
}

private fun aiStatus(state: AiMaskState?, c: MaskComponent): Int? = when {
    c is MaskComponent.Unknown -> R.string.masks_ai_unavailable
    c !is MaskComponent.Ai -> null
    AiMasksKinds.of(c) == null -> R.string.masks_ai_unavailable
    state == AiMaskState.COMPUTING -> R.string.masks_ai_computing
    state == AiMaskState.FAILED -> R.string.masks_ai_failed
    state == AiMaskState.UNAVAILABLE -> R.string.masks_ai_unavailable
    else -> null
}

// ---------------------------------------------------------------------------------------------- component controls

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ComponentControls(host: PanelHost, mask: Mask, index: Int, c: MaskComponent, state: MasksUiState) {
    val actions = host.actions.masks
    fun edit(change: (MaskComponent) -> MaskComponent) = actions.editComponent(index, change)
    when (c) {
        is MaskComponent.Brush -> BrushControls(host, state.brush)

        is MaskComponent.Linear -> MaskSlider(
            host,
            MaskTags.FEATHER,
            stringResource(R.string.masks_feather),
            c.feather,
            PERCENT_SPEC,
            ::percent,
            onChange = { v -> edit { (it as? MaskComponent.Linear)?.copy(feather = v) ?: it } },
            onReset = {
                edit { (it as? MaskComponent.Linear)?.copy(feather = PERCENT) ?: it }
                actions.commit()
            },
        )

        is MaskComponent.Radial -> {
            MaskSlider(
                host,
                MaskTags.FEATHER,
                stringResource(R.string.masks_feather),
                c.feather,
                PERCENT_SPEC,
                ::percent,
                onChange = { v -> edit { (it as? MaskComponent.Radial)?.copy(feather = v) ?: it } },
                onReset = {
                    edit { (it as? MaskComponent.Radial)?.copy(feather = PERCENT / 2) ?: it }
                    actions.commit()
                },
            )
            FocusRow(host.focus, null) {
                ChipRow {
                    RevealChip(
                        label = stringResource(R.string.masks_radial_inside),
                        selected = c.inside,
                        onClick = {
                            edit { (it as? MaskComponent.Radial)?.copy(inside = true) ?: it }
                            actions.commit()
                        },
                        modifier = Modifier.testTag(MaskTags.INSIDE),
                    )
                    RevealChip(
                        label = stringResource(R.string.masks_radial_outside),
                        selected = !c.inside,
                        onClick = {
                            edit { (it as? MaskComponent.Radial)?.copy(inside = false) ?: it }
                            actions.commit()
                        },
                    )
                }
            }
        }

        is MaskComponent.ColourRange -> {
            FocusRow(host.focus, null) {
                ChipRow {
                    RevealChip(
                        label = stringResource(
                            R.string.masks_pick_colours,
                            c.samples.size,
                            MaskComponent.ColourRange.MAX_SAMPLES,
                        ),
                        selected = state.tool == PhotoTool.PICK_COLOUR,
                        onClick = {
                            actions.setTool(
                                if (state.tool ==
                                    PhotoTool.PICK_COLOUR
                                ) {
                                    PhotoTool.NONE
                                } else {
                                    PhotoTool.PICK_COLOUR
                                },
                            )
                        },
                        icon = PhosphorRegular.Eyedropper,
                        modifier = Modifier.testTag(MaskTags.PICK),
                    )
                    for (sample in c.samples) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    OkLabColors.color(
                                        sample.l,
                                        kotlin.math.hypot(sample.a, sample.b),
                                        hueOf(sample.a, sample.b),
                                    ),
                                )
                                .border(BorderStroke(1.dp, RevealTheme.colors.hairline), CircleShape),
                        )
                    }
                    if (c.samples.isNotEmpty()) {
                        RevealIconButton(
                            icon = PhosphorRegular.X,
                            contentDescription = stringResource(R.string.masks_clear_colours),
                            onClick = {
                                edit { (it as? MaskComponent.ColourRange)?.copy(samples = emptyList()) ?: it }
                                actions.commit()
                                actions.setTool(PhotoTool.PICK_COLOUR)
                            },
                        )
                    }
                }
            }
            MaskSlider(
                host,
                MaskTags.RANGE,
                stringResource(R.string.masks_range),
                c.range,
                PERCENT_SPEC,
                ::plain,
                onChange = { v -> edit { (it as? MaskComponent.ColourRange)?.copy(range = v) ?: it } },
                onReset = {
                    edit { (it as? MaskComponent.ColourRange)?.copy(range = PERCENT / 2) ?: it }
                    actions.commit()
                },
            )
            MaskSlider(
                host,
                MaskTags.SMOOTHNESS,
                stringResource(R.string.masks_smoothness),
                c.smoothness,
                PERCENT_SPEC,
                ::plain,
                onChange = { v -> edit { (it as? MaskComponent.ColourRange)?.copy(smoothness = v) ?: it } },
                onReset = {
                    edit { (it as? MaskComponent.ColourRange)?.copy(smoothness = PERCENT / 2) ?: it }
                    actions.commit()
                },
            )
        }

        is MaskComponent.LuminanceRange -> {
            FocusRow(host.focus, null) {
                ChipRow {
                    RevealChip(
                        label = stringResource(R.string.masks_luminance_map),
                        selected = state.luminanceMap,
                        onClick = { actions.setLuminanceMap(!state.luminanceMap) },
                        icon = PhosphorRegular.DropHalf,
                        modifier = Modifier.testTag(MaskTags.LUMA_MAP),
                    )
                }
            }
            FocusRow(host.focus, null) { LuminanceBar(c) }
            MaskSlider(
                host,
                MaskTags.LUMA_LOW,
                stringResource(R.string.masks_luminance_low),
                c.low,
                PERCENT_SPEC,
                ::plain,
                onChange = { v ->
                    edit {
                        (it as? MaskComponent.LuminanceRange)?.let { l -> l.copy(low = minOf(v, l.high)) }
                            ?: it
                    }
                },
                onReset = {
                    edit { (it as? MaskComponent.LuminanceRange)?.copy(low = PERCENT / 2) ?: it }
                    actions.commit()
                },
            )
            MaskSlider(
                host,
                MaskTags.LUMA_HIGH,
                stringResource(R.string.masks_luminance_high),
                c.high,
                PERCENT_SPEC,
                ::plain,
                onChange = { v ->
                    edit {
                        (it as? MaskComponent.LuminanceRange)?.let { l -> l.copy(high = maxOf(v, l.low)) }
                            ?: it
                    }
                },
                onReset = {
                    edit { (it as? MaskComponent.LuminanceRange)?.copy(high = PERCENT) ?: it }
                    actions.commit()
                },
            )
            MaskSlider(
                host,
                MaskTags.LUMA_FALLOFF,
                stringResource(R.string.masks_luminance_falloff),
                c.falloff,
                PERCENT_SPEC,
                ::plain,
                onChange = { v -> edit { (it as? MaskComponent.LuminanceRange)?.copy(falloff = v) ?: it } },
                onReset = {
                    edit { (it as? MaskComponent.LuminanceRange)?.copy(falloff = DEFAULT_FALLOFF) ?: it }
                    actions.commit()
                },
            )
        }

        is MaskComponent.Ai -> if (AiMasksKinds.of(c) == MaskKind.SUBJECT) {
            FocusRow(host.focus, null) {
                Column {
                    ChipRow {
                        RevealChip(
                            label = stringResource(R.string.masks_subject_add),
                            selected = state.tool == PhotoTool.SUBJECT_ADD,
                            onClick = {
                                actions.setTool(
                                    if (state.tool ==
                                        PhotoTool.SUBJECT_ADD
                                    ) {
                                        PhotoTool.NONE
                                    } else {
                                        PhotoTool.SUBJECT_ADD
                                    },
                                )
                            },
                            icon = PhosphorRegular.HandTap,
                            modifier = Modifier.testTag(MaskTags.SUBJECT_ADD),
                        )
                        RevealChip(
                            label = stringResource(R.string.masks_subject_subtract),
                            selected = state.tool == PhotoTool.SUBJECT_SUBTRACT,
                            onClick = {
                                val next = if (state.tool ==
                                    PhotoTool.SUBJECT_SUBTRACT
                                ) {
                                    PhotoTool.NONE
                                } else {
                                    PhotoTool.SUBJECT_SUBTRACT
                                }
                                actions.setTool(next)
                            },
                            icon = PhosphorRegular.Minus,
                            modifier = Modifier.testTag(MaskTags.SUBJECT_SUBTRACT),
                        )
                    }
                    if (state.tool == PhotoTool.SUBJECT_ADD || state.tool == PhotoTool.SUBJECT_SUBTRACT) {
                        Note(stringResource(R.string.masks_subject_hint))
                    }
                }
            }
        }

        is MaskComponent.Unknown -> Unit
    }
    if (mask.components.isEmpty()) Unit
}

@Composable
private fun BrushControls(host: PanelHost, brush: BrushSettings) {
    val actions = host.actions.masks
    FocusRow(host.focus, null) {
        ChipRow {
            RevealChip(
                label = stringResource(R.string.masks_brush_paint),
                selected = !brush.erase,
                onClick = { actions.setBrush(brush.copy(erase = false)) },
                icon = PhosphorRegular.PaintBrush,
            )
            RevealChip(
                label = stringResource(R.string.masks_brush_erase),
                selected = brush.erase,
                onClick = { actions.setBrush(brush.copy(erase = true)) },
                icon = PhosphorRegular.Eraser,
                modifier = Modifier.testTag(MaskTags.BRUSH_ERASE),
            )
            RevealChip(
                label = stringResource(R.string.masks_brush_auto),
                selected = brush.autoMask,
                onClick = { actions.setBrush(brush.copy(autoMask = !brush.autoMask)) },
                icon = PhosphorRegular.MagicWand,
                modifier = Modifier.testTag(MaskTags.BRUSH_AUTO),
            )
        }
    }
    BrushSlider(host, MaskTags.BRUSH_SIZE, R.string.masks_brush_size, brush.size * PERCENT, SIZE_SPEC, ::sizePercent) {
        actions.setBrush(brush.copy(size = it / PERCENT))
    }
    BrushSlider(host, MaskTags.BRUSH_FEATHER, R.string.masks_feather, brush.feather, PERCENT_SPEC, ::plain) {
        actions.setBrush(brush.copy(feather = it))
    }
    BrushSlider(host, MaskTags.BRUSH_FLOW, R.string.masks_brush_flow, brush.flow, PERCENT_SPEC, ::plain) {
        actions.setBrush(brush.copy(flow = it))
    }
    BrushSlider(host, MaskTags.BRUSH_DENSITY, R.string.masks_brush_density, brush.density, PERCENT_SPEC, ::plain) {
        actions.setBrush(brush.copy(density = it))
    }
    FocusRow(host.focus, null) {
        Column {
            Eyebrow(stringResource(R.string.masks_pen_pressure), Modifier.padding(start = 16.dp, top = 8.dp))
            ChipRow {
                RevealChip(
                    label = stringResource(R.string.masks_pressure_flow),
                    selected = brush.pressure == StrokePressure.FLOW,
                    onClick = { actions.setBrush(brush.copy(pressure = StrokePressure.FLOW)) },
                    icon = PhosphorRegular.PenNib,
                    modifier = Modifier.testTag(MaskTags.PRESSURE_FLOW),
                )
                RevealChip(
                    label = stringResource(R.string.masks_pressure_size),
                    selected = brush.pressure == StrokePressure.SIZE,
                    onClick = { actions.setBrush(brush.copy(pressure = StrokePressure.SIZE)) },
                    icon = PhosphorRegular.PenNib,
                    modifier = Modifier.testTag(MaskTags.PRESSURE_SIZE),
                )
            }
        }
    }
}

/** A brush setting's slider (UI state, not the recipe: nothing to commit). */
@Composable
private fun BrushSlider(
    host: PanelHost,
    key: String,
    label: Int,
    value: Float,
    spec: SliderSpec,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    val focus = host.focus
    FocusRow(focus, key) {
        RevealSlider(
            label = stringResource(label),
            value = value,
            spec = spec,
            format = format,
            onValueChange = onChange,
            onReset = { onChange(spec.neutral) },
            testTag = key,
            onDragStateChange = { focus.dragging = if (it) key else null },
        )
    }
}

/** The luminance range on a black-to-white bar: the selected band, its falloff ramps fainter. */
@Composable
private fun LuminanceBar(c: MaskComponent.LuminanceRange) {
    val colors = RevealTheme.colors
    val accent = colors.primary
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(16.dp)
            .clip(RevealTheme.shapes.control)
            .background(Brush.horizontalGradient(listOf(colors.background, colors.foreground)))
            .drawWithContent {
                drawContent()
                val w = size.width
                val lo = (c.low / PERCENT).coerceIn(0f, 1f) * w
                val hi = (c.high / PERCENT).coerceIn(0f, 1f) * w
                val f = (c.falloff / PERCENT) * w
                val band = size.height / 3f
                val top = size.height - band
                drawRect(accent.copy(alpha = FALLOFF_ALPHA), Offset(lo - f, top), Size(f, band))
                drawRect(accent, Offset(lo, top), Size((hi - lo).coerceAtLeast(1f), band))
                drawRect(accent.copy(alpha = FALLOFF_ALPHA), Offset(hi, top), Size(f, band))
            },
    )
}

// ---------------------------------------------------------------------------------------------- adjustments

@Composable
private fun AdjustmentSlider(host: PanelHost, mask: Mask, adjustment: LocalAdjustment) {
    val actions = host.actions.masks
    val track = when (adjustment) {
        LocalAdjustment.COLOUR_HUE -> OkLabColors.hueGradient(HUES)
        LocalAdjustment.COLOUR_SATURATION -> OkLabColors.saturationGradient(mask.adjustments.colourHue)
        else -> null
    }
    MaskSlider(
        host = host,
        key = MaskTags.adjustment(adjustment),
        label = stringResource(MaskLabels.adjustment(adjustment)),
        value = mask.adjustments[adjustment],
        spec = MaskLabels.spec(adjustment),
        format = { MaskLabels.format(adjustment, it) },
        onChange = { actions.setAdjustment(mask.id, adjustment, it) },
        onReset = { actions.resetAdjustment(mask.id, adjustment) },
        trackBrush = track,
    )
}

/** A slider bound to the recipe through [onChange] per frame; release commits one step; double-tap resets. */
@Suppress("LongParameterList")
@Composable
private fun MaskSlider(
    host: PanelHost,
    key: String,
    label: String,
    value: Float,
    spec: SliderSpec,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    trackBrush: Brush? = null,
) {
    val focus = host.focus
    val actions = host.actions.masks
    FocusRow(focus, key) {
        RevealSlider(
            label = label,
            value = value,
            spec = spec,
            format = format,
            onValueChange = onChange,
            onReset = onReset,
            enabled = host.state.ready,
            trackBrush = trackBrush,
            testTag = key,
            onDragStateChange = { dragging ->
                focus.dragging = if (dragging) key else null
                actions.setInteracting(dragging)
            },
            onValueChangeFinished = actions::commit,
        )
    }
}

// ---------------------------------------------------------------------------------------------- small parts

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
internal fun Note(text: String, tag: String? = null) {
    Text(
        text = text,
        style = RevealTheme.typography.ui,
        color = RevealTheme.colors.mutedForeground,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

private fun hueOf(a: Float, b: Float): Float {
    val deg = Math.toDegrees(kotlin.math.atan2(b, a).toDouble()).toFloat()
    return if (deg < 0f) deg + FULL_TURN else deg
}

private fun percent(v: Float): String = ValueFormat.format(ValueKind.PERCENT, v)

private fun plain(v: Float): String = ValueFormat.format(ValueKind.INT, v)

private fun sizePercent(v: Float): String = ValueFormat.format(ValueKind.DECIMAL1, v) + " %"

private const val PERCENT = 100f
private const val DEFAULT_FALLOFF = 10f
private const val FULL_TURN = 360f
private const val DISABLED_ALPHA = 0.45f
private const val FALLOFF_ALPHA = 0.4f
private const val OPAQUE = -0x1000000
private val CELL = 56.dp
private val CELL_GAP = 8.dp
private val HUES = List(13) { it * 30f }
private val AMOUNT_SPEC = SliderSpec(0f, 100f, 100f, 1f, 1f, bipolar = false)
private val PERCENT_SPEC = SliderSpec(0f, 100f, 0f, 1f, 1f, bipolar = false)
private val OPACITY_SPEC = SliderSpec(10f, 100f, 50f, 1f, 1f, bipolar = false)
private val SIZE_SPEC = SliderSpec(
    BrushSettings.MIN_SIZE * 100f,
    BrushSettings.MAX_SIZE * 100f,
    BrushSettings.DEFAULT_SIZE * 100f,
    0.1f,
    0.1f,
    bipolar = false,
)
