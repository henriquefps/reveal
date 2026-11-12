package dev.hfps.reveal.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.editor.EditorLayout
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.canvas.PhotoController
import dev.hfps.reveal.feature.editor.canvas.PhotoSlot
import dev.hfps.reveal.feature.editor.canvas.animateDoubleTap
import dev.hfps.reveal.feature.editor.layout.EditorChrome
import dev.hfps.reveal.feature.editor.layout.EditorKeyCommand
import dev.hfps.reveal.feature.editor.layout.EditorKeys
import dev.hfps.reveal.feature.editor.layout.EditorLayoutKind
import dev.hfps.reveal.feature.editor.layout.EditorTopBar
import dev.hfps.reveal.feature.editor.layout.Filmstrip
import dev.hfps.reveal.feature.editor.layout.FilmstripUi
import dev.hfps.reveal.feature.editor.layout.ToolStrip
import dev.hfps.reveal.feature.editor.panels.PanelCardContent
import dev.hfps.reveal.feature.editor.panels.PanelHost
import dev.hfps.reveal.feature.editor.tools.EditorTool
import dev.hfps.reveal.feature.editor.tools.EditorTools
import dev.hfps.reveal.feature.editor.tools.ToolBehaviour
import dev.hfps.reveal.pipeline.gl.CompareMode
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The tablet / unfolded foldable editor (>= 840dp, docs/plan/ux.md "Landscape and tablets"): the full top bar across
 * the window; below it the photo with the filmstrip under it, the panel column (its left edge drags to resize it,
 * the width is remembered) and the tool rail. A mode's controls take the panel column and the rail (the filmstrip
 * hides while a mode is open).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun TabletLayout(t: TabletChrome, filmstrip: FilmstripUi?, layout: EditorLayout, windowWidth: Dp) {
    val colors = RevealTheme.colors
    val chrome = t.chrome
    val state = t.state
    val chromeVisible = !chrome.immersive
    val mode = chrome.activeMode?.let { EditorTools[it].behaviour as? ToolBehaviour.Mode }
    val panel = EditorTools[chrome.activeTool].behaviour as? ToolBehaviour.Panel
    val failed = state.phase is EditorPhase.Failed
    val range = EditorLayoutKind.panelRange(windowWidth, RAIL_ALLOWANCE)
    val panelWidth = (chrome.panelWidth ?: layout.panelWidthDp?.dp ?: EditorLayoutKind.TABLET.panelWidth)
        .coerceIn(range.start, range.endInclusive)
    val showStrip = filmstrip != null && layout.filmstripVisible
    val open: (Int) -> Unit = { step ->
        filmstrip?.neighbour(step)?.let { t.actions.onOpenPhoto(it.versionId, chrome.activeTool) }
    }
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(RevealMotion.standard()) + expandVertically(RevealMotion.standard()),
            exit = fadeOut(RevealMotion.fast()) + shrinkVertically(RevealMotion.standard()),
        ) {
            Box(
                Modifier
                    .background(colors.background)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ),
            ) {
                EditorTopBar(
                    state,
                    t.actions,
                    compact = false,
                    onHistory = { t.select(EditorTools.history) },
                    filmstrip = if (filmstrip != null) layout.filmstripVisible else null,
                )
            }
        }
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                PhotoSlot(
                    state = state,
                    actions = t.actions,
                    controller = t.photo,
                    chromeVisible = chromeVisible,
                    onToggleChrome = t.onToggleChrome,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                    modeOverlay = mode?.let { m ->
                        { m.overlay(this, chrome.modeHost(m, state, t.actions, t.photo, vertical = true)) }
                    },
                    onSwipe = if (filmstrip != null && mode == null) open else null,
                )
                AnimatedVisibility(
                    visible = chromeVisible && showStrip && !failed && mode == null,
                    enter = fadeIn(RevealMotion.standard()) + expandVertically(RevealMotion.standard()),
                    exit = fadeOut(RevealMotion.fast()) + shrinkVertically(RevealMotion.standard()),
                ) {
                    if (filmstrip != null) {
                        Filmstrip(
                            ui = filmstrip,
                            onOpen = { row ->
                                if (row.versionId != filmstrip.currentVersionId) {
                                    t.actions.onOpenPhoto(row.versionId, chrome.activeTool)
                                }
                            },
                            modifier = Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start),
                            ),
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = chromeVisible && !failed && mode == null && panel != null && chrome.sidePanelOpen,
                enter = fadeIn(RevealMotion.standard()) + expandHorizontally(RevealMotion.standard()),
                exit = fadeOut(RevealMotion.fast()) + shrinkHorizontally(RevealMotion.standard()),
            ) {
                Row(
                    Modifier
                        .fillMaxHeight()
                        .background(colors.background)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                ) {
                    PanelResizeHandle(
                        width = panelWidth,
                        range = range,
                        onResize = { chrome.panelWidth = it },
                        onResizeEnd = { t.actions.onPanelWidth(it.value) },
                    )
                    if (panel != null) {
                        PanelSurface(
                            modifier = Modifier
                                .width(panelWidth)
                                .padding(bottom = 8.dp)
                                .testTag(EditorTags.PANEL),
                        ) {
                            PanelCardContent(panel = panel, host = t.host)
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = chromeVisible && !failed,
                enter = fadeIn(RevealMotion.standard()) + expandHorizontally(RevealMotion.standard()),
                exit = fadeOut(RevealMotion.fast()) + shrinkHorizontally(RevealMotion.standard()),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .background(colors.background)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
                        ),
                ) {
                    if (mode != null) {
                        PanelSurface(
                            modifier = Modifier
                                .width(panelWidth)
                                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        ) {
                            ModeControls(mode, chrome.modeHost(mode, state, t.actions, t.photo, vertical = true))
                        }
                    } else {
                        ToolStrip(
                            tools = EditorTools.all,
                            active = chrome.activeTool,
                            onSelect = t.select,
                            vertical = true,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The panel's left edge: drag it to resize the panel within [range] ([onResize] per frame, [onResizeEnd] once on
 * release, which remembers the width). A pill marks it; TalkBack gets wider/narrower actions; a mouse or a hovering
 * pen shows the resize pointer.
 */
@Composable
private fun PanelResizeHandle(width: Dp, range: ClosedRange<Dp>, onResize: (Dp) -> Unit, onResizeEnd: (Dp) -> Unit) {
    val colors = RevealTheme.colors
    val density = LocalDensity.current
    val currentWidth by rememberUpdatedState(width)
    var dragging by remember { mutableStateOf(false) }
    // The width the drag has reached: the last delta may land after the last recomposition, so release stores this.
    var reached by remember { mutableStateOf(width) }
    val state = rememberDraggableState { delta ->
        val from = if (dragging) reached else currentWidth
        reached = (from - with(density) { delta.toDp() }).coerceIn(range.start, range.endInclusive)
        onResize(reached)
    }
    val label = stringResource(R.string.editor_panel_resize)
    val wider = stringResource(R.string.editor_panel_wider)
    val narrower = stringResource(R.string.editor_panel_narrower)
    fun step(by: Dp): Boolean {
        val next = (width + by).coerceIn(range.start, range.endInclusive)
        onResize(next)
        onResizeEnd(next)
        return true
    }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(HANDLE_WIDTH)
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .draggable(
                state = state,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    reached = currentWidth
                    dragging = true
                },
                onDragStopped = {
                    dragging = false
                    onResizeEnd(reached)
                },
            )
            .testTag(EditorTags.PANEL_RESIZE)
            .semantics {
                contentDescription = label
                stateDescription = "${width.value.roundToInt()} dp"
                customActions = listOf(
                    CustomAccessibilityAction(wider) { step(RESIZE_STEP) },
                    CustomAccessibilityAction(narrower) { step(-RESIZE_STEP) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(PILL_HEIGHT)
                .clip(CircleShape)
                .background(if (dragging) colors.primary else colors.hairline),
        )
    }
}

/**
 * The editor's keyboard shortcuts ([EditorKeys]) as a modifier for the editor's root, which takes the focus when it
 * appears so a hardware keyboard reaches it; a focused text field still gets its own keys first.
 */
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun Modifier.editorKeys(
    state: EditorUiState,
    actions: EditorActions,
    photo: PhotoController,
    chrome: EditorChrome,
    filmstrip: FilmstripUi?,
    scope: CoroutineScope,
): Modifier {
    val focus = remember { FocusRequester() }
    val current by rememberUpdatedState(Triple(state, actions, filmstrip))
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    return this
        .focusRequester(focus)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val ctrl = event.isCtrlPressed || event.isMetaPressed
            val command = EditorKeys.command(event.key, ctrl, event.isShiftPressed, event.nativeKeyEvent.isAltPressed)
                ?: return@onPreviewKeyEvent false
            val (s, a, strip) = current
            if (!s.ready) return@onPreviewKeyEvent false
            when (command) {
                EditorKeyCommand.UNDO -> true.also { if (s.edit.history.canUndo) a.onUndo() }

                EditorKeyCommand.REDO -> true.also { if (s.edit.history.canRedo) a.onRedo() }

                EditorKeyCommand.COMPARE -> true.also {
                    a.onCompare(if (s.view.compare == CompareMode.Off) s.view.lastCompare else CompareMode.Off)
                }

                // Arrows without a neighbour stay with focus navigation.
                EditorKeyCommand.NEXT, EditorKeyCommand.PREVIOUS -> {
                    val step = if (command == EditorKeyCommand.NEXT) 1 else -1
                    val next = strip?.neighbour(step)?.takeIf { chrome.activeMode == null }
                    next?.let { a.onOpenPhoto(it.versionId, chrome.activeTool) } != null
                }

                EditorKeyCommand.ZOOM_100 -> {
                    val z = photo.zoom?.takeIf { chrome.activeMode == null }
                    z?.let { scope.launch { it.animateDoubleTap(it.viewCenterX, it.viewCenterY) } } != null
                }
            }
        }
        .focusable()
}

/** Upper bound of the rail's width, for the panel's resize range. */
private val RAIL_ALLOWANCE = 96.dp
private val HANDLE_WIDTH = 16.dp
private val PILL_HEIGHT = 40.dp
private val RESIZE_STEP = 40.dp
