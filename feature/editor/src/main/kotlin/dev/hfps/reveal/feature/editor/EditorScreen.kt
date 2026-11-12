package dev.hfps.reveal.feature.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hfps.reveal.data.editor.EditorLayout
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.canvas.PhotoController
import dev.hfps.reveal.feature.editor.canvas.PhotoSlot
import dev.hfps.reveal.feature.editor.canvas.PhotoSurface
import dev.hfps.reveal.feature.editor.canvas.rememberPhotoController
import dev.hfps.reveal.feature.editor.layout.EditorChrome
import dev.hfps.reveal.feature.editor.layout.EditorLayoutKind
import dev.hfps.reveal.feature.editor.layout.EditorTopBar
import dev.hfps.reveal.feature.editor.layout.FilmstripUi
import dev.hfps.reveal.feature.editor.layout.FilmstripViewModel
import dev.hfps.reveal.feature.editor.layout.PanelSnap
import dev.hfps.reveal.feature.editor.layout.SystemBarsHidden
import dev.hfps.reveal.feature.editor.layout.ToolStrip
import dev.hfps.reveal.feature.editor.layout.rememberEditorChrome
import dev.hfps.reveal.feature.editor.panels.PanelCardContent
import dev.hfps.reveal.feature.editor.panels.PanelHost
import dev.hfps.reveal.feature.editor.panels.PasteChooserSheet
import dev.hfps.reveal.feature.editor.tools.EditorTool
import dev.hfps.reveal.feature.editor.tools.EditorTools
import dev.hfps.reveal.feature.editor.tools.ModeHost
import dev.hfps.reveal.feature.editor.tools.ToolBehaviour
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.feature.export.ui.ExportRequest
import dev.hfps.reveal.feature.export.ui.ExportSheetHost
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.gl.CompareMode
import kotlinx.coroutines.launch

/** The editor of the photo in its [EditorRoute]: always dark (the darkroom). The actions wire one per editor verb. */
@Suppress("LongMethod")
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSwitch: (EditorRoute) -> Unit = {},
    viewModel: EditorViewModel = hiltViewModel(),
    strip: FilmstripViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val photos by strip.photos.collectAsStateWithLifecycle()
    val layout by strip.layout.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    var exportRequest by remember { mutableStateOf<ExportRequest?>(null) }
    val actions = remember(viewModel, strip, onBack, onSwitch, context) {
        EditorActions(
            onBack = onBack,
            onDeveloped = viewModel::onDeveloped,
            onPreviewHidden = viewModel::onPreviewHidden,
            onControl = viewModel::setControl,
            onCommit = viewModel::commit,
            onResetControl = viewModel::resetControl,
            onResetGroup = viewModel::resetGroup,
            onLook = viewModel::setLook,
            onWbMode = viewModel::setWbMode,
            onAuto = viewModel::auto,
            onHistogram = viewModel::setHistogram,
            onClipping = viewModel::setClipping,
            onCompare = viewModel::setCompare,
            onShowOriginal = viewModel::setShowOriginal,
            onEyedropper = viewModel::setEyedropper,
            onPickNeutral = viewModel::pickNeutral,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onGoTo = viewModel::goTo,
            onCopy = {
                viewModel.copy()
                Toast.makeText(context, R.string.editor_copied, Toast.LENGTH_SHORT).show()
            },
            onPaste = viewModel::openPaste,
            onPasteGroups = viewModel::setPasteGroups,
            onPasteConfirm = viewModel::confirmPaste,
            onPasteDismiss = viewModel::dismissPaste,
            onPrevious = viewModel::previous,
            onResetAll = viewModel::resetAll,
            onResetAsOpened = viewModel::resetAsOpened,
            onGeometry = viewModel::setGeometry,
            onCropMode = viewModel::setCropMode,
            onCropDone = viewModel::cropDone,
            onCropCancel = viewModel::cropCancel,
            onExport = { exportRequest = viewModel.exportRequest() },
            onDetailMask = viewModel::setDetailMask,
            onControls = viewModel::setControls,
            onCurve = viewModel::setCurve,
            onCurvePreset = viewModel::applyCurvePreset,
            onTreatment = viewModel::setTreatment,
            onAutoMix = viewModel::autoMix,
            onTargeted = viewModel::setTargeted,
            onTargetedStart = viewModel::targetedStart,
            onTargetedDrag = viewModel::targetedDrag,
            onTargetedEnd = viewModel::targetedEnd,
            onEditAndCommit = viewModel::editAndCommitStep,
            onRemoveCa = viewModel::setRemoveCa,
            onUpright = viewModel::upright,
            onAutoStraighten = viewModel::autoStraighten,
            onGuide = viewModel::addGuide,
            onClearGuides = viewModel::clearGuides,
            onTargetedHover = viewModel::targetedHover,
            onOpenPhoto = { versionId, tool -> onSwitch(viewModel.routeFor(versionId, tool)) },
            onFilmstrip = strip::setFilmstripVisible,
            onPanelWidth = strip::setPanelWidth,
            onPanelHeight = strip::setPanelHeight,
        )
    }
    val filmstrip = remember(photos, strip) {
        FilmstripUi(photos, strip.currentVersionId, strip.thumbs).takeIf { photos.size > 1 && it.index >= 0 }
    }
    EditorTheme {
        EditorContent(
            state = state,
            actions = actions,
            modifier = modifier,
            initialTool = viewModel.initialTool,
            filmstrip = filmstrip,
            layout = layout,
        )
        ExportSheetHost(request = exportRequest, onDismiss = { exportRequest = null })
    }
}

/**
 * Stateless editor content (inside `EditorTheme`), laid out for the window (docs/plan/ux.md): phone portrait (photo
 * above a 3-snap panel card and the tool strip), landscape phone (panel column and tool rail on the right, icon top
 * bar) or tablet. The photo surface fills the screen behind the chrome; the chrome leaves a photo slot whose bounds
 * become the photo frame. Public for screenshot tests, which also pick the [initialTool] and [initialSnap].
 */
@Composable
fun EditorContent(
    state: EditorUiState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
    initialTool: ToolId = ToolId.LIGHT,
    initialSnap: PanelSnap = PanelSnap.HALF,
    initialMode: ToolId? = null,
    filmstrip: FilmstripUi? = null,
    layout: EditorLayout = EditorLayout(),
) {
    val colors = RevealTheme.colors
    val photo = rememberPhotoController(state.phase)
    val chrome = rememberEditorChrome(initialTool, initialSnap, initialMode)
    PhotoContentSize(photo, state)
    val scope = rememberCoroutineScope()
    LaunchedEffect(chrome.activeMode) {
        for (tool in EditorTools.all) {
            (tool.behaviour as? ToolBehaviour.Mode)?.onActive?.invoke(actions, chrome.activeMode == tool.id)
        }
    }
    SystemBarsHidden(chrome.immersive)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onPlaced(photo::onRootPlaced)
            .editorKeys(state, actions, photo, chrome, filmstrip, scope),
    ) {
        val kind = EditorLayoutKind.of(maxWidth, maxHeight)
        val host = PanelHost(state, actions, chrome.focus, chrome.navigator)
        val onToggleChrome = { chrome.immersive = !chrome.immersive }
        val select: (EditorTool) -> Unit = { tool ->
            chrome.select(tool, kind, actions) { snap -> scope.launch { chrome.sheet.animateTo(snap) } }
        }
        PhotoSurface(photo.renderer, onFirstFrame = actions.onDeveloped, modifier = Modifier.fillMaxSize())
        when (kind) {
            EditorLayoutKind.PORTRAIT ->
                PortraitLayout(state, actions, photo, chrome, host, select, onToggleChrome, maxHeight.value)

            EditorLayoutKind.LANDSCAPE -> SideLayout(kind, state, actions, photo, chrome, host, select, onToggleChrome)

            EditorLayoutKind.TABLET -> TabletLayout(
                TabletChrome(state, actions, photo, chrome, host, select, onToggleChrome),
                filmstrip = filmstrip,
                layout = layout,
                windowWidth = maxWidth,
            )

            EditorLayoutKind.TABLET_DOCKED -> DockedTabletLayout(
                TabletChrome(state, actions, photo, chrome, host, select, onToggleChrome),
                filmstrip = filmstrip,
                layout = layout,
                windowHeight = maxHeight,
            )
        }
    }
    BackHandler(enabled = chrome.immersive) { chrome.immersive = false }
    BackHandler(enabled = state.view.compare != CompareMode.Off) { actions.onCompare(CompareMode.Off) }
    BackHandler(enabled = chrome.activeMode != null) {
        chrome.activeMode?.let { (EditorTools[it].behaviour as? ToolBehaviour.Mode)?.cancel?.invoke(actions) }
        chrome.activeMode = null
    }
    BackHandler(enabled = state.view.eyedropper) { actions.onEyedropper(false) }
    val pasteGroups = state.edit.pasteChooser
    val copied = state.edit.clipboard
    if (pasteGroups != null && copied != null) {
        PasteChooserSheet(
            copied = copied,
            groups = pasteGroups,
            onGroupsChange = actions.onPasteGroups,
            onPaste = actions.onPasteConfirm,
            onDismiss = actions.onPasteDismiss,
        )
    }
    LaunchedEffect(state.phase is EditorPhase.Ready) { chrome.focus.dragging = null }
}

/** The [ModeHost] of the active [mode]: Done and Cancel run the mode's exits and close it. */
internal fun EditorChrome.modeHost(
    mode: ToolBehaviour.Mode,
    state: EditorUiState,
    actions: EditorActions,
    photo: PhotoController,
    vertical: Boolean,
) = ModeHost(
    state = state,
    actions = actions,
    photo = photo,
    crop = crop,
    vertical = vertical,
    onDone = {
        mode.done(actions)
        activeMode = null
    },
    onCancel = {
        mode.cancel(actions)
        activeMode = null
    },
)

/**
 * Keeps the zoom's content the size of what is shown: the geometry operator's output (the cropped, turned picture);
 * re-fits when it changes and when the Crop mode closes (the mode drives the view itself while open).
 */
@Composable
private fun PhotoContentSize(photo: PhotoController, state: EditorUiState) {
    val ready = state.phase as? EditorPhase.Ready ?: return
    val mapping = GeometryMapping.of(state.edit.recipe, state.edit.lens, ready.width, ready.height)
    val cropping = state.view.cropping
    LaunchedEffect(photo.zoom, mapping.outputWidth, mapping.outputHeight, cropping) {
        if (!cropping) photo.setContentSize(mapping.outputWidth, mapping.outputHeight)
    }
}

/** What selecting [tool] does: open/collapse its panel, run its action or enter its mode. */
private fun EditorChrome.select(
    tool: EditorTool,
    kind: EditorLayoutKind,
    actions: EditorActions,
    snapTo: (PanelSnap) -> Unit,
) {
    when (val behaviour = tool.behaviour) {
        is ToolBehaviour.Panel -> {
            val same = activeTool == tool.id
            if (!same) navigator.clear()
            activeTool = tool.id
            if (kind == EditorLayoutKind.PORTRAIT) {
                val collapsed = sheet.currentValue == PanelSnap.COLLAPSED
                snapTo(
                    if (same &&
                        !collapsed
                    ) {
                        PanelSnap.COLLAPSED
                    } else if (collapsed) {
                        PanelSnap.HALF
                    } else {
                        sheet.currentValue
                    },
                )
            } else {
                sidePanelOpen = if (same) !sidePanelOpen else true
            }
        }

        is ToolBehaviour.Mode -> activeMode = tool.id

        is ToolBehaviour.Action -> behaviour.run(actions)

        ToolBehaviour.Unavailable -> Unit
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun PortraitLayout(
    state: EditorUiState,
    actions: EditorActions,
    photo: PhotoController,
    chrome: EditorChrome,
    host: PanelHost,
    select: (EditorTool) -> Unit,
    onToggleChrome: () -> Unit,
    heightDp: Float,
) {
    val colors = RevealTheme.colors
    val density = LocalDensity.current
    val chromeVisible = !chrome.immersive
    val sheet = chrome.sheet
    val scope = rememberCoroutineScope()
    val expandedPx = with(density) { (heightDp * EXPANDED_SHARE).dp.toPx() }
    val halfPx = with(density) { (heightDp * HALF_SHARE).dp.toPx() }
    LaunchedEffect(expandedPx, halfPx) {
        sheet.updateAnchors(
            DraggableAnchors {
                PanelSnap.EXPANDED at 0f
                PanelSnap.HALF at expandedPx - halfPx
                PanelSnap.COLLAPSED at expandedPx
            },
        )
    }
    // Sub-panels that need the room (curves, mixers, wheels) open the tall panel.
    val sub = host.navigator.top
    LaunchedEffect(sub?.key) {
        if (sub?.expand == true && sheet.currentValue != PanelSnap.EXPANDED) sheet.animateTo(PanelSnap.EXPANDED)
    }
    val fling = AnchoredDraggableDefaults.flingBehavior(sheet, positionalThreshold = { it * POSITIONAL_THRESHOLD })
    val drag = Modifier.anchoredDraggable(sheet, Orientation.Vertical, flingBehavior = fling)
    val mode = chrome.activeMode?.let { EditorTools[it].behaviour as? ToolBehaviour.Mode }
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
                EditorTopBar(state, actions, compact = false, narrow = true, onHistory = {
                    select(EditorTools.history)
                })
            }
        }
        PhotoSlot(
            state = state,
            actions = actions,
            controller = photo,
            chromeVisible = chromeVisible,
            onToggleChrome = onToggleChrome,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            modeOverlay = mode?.let { m -> { m.overlay(this, chrome.modeHost(m, state, actions, photo, false)) } },
        )
        AnimatedVisibility(
            visible = chromeVisible && state.phase !is EditorPhase.Failed,
            enter = fadeIn(RevealMotion.standard()) + expandVertically(RevealMotion.standard()),
            exit = fadeOut(RevealMotion.fast()) + shrinkVertically(RevealMotion.standard()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
            ) {
                if (mode != null) {
                    ModeControls(mode, chrome.modeHost(mode, state, actions, photo, vertical = false))
                } else {
                    val panel = EditorTools[chrome.activeTool].behaviour as? ToolBehaviour.Panel
                    val offset = sheet.offset.takeUnless { it.isNaN() } ?: (expandedPx - halfPx)
                    val heightPx = (expandedPx - offset).coerceAtLeast(0f)
                    if (panel != null && (heightPx > 1f || chrome.focus.active)) {
                        val expand = stringResource(R.string.editor_panel_expand)
                        val collapse = stringResource(R.string.editor_panel_collapse)
                        PanelSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .heightIn(max = with(density) { heightPx.toDp() })
                                .testTag(EditorTags.PANEL),
                        ) {
                            PanelCardContent(
                                panel = panel,
                                host = host,
                                showHandle = true,
                                headerModifier = drag.semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(expand) {
                                            scope.launch { sheet.animateTo(PanelSnap.EXPANDED) }
                                            true
                                        },
                                        CustomAccessibilityAction(collapse) {
                                            scope.launch { sheet.animateTo(PanelSnap.COLLAPSED) }
                                            true
                                        },
                                    )
                                },
                            )
                        }
                    }
                    ToolStrip(
                        tools = EditorTools.all,
                        active = chrome.activeTool,
                        onSelect = select,
                        vertical = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(drag),
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun SideLayout(
    kind: EditorLayoutKind,
    state: EditorUiState,
    actions: EditorActions,
    photo: PhotoController,
    chrome: EditorChrome,
    host: PanelHost,
    select: (EditorTool) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val colors = RevealTheme.colors
    val chromeVisible = !chrome.immersive
    val mode = chrome.activeMode?.let { EditorTools[it].behaviour as? ToolBehaviour.Mode }
    val panel = EditorTools[chrome.activeTool].behaviour as? ToolBehaviour.Panel
    val failed = state.phase is EditorPhase.Failed
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(RevealMotion.standard()) + expandVertically(RevealMotion.standard()),
                exit = fadeOut(RevealMotion.fast()) + shrinkVertically(RevealMotion.standard()),
            ) {
                Box(
                    Modifier
                        .background(colors.background)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start),
                        ),
                ) {
                    EditorTopBar(
                        state,
                        actions,
                        compact = kind == EditorLayoutKind.LANDSCAPE,
                        onHistory = { select(EditorTools.history) },
                    )
                }
            }
            PhotoSlot(
                state = state,
                actions = actions,
                controller = photo,
                chromeVisible = chromeVisible,
                onToggleChrome = onToggleChrome,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                modeOverlay = mode?.let { m ->
                    { m.overlay(this, chrome.modeHost(m, state, actions, photo, vertical = true)) }
                },
            )
        }
        AnimatedVisibility(
            visible = chromeVisible && !failed && mode == null && panel != null && chrome.sidePanelOpen,
            enter = fadeIn(RevealMotion.standard()) + expandHorizontally(RevealMotion.standard()),
            exit = fadeOut(RevealMotion.fast()) + shrinkHorizontally(RevealMotion.standard()),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .background(colors.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            ) {
                if (panel != null) {
                    PanelSurface(
                        modifier = Modifier
                            .width(kind.panelWidth)
                            .padding(vertical = 8.dp)
                            .testTag(EditorTags.PANEL),
                    ) {
                        PanelCardContent(panel = panel, host = host)
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
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End),
                    ),
            ) {
                if (mode != null) {
                    // A mode's controls take the panel column's width (the rail is too narrow for them).
                    PanelSurface(
                        modifier = Modifier
                            .width(kind.panelWidth)
                            .padding(8.dp),
                    ) {
                        ModeControls(mode, chrome.modeHost(mode, state, actions, photo, vertical = true))
                    }
                } else {
                    ToolStrip(tools = EditorTools.all, active = chrome.activeTool, onSelect = select, vertical = true)
                }
            }
        }
    }
}

/** The panel card: the dark neutral card slightly lighter than the surround, hairline border, 16dp radius. */
@Composable
internal fun PanelSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = RevealTheme.colors
    Surface(
        modifier = modifier,
        shape = RevealTheme.shapes.card,
        color = colors.card,
        border = BorderStroke(1.dp, colors.hairline),
        content = content,
    )
}

/** A mode's own controls in place of the tool strip, above a row with Cancel, the mode's title and the Done check. */
@Composable
internal fun ModeControls(mode: ToolBehaviour.Mode, host: ModeHost, modifier: Modifier = Modifier) {
    Column(modifier.testTag(EditorTags.MODE_CONTROLS)) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (host.vertical) Modifier.weight(1f, fill = false) else Modifier),
        ) { mode.controls(host) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RevealIconButton(
                icon = PhosphorRegular.X,
                contentDescription = stringResource(R.string.editor_mode_cancel),
                onClick = host.onCancel,
                modifier = Modifier.testTag(EditorTags.MODE_CANCEL),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Eyebrow(stringResource(mode.title)) }
            RevealIconButton(
                icon = PhosphorRegular.Check,
                contentDescription = stringResource(R.string.editor_mode_done),
                onClick = host.onDone,
                active = true,
                modifier = Modifier.testTag(EditorTags.MODE_DONE),
            )
        }
    }
}

/** Panel card heights as shares of the window height (portrait). */
private const val HALF_SHARE = 0.40f
private const val EXPANDED_SHARE = 0.62f
private const val POSITIONAL_THRESHOLD = 0.5f
