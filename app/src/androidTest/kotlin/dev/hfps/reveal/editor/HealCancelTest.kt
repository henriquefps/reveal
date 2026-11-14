package dev.hfps.reveal.editor

import android.app.UiAutomation
import android.os.Debug
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.heal.RemoveMethod
import dev.hfps.reveal.pipeline.heal.RemoveOutput
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/adr/0023 "FIX/H": healing work never outlives the editor. A Remove spot whose method runs until it is
 * cancelled (a test method registered with the process's fills) is placed, the photo is closed while it runs: the
 * method sees the cancellation, the fills' worker is idle, no heal worker thread is left and the Java heap returns.
 * Then a stress run on the 24 MP CC0 RAW (`files/samples/sony-zv-e10.arw`, skipped without it): Remove strokes moved
 * at 60 Hz (Patch and AI), the screen rotated and memory trimmed during fills, the photo closed mid-fill, three times;
 * the app must survive (`REVEAL_HEAL_TEST`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HealCancelTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Suppress("LongMethod")
    @Test
    fun closingThePhotoMidFillStopsEveryWorker() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val running = AtomicInteger()
        RemoveFills.shared.register(
            SLOW,
            RemoveMethod { input ->
                running.incrementAndGet()
                started.countDown()
                try {
                    while (true) {
                        input.checkCancelled()
                        Thread.sleep(2)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    RemoveOutput(input.rgb)
                } finally {
                    running.decrementAndGet()
                    stopped.countDown()
                }
            },
        )
        val uri = TestPhotos.insert(
            TestPhotos.uniqueName("reveal-heal-cancel.jpg"),
            "image/jpeg",
            TestPhotos.syntheticJpeg(),
        )
        // Other renderers of the process (the library's thumbnails, an earlier test's) may have heal threads of their
        // own: only those this test's editor starts must end.
        val earlier = healThreads()
        try {
            // A first open and close fills the process's caches and singletons: the baseline is after it.
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                compose.waitForIdle()
            }
            waitFor("no heal worker thread") { (healThreads() - earlier).isEmpty() }
            gc()
            val before = heapUsed()
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                compose.waitForIdle()
                val (w, h) = checkNotNull(EditorMetrics.proxySize)
                val spot = HealScenes.stroke(
                    "slow",
                    Heal.KIND_REMOVE,
                    w,
                    h,
                    listOf(w * 0.4f to h * 0.5f, w * 0.6f to h * 0.5f),
                    w * 0.02f,
                ).copy(method = SLOW)
                checkNotNull(EditorMetrics.applyRecipe).invoke(Recipe(heals = listOf(spot)))
                assertThat(started.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()
            }
            // The editor is gone: its fill is cancelled, nothing runs any more.
            assertThat(stopped.await(STOP_MS, TimeUnit.MILLISECONDS)).isTrue()
            waitFor("the fills' worker idle") { RemoveFills.shared.activeJobs == 0 && running.get() == 0 }
            waitFor("no heal worker thread") { (healThreads() - earlier).isEmpty() }
            gc()
            val after = heapUsed()
            Log.i(
                TAG,
                "close mid-fill: heap before ${before / MB} MB, after ${after / MB} MB, " +
                    "fills ${RemoveFills.shared.stats}",
            )
            assertThat(after - before).isLessThan(HEAP_SLACK)
        } finally {
            TestPhotos.delete(uri)
        }
    }

    @Test
    fun stressRemoveOn24Mp() {
        val raw = TestPhotos.sample("sony-zv-e10.arw")
        assumeTrue("no files/samples/sony-zv-e10.arw", raw != null)
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
        val earlier = healThreads()
        val uri = TestPhotos.insert(
            TestPhotos.uniqueName("reveal-heal-stress.arw"),
            "image/x-sony-arw",
            checkNotNull(raw),
        )
        try {
            repeat(ROUNDS) { round ->
                TestPhotos.launchView(uri, "image/x-sony-arw").use {
                    compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), RAW_TIMEOUT_MS)
                    compose.waitForIdle()
                    val (w, h) = checkNotNull(EditorMetrics.proxySize)
                    val apply = checkNotNull(EditorMetrics.applyRecipe)
                    val methods = listOf(Heal.METHOD_PATCH) + RemoveFills.shared.models()
                    // A Remove stroke "dragged" at 60 Hz, each position a committed edit (the worst case).
                    for (i in 0 until DRAG_FRAMES) {
                        val x = 0.3f + 0.2f * i / DRAG_FRAMES
                        val spot = HealScenes.stroke(
                            "s$round",
                            Heal.KIND_REMOVE,
                            w,
                            h,
                            listOf(w * x to h * 0.5f, w * (x + 0.1f) to h * 0.52f),
                            w * 0.015f,
                        ).copy(method = methods[i % methods.size])
                        apply(Recipe(heals = listOf(spot)))
                        Thread.sleep(FRAME_MS)
                        if (i == DRAG_FRAMES / 3) {
                            automation.executeShellCommand("am send-trim-memory $PACKAGE RUNNING_LOW").close()
                        }
                        if (i == DRAG_FRAMES / 2) automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                    }
                    automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                    // Closed while the last fills run.
                    Thread.sleep(FRAME_MS * 5)
                }
                waitFor("fills idle after round $round") { RemoveFills.shared.activeJobs == 0 }
                Log.i(TAG, "stress round $round: fills ${RemoveFills.shared.stats}")
            }
            waitFor("no heal worker thread") { (healThreads() - earlier).isEmpty() }
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            TestPhotos.delete(uri)
        }
    }

    private fun healThreads(): Set<String> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name.startsWith("reveal-heal-") }
        .map { it.name }
        .toSet()

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_MS)
        while (!check()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun gc() {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(POLL_MS)
        }
    }

    private fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } +
        Debug.getNativeHeapAllocatedSize()

    private companion object {
        const val TAG = "REVEAL_HEAL_TEST"
        const val SLOW = "test:until-cancelled"
        const val PACKAGE = "dev.hfps.reveal.debug"
        const val TIMEOUT_MS = 30_000L
        const val RAW_TIMEOUT_MS = 90_000L
        const val STOP_MS = 20_000L
        const val POLL_MS = 50L
        const val MB = 1024 * 1024
        const val HEAP_SLACK = 16L * MB
        const val ROUNDS = 3
        const val DRAG_FRAMES = 60
        const val FRAME_MS = 16L
    }
}
