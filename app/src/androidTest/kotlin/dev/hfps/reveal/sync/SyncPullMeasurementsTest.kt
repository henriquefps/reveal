package dev.hfps.reveal.sync

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.library.LibraryMetrics
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library while a big first pull lands (ADR 0018 "Pull"): another device puts 10,000 records on the server (5,000
 * photos only it has, each with an edited version); this device pairs, and while its first sync applies them we open
 * the library and fling a 400-photo folder with real touches (`input swipe`), as `LibraryMeasurementsTest` does.
 * The same is measured before pairing as the baseline. Logs `REVEAL_SYNC`; asserts nothing (a measurement). Needs
 * `-e syncLinks <link>,<link>` (scripts/sync/local-server.sh); skipped without.
 */
@RunWith(AndroidJUnit4::class)
class SyncPullMeasurementsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val links = InstrumentationRegistry.getArguments().getString("syncLinks").orEmpty().split(',')
        .filter { it.isNotBlank() }
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val folder = SyntheticFolder("pull-measure")

    @After
    fun tearDown() {
        runBlocking { access.sync().unpair() }
        folder.delete()
    }

    @Suppress("LongMethod")
    @Test
    fun libraryDuringATenThousandRecordPull() {
        assumeTrue("needs -e syncLinks <link>,<link>", links.size >= 2)
        runBlocking { access.sync().unpair() }
        seed(checkNotNull(PairingLink.parse(links[0])))

        val previews = (0 until VARIANTS).map { folder.gradientJpeg(it, 640, 427, 80) }
        for (i in 0 until LOCAL_PHOTOS) folder.jpeg("IMG_%05d.JPG".format(i), i, previews[i % previews.size])
        val (sourceId, _) = folder.addAndIndex(timeoutMs = 300_000)
        runBlocking { access.library().setLastFilters(PhotoFilters.folder(sourceId)) }

        val baseline = measure("baseline")
        val sync = access.sync()
        runBlocking { sync.pair(checkNotNull(PairingLink.parse(links[1]))) }
        val started = System.nanoTime()
        var pullMs = -1L
        var report: Any? = null
        val pull = thread(name = "first-pull") {
            report = runBlocking { sync.syncMetadata() }
            pullMs = (System.nanoTime() - started) / NANOS_PER_MS
        }
        Thread.sleep(PULL_HEAD_START_MS)
        val during = measure("during pull")
        val overlapped = pull.isAlive
        pull.join(PULL_TIMEOUT_MS)
        log("first pull of $RECORDS records: $pullMs ms ($report); the measurement overlapped the pull: $overlapped")
        log("open: baseline ${baseline.first} ms, during pull ${during.first} ms")
    }

    /** Another device's 5,000 photos with an edit each: 10,000 records. */
    @Suppress("LongMethod")
    private fun seed(link: PairingLink) {
        val paired = SyncApi(link.url, null).pair(PairRequest(link.token, "Seeder (test)", "android", "test"))
        val api = SyncApi(link.url, paired.apiKey)
        val run = UUID.randomUUID().toString()
        val recipe = SyncApi.json.parseToJsonElement(
            RecipeCodec.Default.encode(Recipe().let { it.copy(light = it.light.copy(exposureEv = 0.4f)) }),
        )
        val now = System.currentTimeMillis()
        val start = System.nanoTime()
        val changes = (0 until RECORDS / 2).flatMap { i ->
            val photo = UUID.nameUUIDFromBytes("$run-p$i".toByteArray()).toString()
            val version = UUID.nameUUIDFromBytes("$run-v$i".toByteArray()).toString()
            val fingerprint = "v1:" + MessageDigest.getInstance("SHA-256").digest("$run-$i".toByteArray())
                .joinToString("") { "%02x".format(it) }
            listOf(
                PushChange(
                    "photo",
                    photo,
                    0,
                    now,
                    data = buildJsonObject {
                        put("fingerprint", fingerprint)
                        put("name", "DSC%05d.ARW".format(i))
                        put("format", "RAW")
                        put("captureTime", 1_780_000_000_000L + i * 60_000L)
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("fingerprint", fingerprint)
                                        put("role", "RAW")
                                        put("name", "DSC%05d.ARW".format(i))
                                        put("size", 25_000_000)
                                    },
                                )
                            },
                        )
                    },
                ),
                PushChange(
                    "version",
                    version,
                    0,
                    now,
                    data = buildJsonObject {
                        put("photoId", photo)
                        put("position", 0)
                        put("recipe", recipe)
                        put("cursor", 0)
                        put(
                            "history",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("recipe", recipe)
                                        put("label", JsonArray(emptyList()))
                                    },
                                ),
                            ),
                        )
                    },
                ),
            )
        }
        changes.chunked(PUSH_BATCH).forEach { api.push(it) }
        log("seeded $RECORDS records in ${(System.nanoTime() - start) / NANOS_PER_MS} ms")
    }

    /** Opens the library on the folder and flings it; returns the first-grid-frame time and the frame times. */
    private fun measure(label: String): Pair<Long, List<Double>> {
        LibraryMetrics.reset()
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        var open = -1L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val end = System.currentTimeMillis() + OPEN_TIMEOUT_MS
            while (LibraryMetrics.firstGridFrameMs < 0 && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)
            open = LibraryMetrics.firstGridFrameMs
            Thread.sleep(THUMBS_SETTLE_MS)
            val thread = HandlerThread("frame-metrics").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            }
            var width = 0
            var height = 0
            var hz = 60f
            scenario.onActivity {
                hz = it.display.refreshRate
                width = it.window.decorView.width
                height = it.window.decorView.height
                it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            }
            val automation = instrumentation.uiAutomation
            repeat(FLINGS) { i ->
                val (from, to) = if (i % 2 ==
                    0
                ) {
                    (height * 0.75f).toInt() to (height * 0.3f).toInt()
                } else {
                    (height * 0.3f).toInt() to
                        (height * 0.75f).toInt()
                }
                ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand(
                        "input touchscreen swipe ${width / 2} $from ${width / 2} $to $SWIPE_MS",
                    ),
                ).use { it.readBytes() }
                Thread.sleep(BETWEEN_FLINGS_MS)
            }
            Thread.sleep(SETTLE_MS)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            val sorted = frames.map { it / 1_000_000.0 }.sorted()
            if (sorted.isNotEmpty()) {
                fun pct(p: Double) = "%.1f".format(sorted[((sorted.size - 1) * p).toInt()])
                val budget = 1000.0 / hz
                val late = sorted.count { it > budget }
                log(
                    "$label: open $open ms; ${sorted.size} frames at ${"%.0f".format(hz)} Hz, p50 ${pct(0.5)} ms, " +
                        "p90 ${pct(0.9)} ms, p99 ${pct(0.99)} ms, max ${"%.1f".format(sorted.last())} ms, over " +
                        "${"%.1f".format(budget)} ms: $late (${"%.1f".format(100.0 * late / sorted.size)} %)",
                )
            }
        }
        return open to frames.map { it / 1_000_000.0 }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "REVEAL_SYNC"
        const val RECORDS = 10_000
        const val PUSH_BATCH = 500
        const val LOCAL_PHOTOS = 400
        const val VARIANTS = 12
        const val FLINGS = 16
        const val SWIPE_MS = 120
        const val BETWEEN_FLINGS_MS = 600L
        const val SETTLE_MS = 1_000L
        const val THUMBS_SETTLE_MS = 1_500L
        const val POLL_MS = 20L
        const val OPEN_TIMEOUT_MS = 30_000L
        const val PULL_HEAD_START_MS = 1_500L
        const val PULL_TIMEOUT_MS = 600_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
