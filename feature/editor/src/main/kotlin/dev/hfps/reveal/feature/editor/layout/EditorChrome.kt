package dev.hfps.reveal.feature.editor.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.hfps.reveal.feature.editor.crop.CropSession
import dev.hfps.reveal.feature.editor.panels.PanelNavigator
import dev.hfps.reveal.feature.editor.panels.SliderFocus
import dev.hfps.reveal.feature.editor.tools.ToolId

/** The three heights of the portrait panel card (docs/plan/ux.md). */
enum class PanelSnap {
    /** Only the tool strip. */
    COLLAPSED,

    /** The panel (default). */
    HALF,

    /** A tall panel for curves, HSL, masks. */
    EXPANDED,
}

/** Which arrangement of the editor fits the window. */
enum class EditorLayoutKind {
    /** Phone portrait: photo above, panel card as a bottom sheet, horizontal tool strip. */
    PORTRAIT,

    /** Phone landscape: photo left, panel as a 360dp right column, vertical tool rail, icon-only top bar. */
    LANDSCAPE,

    /**
     * Tablet / unfolded foldable (>= 840dp wide): full-width top bar; the photo with the filmstrip under it, a
     * resizable panel column (400dp by default) and the tool rail.
     */
    TABLET,
    ;

    /** Default width of the side panel column. */
    val panelWidth: Dp get() = if (this == TABLET) TABLET_PANEL else LANDSCAPE_PANEL

    companion object {
        private val LANDSCAPE_PANEL = 360.dp
        private val TABLET_PANEL = 400.dp

        /** The narrowest the tablet panel can be dragged to. */
        val TABLET_PANEL_MIN = 320.dp

        /** The widest the tablet panel can be dragged to (and never more than the photo keeps, see [panelRange]). */
        val TABLET_PANEL_MAX = 560.dp

        /** The photo column keeps at least this much of the window when the panel is dragged wider. */
        private val PHOTO_MIN = 400.dp

        /** The allowed tablet panel widths in a window [windowWidth] wide (the rail takes [railWidth]). */
        fun panelRange(windowWidth: Dp, railWidth: Dp): ClosedRange<Dp> {
            val max = minOf(TABLET_PANEL_MAX, windowWidth - railWidth - PHOTO_MIN).coerceAtLeast(TABLET_PANEL_MIN)
            return TABLET_PANEL_MIN..max
        }

        private val TABLET_MIN_WIDTH = 840.dp
        private val TABLET_MIN_HEIGHT = 480.dp

        /** The layout for a window of [width] x [height]. */
        fun of(width: Dp, height: Dp): EditorLayoutKind = when {
            width >= TABLET_MIN_WIDTH && height >= TABLET_MIN_HEIGHT -> TABLET
            width > height -> LANDSCAPE
            else -> PORTRAIT
        }
    }
}

/**
 * UI-only state of the editor chrome: the active tool and mode, immersive mode, the side panel, the slider drag
 * focus and the sub-panel stack. The portrait sheet's snap lives in [sheet].
 */
@Stable
class EditorChrome(initialTool: ToolId, initialImmersive: Boolean, val sheet: AnchoredDraggableState<PanelSnap>) {
    var activeTool: ToolId by mutableStateOf(initialTool)
    var activeMode: ToolId? by mutableStateOf(null)
    var immersive: Boolean by mutableStateOf(initialImmersive)
    var sidePanelOpen: Boolean by mutableStateOf(true)

    /** The tablet panel's width while it is dragged (and after); `null` = the remembered or default width. */
    var panelWidth: Dp? by mutableStateOf(null)
    val focus = SliderFocus()
    val navigator = PanelNavigator()

    /** UI state of the Crop mode (grids, horizon tool, frozen view during a handle drag). */
    val crop = CropSession()
}

/**
 * An [EditorChrome] that keeps the active tool and mode, immersive mode, the sheet's snap, the side panel, the open
 * sub-panels and the tablet panel's width across configuration changes (rotation, fold/unfold).
 */
@Composable
fun rememberEditorChrome(
    initialTool: ToolId = ToolId.LIGHT,
    initialSnap: PanelSnap = PanelSnap.HALF,
    initialMode: ToolId? = null,
): EditorChrome {
    var tool by rememberSaveable { mutableStateOf(initialTool) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberSaveable(saver = AnchoredDraggableState.Saver()) { AnchoredDraggableState(initialSnap) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var sidePanel by rememberSaveable { mutableStateOf(true) }
    var subPanels by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var panelWidth by rememberSaveable { mutableStateOf<Float?>(null) }
    val chrome = remember(sheet) {
        EditorChrome(tool, immersive, sheet).also {
            it.activeMode = mode
            it.sidePanelOpen = sidePanel
            it.navigator.restore(subPanels)
            it.panelWidth = panelWidth?.dp
        }
    }
    // Snapshot reads, so a change in any inner scope (a sub-panel push, a resize) is saved too.
    LaunchedEffect(chrome) {
        snapshotFlow {
            SavedChrome(
                chrome.activeTool,
                chrome.immersive,
                chrome.activeMode,
                chrome.sidePanelOpen,
                chrome.navigator.keys,
                chrome.panelWidth?.value,
            )
        }.collect {
            tool = it.tool
            immersive = it.immersive
            mode = it.mode
            sidePanel = it.sidePanel
            subPanels = it.subPanels
            panelWidth = it.panelWidth
        }
    }
    return chrome
}

private data class SavedChrome(
    val tool: ToolId,
    val immersive: Boolean,
    val mode: ToolId?,
    val sidePanel: Boolean,
    val subPanels: List<String>,
    val panelWidth: Float?,
)

/** Hides the status and navigation bars while [hidden] (immersive mode); swipe from an edge shows them briefly. */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden, view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
