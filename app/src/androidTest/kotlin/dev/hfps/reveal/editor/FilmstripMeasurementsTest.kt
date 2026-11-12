package dev.hfps.reveal.editor

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
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
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.editor.EditorMetrics
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tablet filmstrip with a large list (M2/E): 1,200 JPEGs handed over by "another app" (ADR 0011), then a DEVELOP
 * intent with an initial photo opens the editor with "Opened from other apps" as its filmstrip, and real touches
 * (`input swipe`) fling through the strip while thumbnails load. Logs (`REVEAL_FILMSTRIP`) the frame times
 * (FrameMetrics). No Compose test rule: it drives frames only while it synchronizes, which would starve the flings.
 * Run on its own on a tablet emulator: `-e class dev.hfps.reveal.editor.FilmstripMeasurementsTest`.
 */
@RunWith(AndroidJUnit4::class)
class FilmstripMeasurementsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val inserted = mutableListOf<Uri>()

    @After
    fun tearDown() = inserted.forEach(TestPhotos::delete)

    @Suppress("LongMethod") // One measurement script, read top to bottom.
    @Test
    fun twelveHundredPhotos() {
        assumeTrue(context.resources.configuration.screenWidthDp >= TABLET_DP)
        val stem = "FS${System.nanoTime() % 1_000_000_000}"
        val previews = (0 until VARIANTS).map { variant(it) }
        val started = System.nanoTime()
        for (i in 0 until PHOTOS) {
            inserted += TestPhotos.insert("${stem}_%05d.jpg".format(i), "image/jpeg", previews[i % previews.size])
        }
        log("inserted $PHOTOS photos in ${(System.nanoTime() - started) / 1_000_000} ms")
        val handed = System.nanoTime()
        runBlocking { access.handoffs().receive(HandoffRequest(uris = inserted.toList())) }
        log("handed over in ${(System.nanoTime() - handed) / 1_000_000} ms")

        // The editor on one photo of the batch, the batch behind it: its filmstrip is "Opened from other apps".
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).setType("image/*")
            .putExtra(HandoffIntents.EXTRA_INITIAL_URI, inserted[0])
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.clipData = ClipData.newRawUri("photos", inserted[0]).apply { addItem(ClipData.Item(inserted[1])) }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitFor(TIMEOUT_MS) { EditorMetrics.renderer != null }
            Thread.sleep(SETTLE_MS)
            var width = 0
            var height = 0
            var bottom = 0
            var density = 1f
            scenario.onActivity {
                width = it.window.decorView.width
                height = it.window.decorView.height
                density = it.resources.displayMetrics.density
                bottom = it.window.decorView.rootWindowInsets
                    ?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
            }
            // The strip is the bottom row of the photo column (left of the 400 dp panel and the rail).
            val y = height - bottom - (STRIP_DP / 2 * density).toInt()
            val photoColumn = width - ((PANEL_DP + RAIL_DP) * density).toInt()
            val right = (photoColumn * 0.85f).toInt()
            val left = (photoColumn * 0.15f).toInt()
            log("window ${width}x$height, strip at y $y, x $left..$right, $PHOTOS photos")

            val frames = Collections.synchronizedList(mutableListOf<Long>())
            val missed = java.util.concurrent.atomic.AtomicInteger()
            val thread = HandlerThread("frame-metrics").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > metrics.getMetric(FrameMetrics.DEADLINE)) {
                    missed.incrementAndGet()
                }
            }
            var refreshHz = 60f
            scenario.onActivity {
                refreshHz = it.display.refreshRate
                it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            }
            val automation = instrumentation.uiAutomation
            repeat(FLINGS) { i ->
                val (from, to) = if (i < FLINGS - 4) right to left else left to right
                val command = automation.executeShellCommand("input touchscreen swipe $from $y $to $y $SWIPE_MS")
                ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
                Thread.sleep(BETWEEN_FLINGS_MS)
            }
            Thread.sleep(SETTLE_MS)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            report(frames.map { it / 1_000_000.0 }.sorted(), refreshHz, missed.get())
        }
    }

    /** A small JPEG gradient (a few variants are enough: the trailer makes every inserted file unique). */
    private fun variant(seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val hue = (seed * 47 % 360).toFloat()
        val a = Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.9f))
        val b = Color.HSVToColor(floatArrayOf((hue + 50) % 360, 0.7f, 0.35f))
        Canvas(bitmap).drawPaint(
            Paint().apply {
                shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), a, b, Shader.TileMode.CLAMP)
            },
        )
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun report(frames: List<Double>, refreshHz: Float, missed: Int) {
        if (frames.isEmpty()) {
            log("filmstrip: no frames")
            return
        }
        fun pct(p: Double) = "%.1f".format(frames[((frames.size - 1) * p).toInt()])
        val budget = 1000.0 / refreshHz
        val janky = frames.count { it > budget }
        log(
            "filmstrip fling: ${frames.size} frames at ${"%.0f".format(refreshHz)} Hz; p50 ${pct(0.5)} ms, p90 " +
                "${pct(0.9)} ms, p95 ${pct(0.95)} ms, p99 ${pct(0.99)} ms, max ${pct(1.0)} ms; over " +
                "${"%.1f".format(
                    budget,
                )} ms: $janky (${"%.1f".format(100.0 * janky / frames.size)} %); past deadline " +
                "$missed (${"%.1f".format(100.0 * missed / frames.size)} %)",
        )
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "REVEAL_FILMSTRIP"
        const val TABLET_DP = 840
        const val PHOTOS = 1_200
        const val VARIANTS = 24
        const val WIDTH = 480
        const val HEIGHT = 320
        const val QUALITY = 80
        const val FLINGS = 16
        const val SWIPE_MS = 120
        const val BETWEEN_FLINGS_MS = 700L
        const val SETTLE_MS = 2_000L
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 50L
        const val STRIP_DP = 76
        const val PANEL_DP = 416
        const val RAIL_DP = 80
    }
}
