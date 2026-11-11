package dev.hfps.reveal.feature.editor.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorActions
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.gl.CompareMode
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The editor's top bar (56dp): back, file name (13sp mono, ellipsised in the middle so the extension stays), undo,
 * redo, before/after ◐, more ⋯ and the primary Export. [compact] (landscape phone) keeps only the icons: no file
 * name, Export as an icon; [narrow] (phone portrait) shows Export as an icon-sized primary pill so the name gets the
 * width the icons do not need.
 */
@Composable
fun EditorTopBar(
    state: EditorUiState,
    actions: EditorActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
    narrow: Boolean = false,
    onHistory: () -> Unit = {},
) {
    RevealTopBar(
        title = if (compact) "" else state.fileName,
        onBack = actions.onBack,
        backContentDescription = stringResource(R.string.editor_back),
        titleStyle = RevealTheme.typography.mono,
        titleIsFileName = true,
        modifier = modifier.testTag(EditorTags.TOP_BAR),
    ) {
        val history = state.edit.history
        RevealIconButton(
            icon = PhosphorRegular.ArrowUUpLeft,
            contentDescription = stringResource(R.string.editor_undo),
            onClick = actions.onUndo,
            enabled = state.ready && history.canUndo,
            modifier = Modifier.testTag(EditorTags.UNDO),
        )
        RevealIconButton(
            icon = PhosphorRegular.ArrowUUpRight,
            contentDescription = stringResource(R.string.editor_redo),
            onClick = actions.onRedo,
            enabled = state.ready && history.canRedo,
            modifier = Modifier.testTag(EditorTags.REDO),
        )
        CompareButton(state, actions)
        MoreMenu(state, actions, onHistory)
        when {
            compact -> RevealIconButton(
                icon = PhosphorRegular.Export,
                contentDescription = stringResource(R.string.editor_export),
                onClick = actions.onExport,
                enabled = state.ready,
                modifier = Modifier.testTag(EditorTags.EXPORT),
            )

            // Phone portrait: an icon-sized primary pill leaves the file name the room.
            narrow -> ExportPill(
                onClick = actions.onExport,
                enabled = state.ready,
                modifier = Modifier.testTag(EditorTags.EXPORT),
            )

            else -> PrimaryButton(
                text = stringResource(R.string.editor_export),
                onClick = actions.onExport,
                enabled = state.ready,
                modifier = Modifier
                    .padding(start = 4.dp, end = 4.dp)
                    .testTag(EditorTags.EXPORT),
            )
        }
    }
}

/** The compare views offered by a long-press on ◐, in menu order. */
private val CompareOptions: List<Pair<CompareMode, Int>> = listOf(
    CompareMode.Split(leftRight = true) to R.string.editor_compare_split_lr,
    CompareMode.Split(leftRight = false) to R.string.editor_compare_split_tb,
    CompareMode.SideBySide to R.string.editor_compare_side,
    CompareMode.Off to R.string.editor_compare_off,
)

private fun compareOptionIcon(mode: CompareMode): ImageVector = when (mode) {
    is CompareMode.Split -> if (mode.leftRight) {
        PhosphorRegular.SquareSplitHorizontal
    } else {
        PhosphorRegular.SquareSplitVertical
    }

    CompareMode.SideBySide -> PhosphorRegular.Columns

    CompareMode.Off -> PhosphorRegular.X
}

private fun CompareMode.sameKind(other: CompareMode): Boolean = when (this) {
    is CompareMode.Split -> other is CompareMode.Split && other.leftRight == leftRight
    else -> this == other
}

/**
 * Before/after ◐: **hold** shows the original while the finger is down; a **long press** (still holding) also opens
 * the compare views, and releasing over one picks it (press-drag-release; releasing elsewhere leaves the menu open
 * for a normal tap). A **tap** (released within [TAP_MS]) toggles the last compare view on and off.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun CompareButton(state: EditorUiState, actions: EditorActions, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    val haptics = LocalHapticFeedback.current
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    var menuOpen by remember { mutableStateOf(false) }
    var hovered by remember { mutableIntStateOf(-1) }
    val itemBounds = remember { arrayOfNulls<Rect>(CompareOptions.size) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val label = stringResource(R.string.editor_compare)
    val hint = stringResource(R.string.editor_compare_hint)
    val active = state.view.compare != CompareMode.Off
    fun toggle() {
        val view = currentState.view
        currentActions.onCompare(if (view.compare == CompareMode.Off) view.lastCompare else CompareMode.Off)
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .onGloballyPositioned { anchor = it }
            .testTag(EditorTags.COMPARE)
            .semantics {
                contentDescription = label
                stateDescription = hint
                role = Role.Button
                onClick {
                    toggle()
                    true
                }
                onLongClick {
                    menuOpen = true
                    true
                }
            }
            .pointerInput(state.ready) {
                if (!state.ready) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    currentActions.onShowOriginal(true)
                    // A short press is a tap (toggle the last compare view); a longer one only peeks at the original.
                    val tap = withTimeoutOrNull(TAP_MS) { waitForUpOrCancellation() }
                    if (tap != null) {
                        currentActions.onShowOriginal(false)
                        toggle()
                        return@awaitEachGesture
                    }
                    val upEarly = withTimeoutOrNull(longPressMs - TAP_MS) { waitForUpOrCancellation() }
                    if (upEarly != null) {
                        currentActions.onShowOriginal(false)
                        return@awaitEachGesture
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                    var pressed = true
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull()
                        val root = change?.let { c -> anchor?.takeIf { it.isAttached }?.localToRoot(c.position) }
                        hovered = if (root == null) -1 else itemBounds.indexOfFirst { it?.contains(root) == true }
                        pressed = change?.pressed == true
                        change?.consume()
                    }
                    val picked = hovered
                    currentActions.onShowOriginal(false)
                    hovered = -1
                    if (picked >= 0) {
                        currentActions.onCompare(CompareOptions[picked].first)
                        menuOpen = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (active) PhosphorFill.CircleHalf else PhosphorRegular.CircleHalf,
            contentDescription = null,
            tint = if (!state.ready) {
                colors.mutedForeground
            } else if (active) {
                colors.primary
            } else {
                colors.foreground
            },
            modifier = Modifier.size(24.dp),
        )
        if (menuOpen) {
            Popup(alignment = Alignment.TopEnd, onDismissRequest = { menuOpen = false }) {
                Column(
                    modifier = Modifier
                        .padding(top = 52.dp)
                        .widthIn(min = 220.dp)
                        .background(colors.card, RevealTheme.shapes.control)
                        .border(1.dp, colors.hairline, RevealTheme.shapes.control)
                        .padding(vertical = 4.dp)
                        .testTag(EditorTags.COMPARE_MENU),
                ) {
                    CompareOptions.forEachIndexed { index, (mode, text) ->
                        val selected = state.view.compare.sameKind(mode)
                        Row(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .onGloballyPositioned { itemBounds[index] = it.boundsInRoot() }
                                .background(if (hovered == index) colors.muted else colors.card)
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    actions.onCompare(mode)
                                    menuOpen = false
                                }
                                .padding(horizontal = 16.dp)
                                .testTag(EditorTags.compareOption(index)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = compareOptionIcon(mode),
                                contentDescription = null,
                                tint = if (selected) colors.primary else colors.foreground,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(text),
                                style = RevealTheme.typography.ui,
                                color = if (selected) colors.primary else colors.foreground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .width(180.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * More ⋯: the history list, copy / paste / Previous, the resets, then the histogram toggle and the two clipping
 * overlays (also reachable from the histogram's corners).
 */
@Suppress("LongMethod")
@Composable
private fun MoreMenu(
    state: EditorUiState,
    actions: EditorActions,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val view = state.view
    val edit = state.edit
    val ready = state.ready
    fun run(action: () -> Unit): () -> Unit = {
        open = false
        action()
    }
    Box(modifier) {
        RevealIconButton(
            icon = PhosphorRegular.DotsThree,
            contentDescription = stringResource(R.string.editor_more),
            onClick = { open = true },
            modifier = Modifier.testTag(EditorTags.MORE),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(0.dp, 0.dp),
            containerColor = RevealTheme.colors.card,
            shape = RevealTheme.shapes.control,
        ) {
            ActionItem(
                R.string.editor_menu_history,
                PhosphorRegular.ClockCounterClockwise,
                EditorTags.MENU_HISTORY,
                onClick = run(onHistory),
            )
            ActionItem(
                R.string.editor_menu_copy,
                PhosphorRegular.Copy,
                EditorTags.MENU_COPY,
                enabled = ready,
                onClick = run(actions.onCopy),
            )
            ActionItem(
                R.string.editor_menu_paste,
                PhosphorRegular.ClipboardText,
                EditorTags.MENU_PASTE,
                enabled = ready && edit.canPaste,
                onClick = run(actions.onPaste),
            )
            ActionItem(
                R.string.editor_menu_previous,
                PhosphorRegular.ArrowBendUpLeft,
                EditorTags.MENU_PREVIOUS,
                enabled = ready && edit.hasPrevious,
                onClick = run(actions.onPrevious),
            )
            ActionItem(
                R.string.editor_menu_reset_all,
                PhosphorRegular.ArrowCounterClockwise,
                EditorTags.MENU_RESET_ALL,
                enabled = ready && !edit.committed.isDefault(),
                onClick = run(actions.onResetAll),
            )
            ActionItem(
                R.string.editor_menu_reset_as_opened,
                PhosphorRegular.ArrowCounterClockwise,
                EditorTags.MENU_RESET_AS_OPENED,
                enabled = ready,
                onClick = run(actions.onResetAsOpened),
            )
            HorizontalDivider(color = RevealTheme.colors.hairline)
            CheckItem(R.string.editor_menu_histogram, view.histogram, EditorTags.MENU_HISTOGRAM) {
                actions.onHistogram(!view.histogram)
            }
            CheckItem(R.string.editor_menu_shadow_clipping, view.clipping.shadows, EditorTags.MENU_SHADOWS) {
                actions.onClipping(view.clipping.copy(shadows = !view.clipping.shadows))
            }
            CheckItem(R.string.editor_menu_highlight_clipping, view.clipping.highlights, EditorTags.MENU_HIGHLIGHTS) {
                actions.onClipping(view.clipping.copy(highlights = !view.clipping.highlights))
            }
        }
    }
}

@Composable
private fun ActionItem(text: Int, icon: ImageVector, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = RevealTheme.colors
    val tint = if (enabled) colors.foreground else colors.mutedForeground
    DropdownMenuItem(
        text = { Text(stringResource(text), style = RevealTheme.typography.ui, color = tint) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun CheckItem(text: Int, checked: Boolean, tag: String, onClick: () -> Unit) {
    val colors = RevealTheme.colors
    DropdownMenuItem(
        text = { Text(stringResource(text), style = RevealTheme.typography.ui, color = colors.foreground) },
        onClick = onClick,
        leadingIcon = {
            if (checked) {
                Icon(
                    imageVector = PhosphorRegular.Check,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Spacer(Modifier.size(20.dp))
            }
        },
        modifier = Modifier
            .testTag(tag)
            .semantics { toggleableState = ToggleableState(checked) },
    )
}

/** Longest press that still counts as a tap on ◐ (longer = peek at the original). */
private const val TAP_MS = 200L

/** Export as an icon-sized primary pill (44dp tall, 48dp target) for narrow top bars. */
@Composable
private fun ExportPill(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    val label = stringResource(R.string.editor_export)
    Box(
        modifier = modifier
            .padding(start = 2.dp, end = 4.dp)
            .size(width = 48.dp, height = 40.dp)
            .background(if (enabled) colors.primary else colors.muted, RevealTheme.shapes.control)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
            }
            .then(
                if (enabled) Modifier.selectable(selected = false, role = Role.Button, onClick = onClick) else Modifier,
            )
            .testTag(EditorTags.EXPORT),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorRegular.Export,
            contentDescription = null,
            tint = if (enabled) colors.primaryForeground else colors.mutedForeground,
            modifier = Modifier.size(20.dp),
        )
    }
}
