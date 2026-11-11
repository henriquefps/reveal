package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame time while dragging a slider at a ~2400 px proxy: every pass of the develop pipeline + the output pass per
 * frame (stage textures mip-mapped as on screen), 120 different recipes, `glFinish` after each. Prints `REVEAL_BENCH`
 * lines (milliseconds); the orchestrator re-runs it on the phone. Not an assertion test: numbers differ per device,
 * the log is the result.
 */
@RunWith(AndroidJUnit4::class)
class PipelineBenchmark {
    @Test
    fun exposureDragFrameTime(): Unit = runBlocking {
        drag("exposure", SyntheticImages.scene(WIDTH, HEIGHT)) { Recipe(light = Light(-2f + 4f * it)) }
    }

    /** Every Light slider active, dragging highlights/shadows: guide passes + local and global tone every frame. */
    @Test
    fun lightDragFrameTime(): Unit = runBlocking {
        drag("light (all sliders, highlights/shadows drag)", SyntheticImages.scene(WIDTH, HEIGHT)) {
            Recipe(
                light = Light(
                    -0.3f,
                    contrast = 35f,
                    highlights = -100f + 100f * it,
                    shadows = 100f - 60f * it,
                    whites = 20f,
                    blacks = -10f,
                ),
            )
        }
    }

    /** A RAW with clipped highlights while dragging temperature: the reconstruction pyramid reruns every frame. */
    @Test
    fun highlightReconstructionDragFrameTime(): Unit = runBlocking {
        val image = SyntheticImages.clippedHighlights(WIDTH, HEIGHT)
        drag("white balance on clipped highlights (reconstruction every frame)", image) {
            Recipe(
                light = Light(-1f, highlights = -60f, shadows = 40f),
                colour = Colour(WhiteBalance(temperatureK = 3000f + 6000f * it)),
            )
        }
    }

    /**
     * The costliest colour path (docs/adr/0008): Camera JPEG match (matrix, curve, hue treatment: one OkLab round
     * trip) plus vibrance and saturation (a second one), with Light's tone sliders on, dragging vibrance.
     */
    @Test
    fun colourDragFrameTime(): Unit = runBlocking {
        drag("colour (camera match + vibrance/saturation drag, light on)", SyntheticImages.scene(WIDTH, HEIGHT)) {
            Recipe(
                profile = Profile(BaseLook.CAMERA_MATCH, 120f),
                light = Light(0.3f, contrast = 20f, highlights = -40f, shadows = 30f),
                colour = Colour(vibrance = -100f + 200f * it, saturation = 25f),
            )
        }
    }

    private suspend fun drag(name: String, image: PipelineInput, recipeAt: (Float) -> Recipe) {
        val renderer = GpuDevelopRenderer.create()
        try {
            val uploadStart = System.nanoTime()
            renderer.setInput(image)
            renderer.benchmark(listOf(Recipe()))
            val uploadMs = (System.nanoTime() - uploadStart) / 1e6
            val sweep = List(FRAMES) { recipeAt(it.toFloat() / (FRAMES - 1)) }
            renderer.benchmark(sweep.take(WARMUP))
            val times = renderer.benchmark(sweep).sorted()
            val gpu = "${renderer.info.renderer} (${renderer.info.version})"
            val mean = "%.2f".format(times.average())
            val p50 = "%.2f".format(times[times.size / 2])
            val p95 = "%.2f".format(times[(times.size * 95) / 100])
            val max = "%.2f".format(times.last())
            val first = "%.1f".format(uploadMs)
            Log.i(
                TAG,
                "$name: $gpu ${image.width}x${image.height}: upload+first frame $first ms, " +
                    "frame mean $mean ms, p50 $p50, p95 $p95, max $max",
            )
        } finally {
            renderer.close()
        }
    }

    private companion object {
        const val TAG = "REVEAL_BENCH"
        const val WIDTH = 2400
        const val HEIGHT = 1600
        const val FRAMES = 120
        const val WARMUP = 10
    }
}
