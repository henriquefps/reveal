package dev.hfps.reveal.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cold start to the library grid with the 10,000-photo library (docs/adr/0006 "Measurements", docs/adr/0001
 * "Baseline Profile"): time to initial display (first frame) and to full display (`ReportDrawnWhen`: the grid's first
 * rows), without ahead-of-time compilation (a sideloaded APK before its profile is applied) and with the profile.
 */
@RunWith(Parameterized::class)
class StartupBenchmark(private val compilation: CompilationMode) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartToLibrary() = rule.measureRepeated(
        packageName = BenchApp.PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    companion object {
        private const val ITERATIONS = 10

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<CompilationMode> =
            listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))

        @JvmStatic
        @BeforeClass
        fun library() = BenchApp.prepareLibrary()
    }
}
