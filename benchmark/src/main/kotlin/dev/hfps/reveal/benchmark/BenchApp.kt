package dev.hfps.reveal.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/**
 * The bench app (`dev.hfps.reveal.bench`: the minified `bench` build, or `benchProfile` while collecting the Baseline
 * Profile) and its adb-driven `BenchActivity` (app/src/bench). Never the release app.
 */
internal object BenchApp {
    const val PACKAGE = "dev.hfps.reveal.bench"
    private const val ACTIVITY = "$PACKAGE/dev.hfps.reveal.bench.BenchActivity"
    private const val TAG = "REVEAL_BENCH"
    private const val POLL_MS = 250L
    const val LIBRARY_TIMEOUT_MS = 900_000L
    const val JOURNEY_TIMEOUT_MS = 180_000L

    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * Runs one `BenchActivity` mode in the bench app's process and waits for its log line [done] (or a failure);
     * returns the matching line.
     */
    fun run(mode: String, done: String, timeoutMs: Long): String {
        device.executeShellCommand("logcat -c")
        device.executeShellCommand("am start -n $ACTIVITY --es mode $mode")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val lines = device.executeShellCommand("logcat -d -s $TAG").lines()
            lines.firstOrNull { "$mode: failed" in it }?.let { error(it) }
            lines.lastOrNull { done in it }?.let { return it.substringAfter("$TAG: ") }
            Thread.sleep(POLL_MS)
        }
        error("timed out waiting for '$done'")
    }

    /** The 10,000-photo library as the only source (made once, kept between runs). */
    fun prepareLibrary() {
        run("library", "library: ready", LIBRARY_TIMEOUT_MS)
    }

    /** Flings the library grid (the middle of the window) up [times] and back down. */
    fun MacrobenchmarkScope.flingLibrary(times: Int) {
        val x = device.displayWidth / 2
        val low = device.displayHeight * LOW / PARTS
        val high = device.displayHeight * HIGH / PARTS
        repeat(times) { device.swipe(x, low, x, high, FLING_STEPS) }
        repeat(times / 2) { device.swipe(x, high, x, low, FLING_STEPS) }
        device.waitForIdle()
    }

    /** Swipe steps of a fling (~5 ms each: a fast flick). */
    private const val FLING_STEPS = 10

    /** Flings between three quarters and one quarter of the display's height. */
    private const val PARTS = 4
    private const val LOW = 3
    private const val HIGH = 1
}
