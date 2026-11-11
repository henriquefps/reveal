package dev.hfps.reveal.feature.editor.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    /** Tablet / unfolded foldable (>= 840dp wide): photo, a 400dp panel column, tool rail, full top bar. */
    TABLET,
    ;

    /** Width of the side panel column. */
    val panelWidth: Dp get() = if (this == TABLET) TABLET_PANEL else LANDSCAPE_PANEL

    companion object {
        private val LANDSCAPE_PANEL = 360.dp
        private val TABLET_PANEL = 400.dp
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
    val focus = SliderFocus()
    val navigator = PanelNavigator()
}

/** An [EditorChrome] that keeps the active tool, immersive mode and the sheet's snap across configuration changes. */
@Composable
fun rememberEditorChrome(initialTool: ToolId = ToolId.LIGHT, initialSnap: PanelSnap = PanelSnap.HALF): EditorChrome {
    var tool by rememberSaveable { mutableStateOf(initialTool) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberSaveable(saver = AnchoredDraggableState.Saver()) { AnchoredDraggableState(initialSnap) }
    val chrome = remember(sheet) { EditorChrome(tool, immersive, sheet) }
    SideEffect {
        tool = chrome.activeTool
        immersive = chrome.immersive
    }
    return chrome
}

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
