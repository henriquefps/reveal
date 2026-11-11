package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame time while dragging the exposure slider at a ~2400 px proxy: stage 1 + output pass per frame, 120 different
 * exposures, `glFinish` after each. Prints `REVEAL_BENCH` lines (milliseconds); the orchestrator re-runs it on the
 * phone. Not an assertion test: numbers differ per device, the log is the result.
 */
@RunWith(AndroidJUnit4::class)
class PipelineBenchmark {
    @Test
    fun exposureDragFrameTime(): Unit = runBlocking {
        val renderer = GpuDevelopRenderer.create()
        try {
            val image = SyntheticImages.scene(WIDTH, HEIGHT)
            val uploadStart = System.nanoTime()
            renderer.setInput(image)
            renderer.benchmark(listOf(Recipe()))
            val uploadMs = (System.nanoTime() - uploadStart) / 1e6
            val sweep = List(FRAMES) { Recipe(light = Light(-2f + 4f * it / (FRAMES - 1))) }
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
                "$gpu ${WIDTH}x$HEIGHT: upload+first frame $first ms, " +
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
