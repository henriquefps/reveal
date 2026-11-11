package dev.hfps.reveal.feature.editor.panels

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.tools.ToolBehaviour
import dev.hfps.reveal.pipeline.auto.AutoScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * The content of the panel card for a [ToolBehaviour.Panel]: an optional drag handle, the title row (eyebrow name,
 * panel Auto, reset ⟲; long-press the title also resets) and the rows, which scroll inside the card. A sub-panel
 * replaces the rows and puts a back arrow in the title row; the system back gesture (predictive) closes it.
 *
 * @param headerModifier applied to the handle + title area (the portrait sheet makes it draggable).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun PanelCardContent(
    panel: ToolBehaviour.Panel,
    host: PanelHost,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
    showHandle: Boolean = false,
) {
    val navigator = host.navigator
    val sub = navigator.top
    val title = stringResource(sub?.title ?: panel.title)
    val group = if (sub != null) sub.group else panel.group
    val recipe = host.state.edit.recipe
    val subReset = sub?.reset
    val canReset = host.state.ready &&
        if (subReset != null) subReset(recipe) != recipe else group != null && group.reset(recipe) != recipe
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = navigator.depth > 0) { events ->
        try {
            events.collect { backProgress = it.progress }
            navigator.pop()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
        backProgress = 0f
    }
    fun resetGroup() {
        if (!canReset) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (subReset != null) {
            host.actions.onEditAndCommit(subReset)
        } else if (group != null) {
            host.actions.onResetGroup(group)
        }
    }
    val currentReset by rememberUpdatedState(::resetGroup)
    Column(modifier) {
        FocusRow(host.focus, null) {
            Column(headerModifier.testTag(EditorTags.PANEL_HEADER)) {
                if (showHandle) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(RevealTheme.colors.input, RoundedCornerShape(2.dp)),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(start = if (sub == null) 16.dp else 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sub != null) {
                        RevealIconButton(
                            icon = PhosphorRegular.CaretLeft,
                            contentDescription = stringResource(
                                R.string.editor_panel_back,
                                stringResource(panel.title),
                            ),
                            onClick = { navigator.pop() },
                            modifier = Modifier.testTag(EditorTags.PANEL_BACK),
                        )
                    }
                    Eyebrow(
                        text = title,
                        modifier = Modifier
                            .weight(1f)
                            .pointerInput(Unit) { detectTapGestures(onLongPress = { currentReset() }) },
                    )
                    if (panel.showAuto && sub == null) {
                        GhostButton(
                            text = stringResource(R.string.editor_panel_auto),
                            onClick = { host.actions.onAuto(AutoScope.LIGHT) },
                            enabled = host.state.edit.autoReady,
                            modifier = Modifier.testTag(EditorTags.PANEL_AUTO),
                        )
                    }
                    if (group != null || sub != null) {
                        RevealIconButton(
                            icon = PhosphorRegular.ArrowCounterClockwise,
                            contentDescription = stringResource(R.string.editor_panel_reset, title),
                            onClick = ::resetGroup,
                            enabled = canReset,
                            modifier = Modifier.testTag(EditorTags.PANEL_RESET),
                        )
                    }
                }
            }
        }
        AnimatedContent(
            targetState = sub,
            transitionSpec = { fadeIn(RevealMotion.standard()) togetherWith fadeOut(RevealMotion.fast()) },
            contentKey = { it?.key ?: "root" },
            label = "subPanel",
            modifier = Modifier
                .weight(1f, fill = false)
                .graphicsLayer {
                    translationX = backProgress * with(density) { BACK_SHIFT.toPx() }
                    alpha = 1f - backProgress * BACK_FADE
                },
        ) { shown ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                (shown?.content ?: panel.content)(host)
            }
        }
    }
}

private val BACK_SHIFT = 48.dp
private const val BACK_FADE = 0.5f
