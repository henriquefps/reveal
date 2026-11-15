package dev.hfps.reveal.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.hfps.reveal.benchmark.BenchApp.flingLibrary
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Flings through the 10,000-photo library grid with thumbnails loading (docs/adr/0006 "Measurements"): frame times
 * and missed deadlines from `dumpsys gfxinfo` (120 Hz on the tablet and the S24 Ultra). Each iteration
 * starts a new process (the thumbnail memory cache empty; the disk cache warm after the first), so cells load while
 * they fling in.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(Parameterized::class)
class LibraryScrollBenchmark(private val compilation: CompilationMode, private val grouping: Grouping) {
    /**
     * How the grid is grouped while it flings (W3/D, the date headers' cost): [DAY] = month and day headers (the
     * default), [MONTH] = month headers only, [OFF] = sorted by file name, which has no headers at all.
     */
    enum class Grouping(val menuLabel: String) {
        DAY("Group by day"),
        MONTH("Group by month"),
        OFF("File name"),
    }

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun flingTheGrid() = rule.measureRepeated(
        packageName = BenchApp.PACKAGE,
        // dumpsys gfxinfo: this device's traces carry no RenderThread slices, which FrameTimingMetric needs.
        metrics = listOf(FrameTimingGfxInfoMetric()),
        compilationMode = compilation,
        // A cold process each iteration, started by the setup: StartupMode.COLD would kill it after the setup.
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            killProcess()
            pressHome()
            startActivityAndWait()
            Thread.sleep(SETTLE_MS)
            chooseGrouping()
        },
    ) {
        flingLibrary(FLINGS)
    }

    /** The sort menu's item for [grouping] (the grouping persists across processes, the sort does not). */
    private fun chooseGrouping() {
        val device = BenchApp.device
        device.wait(Until.findObject(By.desc("Sort")), MENU_WAIT_MS)?.click() ?: error("no sort menu")
        device.wait(Until.findObject(By.text(grouping.menuLabel)), MENU_WAIT_MS)?.click()
            ?: error("no '${grouping.menuLabel}'")
        Thread.sleep(SETTLE_MS)
    }

    companion object {
        private const val MENU_WAIT_MS = 5_000L
        private const val ITERATIONS = 5
        private const val FLINGS = 10
        private const val SETTLE_MS = 1_500L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun modes(): List<Array<Any>> =
            listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))
                .flatMap { mode -> Grouping.entries.map { arrayOf<Any>(mode, it) } }

        @JvmStatic
        @BeforeClass
        fun library() = BenchApp.prepareLibrary()
    }
}
