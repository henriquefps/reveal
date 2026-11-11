package dev.hfps.reveal.feature.editor

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.canvas.ZoomableGlCanvas
import dev.hfps.reveal.feature.editor.canvas.animateDoubleTap
import dev.hfps.reveal.feature.editor.canvas.rememberZoomState
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Test tags of the editor, for instrumented tests. */
object EditorTags {
    const val CANVAS = "editor-canvas"
    const val PREVIEW = "editor-preview"
    const val LOADING = "editor-loading"
    const val DEVELOPED = "editor-developed"
    const val ERROR = "editor-error"
    const val EXPOSURE = "editor-exposure"
    const val ZOOM = "editor-zoom"
}

/** The editor (M0 shape) of the photo in its [EditorRoute]: always dark, canvas plus the debug develop panel. */
@Composable
fun EditorScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    EditorTheme {
        EditorContent(
            state = state,
            actions = EditorActions(
                onBack = onBack,
                onDeveloped = viewModel::onDeveloped,
                onPreviewHidden = viewModel::onPreviewHidden,
                onExposure = viewModel::setExposure,
                onTemperature = viewModel::setTemperature,
                onTint = viewModel::setTint,
                onReset = viewModel::resetControls,
            ),
            modifier = modifier,
        )
    }
}

/** Everything the editor content can ask for. */
class EditorActions(
    val onBack: () -> Unit,
    val onDeveloped: () -> Unit = {},
    val onPreviewHidden: () -> Unit = {},
    val onExposure: (Float) -> Unit = {},
    val onTemperature: (Float) -> Unit = {},
    val onTint: (Float) -> Unit = {},
    val onReset: () -> Unit = {},
)

/**
 * Stateless editor content (inside `EditorTheme`): top bar with the file name, the canvas area (preview, GPU canvas,
 * progress, error) and the develop panel. Public for screenshot tests of the loading and error states.
 */
@Composable
fun EditorContent(state: EditorUiState, actions: EditorActions, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        RevealTopBar(
            title = state.fileName,
            onBack = actions.onBack,
            backContentDescription = stringResource(R.string.editor_back),
            titleStyle = RevealTheme.typography.mono,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .testTag(EditorTags.CANVAS),
        ) {
            val phase = state.phase
            if (phase is EditorPhase.Ready) {
                DevelopedCanvas(phase, state.controls.recipe, onFirstFrame = actions.onDeveloped)
            }
            PreviewOverlay(state, onFadeOut = actions.onPreviewHidden)
            when (phase) {
                is EditorPhase.Loading -> LoadingBadge(phase.progress, Modifier.align(Alignment.BottomCenter))

                is EditorPhase.Failed -> ErrorState(phase.error, actions.onBack, Modifier.align(Alignment.Center))

                is EditorPhase.Ready -> if (state.developed) {
                    Box(Modifier.size(1.dp).testTag(EditorTags.DEVELOPED))
                }
            }
        }
        if (state.phase !is EditorPhase.Failed) {
            DevelopPanel(state.controls, enabled = state.phase is EditorPhase.Ready, actions = actions)
        }
    }
}

/**
 * The GPU canvas of a ready photo. Reports the first presented frame through [onFirstFrame] (on the main thread) and
 * every frame to [EditorMetrics].
 */
@Composable
private fun DevelopedCanvas(ready: EditorPhase.Ready, recipe: Recipe, onFirstFrame: () -> Unit) {
    val renderer = ready.renderer
    val zoom = rememberZoomState(renderer, ready.hundredPercentScale)
    val scope = rememberCoroutineScope()
    val currentOnFirstFrame by rememberUpdatedState(onFirstFrame)
    DisposableEffect(renderer) {
        val main = Handler(Looper.getMainLooper())
        val first = AtomicBoolean(true)
        renderer.onFrame = { stats ->
            EditorMetrics.frame(stats)
            if (first.getAndSet(false)) main.post { currentOnFirstFrame() }
        }
        onDispose { renderer.onFrame = null }
    }
    Box(Modifier.fillMaxSize()) {
        ZoomableGlCanvas(
            renderer = renderer,
            imageWidth = ready.width,
            imageHeight = ready.height,
            recipe = recipe,
            modifier = Modifier.fillMaxSize(),
            zoom = zoom,
        )
        // RevealIconButton's tooltip anchor does not forward parent data (align), hence the wrapping box.
        Box(Modifier.align(Alignment.TopEnd)) {
            RevealIconButton(
                icon = PhosphorRegular.MagnifyingGlassPlus,
                contentDescription = stringResource(R.string.editor_zoom_toggle),
                onClick = { scope.launch { zoom.animateDoubleTap(zoom.viewCenterX, zoom.viewCenterY) } },
                modifier = Modifier.testTag(EditorTags.ZOOM),
            )
        }
    }
}

/**
 * The instant preview over the canvas, on an opaque surround so nothing flashes while the GPU surface starts; it
 * fades out once the developed frame is on screen. Same fit geometry as the canvas (centred, whole image visible).
 */
@Composable
private fun PreviewOverlay(state: EditorUiState, onFadeOut: () -> Unit) {
    val hidden = state.developed || state.phase is EditorPhase.Failed
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

/** The M0 debug panel (docs/plan/ux.md panel card): exposure, temperature and tint wired to the recipe. */
@Composable
private fun DevelopPanel(controls: DevelopControls, enabled: Boolean, actions: EditorActions) {
    val colors = RevealTheme.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        shape = RevealTheme.shapes.card,
        color = colors.card,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(stringResource(R.string.editor_panel_develop), Modifier.weight(1f))
                RevealIconButton(
                    icon = PhosphorRegular.ArrowCounterClockwise,
                    contentDescription = stringResource(R.string.editor_reset),
                    onClick = actions.onReset,
                    enabled = enabled,
                )
            }
            Column(Modifier.padding(end = 12.dp)) {
                DevelopSlider(
                    label = stringResource(R.string.editor_exposure),
                    valueText = stringResource(R.string.editor_value_ev, controls.exposureEv),
                    value = controls.exposureEv,
                    range = -EXPOSURE_RANGE..EXPOSURE_RANGE,
                    onChange = actions.onExposure,
                    enabled = enabled,
                    modifier = Modifier.testTag(EditorTags.EXPOSURE),
                )
                val temperature = controls.temperatureK ?: controls.asShotTemperatureK
                DevelopSlider(
                    label = stringResource(R.string.editor_temperature),
                    valueText = stringResource(R.string.editor_value_kelvin, temperature.roundToInt()),
                    value = temperature,
                    range = TEMPERATURE_RANGE,
                    onChange = { actions.onTemperature(it.roundToInt().toFloat()) },
                    enabled = enabled,
                )
                val tint = controls.tint ?: controls.asShotTint
                DevelopSlider(
                    label = stringResource(R.string.editor_tint),
                    valueText = stringResource(R.string.editor_value_tint, tint.roundToInt()),
                    value = tint,
                    range = -TINT_RANGE..TINT_RANGE,
                    onChange = { actions.onTint(it.roundToInt().toFloat()) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun DevelopSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(label, style = RevealTheme.typography.ui, color = colors.foreground, modifier = Modifier.weight(1f))
            Text(valueText, style = RevealTheme.typography.value, color = colors.mutedForeground)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.height(SLIDER_HEIGHT),
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.muted,
            ),
        )
    }
}

private const val PERCENT = 100
private const val EXPOSURE_RANGE = 5f
private const val TINT_RANGE = 150f
private val TEMPERATURE_RANGE =
    WhiteBalanceModel.MIN_TEMPERATURE_K.toFloat()..WhiteBalanceModel.MAX_TEMPERATURE_K.toFloat()
private val PROGRESS_WIDTH = 160.dp
private val SLIDER_HEIGHT = 36.dp
