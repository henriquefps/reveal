package dev.hfps.reveal.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import dev.hfps.reveal.benchmark.BenchApp.flingLibrary
import org.junit.Rule
import org.junit.Test

/**
 * Collects the Baseline Profile and the startup profile (docs/adr/0001 "Baseline Profile") from the unminified bench
 * app (`benchProfile`): cold start to the 10,000-photo library and a few flings, then `BenchActivity`'s journey (the
 * editor opening a 24 MP RAW, the Presets, Detail, Masks and Heal tools, a mask, a heal spot and a Remove stroke).
 * scripts/bench/macrobench.sh profile copies the result to app/src/main/baselineProfiles/.
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** Start to the library and a few flings: the startup profile (dex layout) and part of the Baseline Profile. */
    @Test
    fun startup() {
        BenchApp.prepareLibrary()
        rule.collect(packageName = BenchApp.PACKAGE, includeInStartupProfile = true, maxIterations = ITERATIONS) {
            pressHome()
            startActivityAndWait()
            Thread.sleep(SETTLE_MS)
            flingLibrary(FLINGS)
        }
    }

    /** The editor's journey: the rest of the Baseline Profile (not in the startup profile). */
    @Test
    fun editor() {
        rule.collect(packageName = BenchApp.PACKAGE, includeInStartupProfile = false, maxIterations = ITERATIONS) {
            pressHome()
            BenchApp.run("journey", "journey: done", BenchApp.JOURNEY_TIMEOUT_MS)
        }
    }

    private companion object {
        const val ITERATIONS = 3
        const val FLINGS = 4
        const val SETTLE_MS = 1_000L
    }
}
