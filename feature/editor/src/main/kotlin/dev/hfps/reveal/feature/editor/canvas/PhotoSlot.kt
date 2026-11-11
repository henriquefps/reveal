package dev.hfps.reveal.feature.editor.canvas

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorActions
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.gl.CompareMode
import kotlin.math.roundToInt

/**
 * The photo slot: the empty space the layout leaves for the photo. It reports its bounds as the photo frame
 * ([PhotoController.onSlotPlaced]) and carries everything drawn or touched over the photo: gestures (zoom, pan,
 * hold = original, tap = immersive), the still preview, loading and error states, the histogram with its clipping
 * toggles, the before/after chrome and the eyedropper.
 *
 * @param chromeVisible false in immersive mode: the histogram and hints hide with the rest of the chrome.
 * @param modeOverlay the active mode's overlay (Crop frame, mask pins), in frame coordinates.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun PhotoSlot(
    state: EditorUiState,
    actions: EditorActions,
    controller: PhotoController,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
    modeOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val phase = state.phase
    val scope = rememberCoroutineScope()
    val zoom = controller.zoom
    Box(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned(controller::onSlotPlaced)
            .testTag(EditorTags.CANVAS),
    ) {
        val gestures = when {
            zoom == null -> Modifier

            state.view.eyedropper && phase is EditorPhase.Ready ->
                Modifier.eyedropperGestures(controller, phase, state, actions)

            else -> Modifier.canvasGestures(zoom, scope, onTap = onToggleChrome, onHold = actions.onShowOriginal)
        }
        val photoDescription = stringResource(R.string.editor_photo_actions)
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = photoDescription }
                .then(gestures),
        )
        if (phase is EditorPhase.Ready && controller.renderer == null) {
            val geometry = state.edit.recipe.geometry
            val mapping = if (state.view.cropping) {
                GeometryMapping.editing(geometry, phase.width, phase.height)
            } else {
                GeometryMapping.of(geometry, phase.width, phase.height)
            }
            if (mapping.isIdentity) {
                StillPhoto(state.preview, state.stillOriginal, state.view.compare)
            } else {
                StillGeometryPhoto(state.preview, mapping, controller)
            }
        }
        PreviewOverlay(state, onFadeOut = actions.onPreviewHidden)
        when (phase) {
            is EditorPhase.Loading -> LoadingBadge(phase.progress, Modifier.align(Alignment.BottomCenter))
            is EditorPhase.Failed -> ErrorState(phase.error, actions.onBack, Modifier.align(Alignment.Center))
            is EditorPhase.Ready -> if (state.developed) Box(Modifier.size(1.dp).testTag(EditorTags.DEVELOPED))
        }
        if (phase is EditorPhase.Ready) {
            if (!state.view.cropping) CompareOverlay(state.view.compare, actions.onCompare)
            modeOverlay?.invoke(this)
            AnimatedVisibility(
                visible = chromeVisible && state.view.histogram && !state.view.eyedropper && !state.view.cropping,
                enter = fadeIn(RevealMotion.standard()),
                exit = fadeOut(RevealMotion.fast()),
                modifier = Modifier
                    .align(if (state.view.compare is CompareMode.Off) Alignment.TopStart else Alignment.BottomStart)
                    .padding(8.dp),
            ) {
                HistogramView(state.histogram, state.view.clipping, actions.onClipping)
            }
            if (state.view.eyedropper) EyedropperHint(actions, Modifier.align(Alignment.TopCenter))
            EyedropperLoupe(controller)
        }
    }
}

/** The still photo when there is no renderer (screenshot tests): the preview, split against [original]. */
@Composable
private fun StillPhoto(edited: ImageBitmap?, original: ImageBitmap?, compare: CompareMode) {
    if (edited == null) return
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = edited,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        val split = compare as? CompareMode.Split
        if (original != null && split != null) {
            Image(
                bitmap = original,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val w = if (split.leftRight) size.width * split.position else size.width
                        val h = if (split.leftRight) size.height else size.height * split.position
                        drawContext.canvas.save()
                        drawContext.canvas.clipRect(0f, 0f, w, h)
                        drawContent()
                        drawContext.canvas.restore()
                    },
            )
        }
    }
}

/**
 * The still photo through a geometry [mapping] (screenshot tests and previews have no renderer): the un-cropped
 * [image] drawn with the mapping and the current view, clipped to the output (the crop) or, in the Crop mode's
 * editing plane, unclipped (the frame overlay dims the outside).
 */
@Composable
private fun StillGeometryPhoto(image: ImageBitmap?, mapping: GeometryMapping, controller: PhotoController) {
    if (image == null) return
    val bitmap = image.asAndroidBitmap()
    Canvas(Modifier.fillMaxSize()) {
        val view = controller.view ?: return@Canvas
        // Screen = (output - centre) * scale + slot centre; output = toOutput(image).
        val m = mapping.toOutput
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    m.a.toFloat(), m.b.toFloat(), m.tx.toFloat(),
                    m.c.toFloat(), m.d.toFloat(), m.ty.toFloat(),
                    0f, 0f, 1f,
                ),
            )
            postTranslate(-view.centerX, -view.centerY)
            postScale(view.scale, view.scale)
            postTranslate(size.width / 2f, size.height / 2f)
        }
        drawContext.canvas.nativeCanvas.withSave {
            if (!mapping.emptyIsSurround) {
                val left = (0f - view.centerX) * view.scale + size.width / 2f
                val top = (0f - view.centerY) * view.scale + size.height / 2f
                clipRect(left, top, left + mapping.outputWidth * view.scale, top + mapping.outputHeight * view.scale)
            }
            drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}

// ---------------------------------------------------------------------------------------------- eyedropper

@Composable
private fun Modifier.eyedropperGestures(
    controller: PhotoController,
    ready: EditorPhase.Ready,
    state: EditorUiState,
    actions: EditorActions,
): Modifier {
    val mapping = GeometryMapping.of(state.edit.recipe.geometry, ready.width, ready.height)
    val density = LocalDensity.current
    val sizePx = with(density) { LOUPE_SIZE.toPx() }
    val liftPx = with(density) { LOUPE_LIFT.toPx() }
    val currentActions by rememberUpdatedState(actions)
    val loupe = controller.loupe
    return pointerInput(controller, ready, mapping) {
        fun place(finger: Offset) {
            val half = sizePx / 2f
            val at = Offset(
                finger.x.coerceIn(half, size.width - half),
                (finger.y - liftPx).coerceIn(half, size.height - half),
            )
            loupe.finger = finger
            loupe.at = at
            controller.showLoupe(finger, at, sizePx)
        }
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            place(down.position)
            var last = down.position
            var change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            while (change != null && change.pressed) {
                change.consume()
                last = change.position
                place(last)
                change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            }
            change?.consume()
            loupe.finger = null
            controller.showLoupe(null, Offset.Zero, sizePx)
            controller.imagePoint(last, mapping)?.let { currentActions.onPickNeutral(it.x, it.y) }
        }
    }
}

/** The ring and crosshair drawn over the renderer's loupe pane. */
@Composable
private fun EyedropperLoupe(controller: PhotoController) {
    val loupe = controller.loupe
    if (loupe.finger == null) return
    val colors = RevealTheme.colors
    val density = LocalDensity.current
    val half = with(density) { LOUPE_SIZE.toPx() / 2f }
    Box(
        Modifier
            .offset { IntOffset((loupe.at.x - half).roundToInt(), (loupe.at.y - half).roundToInt()) }
            .size(LOUPE_SIZE)
            .border(BorderStroke(3.dp, colors.foreground), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(1.dp)
                .height(CROSSHAIR)
                .background(colors.foreground),
        )
        Box(
            Modifier
                .height(1.dp)
                .width(CROSSHAIR)
                .background(colors.foreground),
        )
    }
}

@Composable
private fun EyedropperHint(actions: EditorActions, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    Surface(
        modifier = modifier
            .padding(8.dp)
            .testTag(EditorTags.EYEDROPPER_HINT),
        shape = RevealTheme.shapes.chip,
        color = colors.card,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                PhosphorRegular.Eyedropper,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.editor_eyedropper_hint),
                style = RevealTheme.typography.ui,
                color = colors.foreground,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .widthIn(max = 240.dp),
            )
            RevealIconButton(
                icon = PhosphorRegular.X,
                contentDescription = stringResource(R.string.editor_eyedropper_cancel),
                onClick = { actions.onEyedropper(false) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------- states

/**
 * The instant preview over the canvas, on an opaque surround so nothing flashes while the GPU surface starts; it
 * fades out once the developed frame is on screen. Same fit geometry as the photo (centred in the frame).
 */
@Composable
private fun PreviewOverlay(state: EditorUiState, onFadeOut: () -> Unit) {
    val phase = state.phase
    val still = phase is EditorPhase.Ready && phase.renderer == null
    val hidden = state.developed || state.phase is EditorPhase.Failed || still
    val alpha by animateFloatAsState(
        targetValue = if (hidden) 0f else 1f,
        animationSpec = RevealMotion.standard(),
        label = "preview",
        finishedListener = { if (it == 0f) onFadeOut() },
    )
    if (alpha == 0f || state.phase is EditorPhase.Failed) return
    val preview = state.preview
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RevealTheme.colors.background.copy(alpha = alpha)),
    ) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = alpha,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(EditorTags.PREVIEW),
            )
        }
    }
}

@Composable
private fun LoadingBadge(progress: Float, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    val description = stringResource(R.string.editor_loading_progress, (progress * PERCENT).roundToInt())
    val animated by animateFloatAsState(progress, RevealMotion.standard(), label = "progress")
    Surface(
        modifier = modifier
            .padding(16.dp)
            .testTag(EditorTags.LOADING)
            .semantics { contentDescription = description },
        shape = RevealTheme.shapes.card,
        color = colors.card,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.editor_loading), style = RevealTheme.typography.ui, color = colors.foreground)
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.width(PROGRESS_WIDTH),
                color = colors.primary,
                trackColor = colors.muted,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun ErrorState(error: OpenError, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    val (title, body) = error.strings()
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .padding(24.dp)
            .testTag(EditorTags.ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = PhosphorRegular.WarningCircle,
            contentDescription = null,
            tint = colors.mutedForeground,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(title),
            style = RevealTheme.typography.title,
            color = colors.foreground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(body),
            style = RevealTheme.typography.body,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton(text = stringResource(R.string.editor_error_back), onClick = onBack)
    }
}

private fun OpenError.strings(): Pair<Int, Int> = when (this) {
    OpenError.PERMISSION_LOST -> R.string.editor_error_permission_title to R.string.editor_error_permission_body
    OpenError.UNSUPPORTED_FORMAT -> R.string.editor_error_format_title to R.string.editor_error_format_body
    OpenError.UNSUPPORTED_CAMERA -> R.string.editor_error_camera_title to R.string.editor_error_camera_body
    OpenError.CORRUPT -> R.string.editor_error_corrupt_title to R.string.editor_error_corrupt_body
    OpenError.GPU_UNSUPPORTED -> R.string.editor_error_gpu_title to R.string.editor_error_gpu_body
    OpenError.OUT_OF_MEMORY -> R.string.editor_error_memory_title to R.string.editor_error_memory_body
}

private const val PERCENT = 100
private val PROGRESS_WIDTH = 160.dp
private val LOUPE_SIZE = 112.dp
private val LOUPE_LIFT = 96.dp
private val CROSSHAIR = 16.dp
