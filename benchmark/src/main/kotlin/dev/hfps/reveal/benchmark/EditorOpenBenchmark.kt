package dev.hfps.reveal.benchmark

import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Opening a 24 MP RAW (the CC0 `sony-zv-e10.arw` in the bench app's `files/samples`, pushed by
 * scripts/bench/macrobench.sh) in a cold process, as from another app: `BenchActivity --es mode open` hands it to the
 * editor. Metrics: the editor's own trace sections (`EditorMetrics`: to the embedded preview on screen, to the first
 * developed GPU frame), and its logged timings (`REVEAL_BENCH open:` lines).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(Parameterized::class)
class EditorOpenBenchmark(private val compilation: CompilationMode) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun openRaw() = rule.measureRepeated(
        packageName = BenchApp.PACKAGE,
        metrics = listOf(
            TraceSectionMetric(PREVIEW, TraceSectionMetric.Mode.First),
            TraceSectionMetric(DEVELOPED, TraceSectionMetric.Mode.First),
        ),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        val line = BenchApp.run("open", "open: done", BenchApp.JOURNEY_TIMEOUT_MS)
        Log.i("REVEAL_BENCH", "editor open ($compilation): $line")
    }

    companion object {
        private const val ITERATIONS = 8
        private const val PREVIEW = "RevealEditor.openToPreview"
        private const val DEVELOPED = "RevealEditor.openToDeveloped"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<CompilationMode> =
            listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))
    }
}
