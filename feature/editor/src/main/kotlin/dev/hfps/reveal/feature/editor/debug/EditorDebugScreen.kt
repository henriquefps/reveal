package dev.hfps.reveal.feature.editor.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.canvas.ZoomableGlCanvas
import dev.hfps.reveal.feature.editor.canvas.animateDoubleTap
import dev.hfps.reveal.feature.editor.canvas.rememberZoomState
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.gl.FrameStats
import dev.hfps.reveal.pipeline.gl.GlesRequirementException
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PROXY_WIDTH = 2400
private const val PROXY_HEIGHT = 1600
private const val EXPOSURE_RANGE = 3f
private const val TEMPERATURE_MIN = 2500f
private const val TEMPERATURE_MAX = 12000f

/**
 * Temporary, debug-only screen for M0: the zoomable GPU canvas on a synthetic 2400 px proxy with two plain sliders
 * (exposure, temperature) wired to the recipe, to prove live re-rendering. Replaced by the real editor in M1.
 */
@Composable
fun EditorDebugScreen(modifier: Modifier = Modifier) {
    val renderer by produceState<Result<GpuDevelopRenderer>?>(initialValue = null) {
        value = runCatching { GpuDevelopRenderer.create() }
    }
    val created = renderer?.getOrNull()
    DisposableEffect(created) { onDispose { created?.close() } }
    val input by produceState<PipelineInput?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { SyntheticImages.scene(PROXY_WIDTH, PROXY_HEIGHT) }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        val ready = renderer?.getOrNull()
        val image = input
        when {
            renderer?.isFailure == true -> {
                val failure = renderer?.exceptionOrNull()
                val reason = (failure as? GlesRequirementException)?.message ?: failure?.message.orEmpty()
                Text(
                    text = stringResource(R.string.debug_gpu_unsupported, reason),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            ready == null || image == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            else -> DebugCanvas(ready, image, Modifier.fillMaxSize())
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun DebugCanvas(renderer: GpuDevelopRenderer, input: PipelineInput, modifier: Modifier = Modifier) {
    val asShot = remember(input) { WhiteBalanceModel.asShot(input) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var temperature by remember(asShot) { mutableFloatStateOf(asShot.temperatureK.toFloat()) }
    val zoom = rememberZoomState(renderer)
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<FrameStats?>(null) }
    DisposableEffect(renderer) {
        renderer.measureGpuTime = true
        renderer.onFrame = { stats = it }
        onDispose { renderer.onFrame = null }
    }
    val recipe = remember(exposure, temperature) {
        Recipe(
            light = Light(exposureEv = exposure),
            colour = Colour(WhiteBalance(temperatureK = temperature.roundToInt().toFloat())),
        )
    }
    // The canvas takes only the space above the panel (docs/plan/ux.md), clipped to its bounds; the panel is opaque.
    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            ZoomableGlCanvas(
                renderer = renderer,
                input = input,
                recipe = recipe,
                modifier = Modifier.fillMaxSize(),
                zoom = zoom,
            )
            TextButton(
                onClick = { scope.launch { zoom.animateDoubleTap(zoom.viewCenterX, zoom.viewCenterY) } },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(stringResource(R.string.debug_toggle_zoom))
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.debug_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SliderRow(
                    label = stringResource(R.string.debug_exposure),
                    value = exposure,
                    valueText = String.format(Locale.ROOT, "%+.2f EV", exposure),
                    range = -EXPOSURE_RANGE..EXPOSURE_RANGE,
                    onValueChange = { exposure = it },
                )
                SliderRow(
                    label = stringResource(R.string.debug_temperature),
                    value = temperature,
                    valueText = stringResource(R.string.debug_kelvin, temperature.roundToInt()),
                    range = TEMPERATURE_MIN..TEMPERATURE_MAX,
                    onValueChange = { temperature = it },
                )
                stats?.let {
                    Text(
                        text = stringResource(R.string.debug_frame_stats, it.gpuWallMs, it.frames, it.updates),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}
