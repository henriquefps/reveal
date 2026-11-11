package dev.hfps.reveal.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.format.ExifSnapshot
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.source.BlockCachedSource
import dev.hfps.image.tiff.Tags
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.export.destination.ShareCache
import dev.hfps.reveal.feature.export.engine.ExportEngine
import dev.hfps.reveal.feature.export.engine.ExportException
import dev.hfps.reveal.feature.export.engine.ExportFailure
import dev.hfps.reveal.feature.export.engine.ExportItem
import dev.hfps.reveal.feature.export.engine.ExportResult
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportFormat
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.model.MetadataOptions
import dev.hfps.reveal.feature.export.work.ExportJobItem
import dev.hfps.reveal.feature.export.work.ExportJobStore
import dev.hfps.reveal.feature.export.work.ExportWorker
import dev.hfps.reveal.feature.export.work.ItemStatus
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export end to end on a device (docs/adr/0009): the public ZV-E10 ARW to JPEG and HEIF through MediaStore and a
 * SAF folder, preview = export, the embedded metadata and ICC, the share cache, a batch with cancel, and a removed
 * destination. Numbers under `REVEAL_EXPORT`. `-e keep true` leaves the exported files in Pictures/Reveal and
 * `files/saf-test/export` for exiftool on the Mac.
 */
@RunWith(AndroidJUnit4::class)
class ExportFlowTest {
    private val context = TestPhotos.context
    private val keep = InstrumentationRegistry.getArguments().getString("keep") == "true"
    private val inserted = mutableListOf<Uri>()
    private val exported = mutableListOf<Uri>()
    private lateinit var renderer: TiledRenderer
    private val engine = ExportEngine(context)

    /** A visible edit so the comparison exercises the Light passes (guide pre-pass) too. */
    private val recipe = Recipe(light = Light(0.3f, contrast = 15f, highlights = -40f, shadows = 30f))

    @Before
    fun setUp() {
        renderer = TiledRenderer.create()
    }

    @After
    fun tearDown() {
        renderer.close()
        inserted.forEach(TestPhotos::delete)
        if (!keep) exported.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        ShareCache.clear(context)
    }

    private fun arw(): Uri {
        val file = TestPhotos.sample(ARW)
        assumeTrue("push $ARW into files/samples (CLAUDE.md)", file != null)
        return TestPhotos.insert(ARW, "image/x-sony-arw", checkNotNull(file)).also { inserted += it }
    }

    private fun export(
        uri: Uri,
        options: ExportOptions,
        name: String = ARW,
        recipe: Recipe = this.recipe,
    ): ExportResult {
        val result = engine.export(
            ExportItem(uri, name, recipe, rating = 3),
            options,
            Authorship("Ana Example", "© 2026 Ana Example"),
            renderer,
            cancel = { false },
        )
        if (options.destination == ExportDestination.Pictures) exported += result.uri
        return result
    }

    @Suppress("LongMethod")
    @Test
    fun arwToJpegInPicturesMatchesThePreview() = runBlocking {
        val uri = arw()
        val peak = PeakNative()
        val result = try {
            export(uri, ExportOptions(quality = 95, destination = ExportDestination.Pictures))
        } finally {
            peak.stop()
        }
        Log.i(
            TAG,
            "ARW full JPEG ${result.width}x${result.height}: decode ${result.decodeMs} ms, " +
                "render ${result.renderMs} ms " +
                "(${result.stats}), encode+write ${result.encodeMs} ms, ${result.bytes} bytes, " +
                "peak native ${peak.peakMb} MB (baseline ${peak.baselineMb} MB)",
        )
        assertThat(result.width).isEqualTo(6024)
        // Preview = export: the editor's proxy through the preview renderer vs the export downscaled to its size.
        val proxy = ImageInput.fromUri(context.contentResolver, uri, ARW).use { ProxyBuilder.decodeProxy(it, PROXY) }
        val input = PipelineInputMapper.map(proxy.image, proxy.info)
        val preview = GpuDevelopRenderer.create()
        val expected = try {
            preview.upload(input)
            preview.renderOutput(recipe, OutputSettings(OutputSpace.SRGB))
        } finally {
            preview.close()
        }
        val decoded = checkNotNull(
            context.contentResolver.openInputStream(result.uri).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            },
        )
        val small = Bitmap.createScaledBitmap(decoded, input.width, input.height, true)
        val actual = ByteArray(input.width * input.height * 4).also { small.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        val viaDecode = DiffStats.of(expected, actual)
        // The export pipeline itself downscaling to the proxy size (Lanczos in linear light), lossless-ish JPEG.
        val atProxy = export(uri, ExportOptions(quality = 100, size = ExportSize.LongEdge(input.width)))
        val proxyBitmap =
            checkNotNull(context.contentResolver.openInputStream(atProxy.uri).use { BitmapFactory.decodeStream(it) })
        val sameSize = Bitmap.createScaledBitmap(proxyBitmap, input.width, input.height, false)
        val direct = ByteArray(input.width * input.height * 4).also { sameSize.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        val stats = DiffStats.of(expected, direct)
        val blocks = DiffStats.of(
            blockMean(expected, input.width, input.height),
            blockMean(direct, input.width, input.height),
        )
        Log.i(
            TAG,
            "preview vs export (${input.width}x${input.height}, sRGB levels): export at proxy size $stats; " +
                "8x8 block means $blocks; full export decoded at 1/2 + bilinear $viaDecode",
        )
        assertThat(stats.mean).isAtMost(PREVIEW_MEAN_LEVELS)
        assertThat(stats.p99).isAtMost(PREVIEW_P99_LEVELS)
        assertThat(blocks.mean).isAtMost(PREVIEW_BLOCK_MEAN_LEVELS)
        assertThat(blocks.p99).isAtMost(PREVIEW_BLOCK_P99_LEVELS)
        // Metadata: EXIF from the ARW, orientation 1, rating, author; ICC profile embedded by the encoder.
        val bytes = checkNotNull(context.contentResolver.openInputStream(result.uri)).use { it.readBytes() }
        val snapshot = ImageInput.fromUri(context.contentResolver, result.uri, result.fileName).use { image ->
            image.withSource { ExifSnapshot.read(BlockCachedSource(it), image.type) }
        }
        assertThat(checkNotNull(snapshot).field(Tags.MAKE)?.text).isEqualTo("SONY")
        assertThat(String(bytes, 0, minOf(bytes.size, HEADER_BYTES), Charsets.ISO_8859_1)).contains("ICC_PROFILE")
        assertThat(String(bytes, 0, minOf(bytes.size, HEADER_BYTES), Charsets.ISO_8859_1)).contains("xmp:Rating=\"3\"")
    }

    /** Opt-in (`-e large <file in files/samples>`): a ~60 MP RAW at full size, peak memory and time. */
    @Test
    fun largeRawFullSize() {
        val name = InstrumentationRegistry.getArguments().getString("large")
        assumeTrue(name != null)
        val file = checkNotNull(TestPhotos.sample(checkNotNull(name)))
        val uri = TestPhotos.insert(name, "application/octet-stream", file).also { inserted += it }
        val peak = PeakNative()
        val result = try {
            export(uri, ExportOptions(destination = ExportDestination.Pictures), name)
        } finally {
            peak.stop()
        }
        Log.i(
            TAG,
            "large RAW ${result.width}x${result.height}: decode ${result.decodeMs} ms, render ${result.renderMs} ms " +
                "(${result.stats}), encode+write ${result.encodeMs} ms, ${result.bytes} bytes, " +
                "peak native ${peak.peakMb} MB (baseline ${peak.baselineMb} MB), " +
                "java heap max ${Runtime.getRuntime().maxMemory() / MB} MB",
        )
        assertThat(result.width.toLong() * result.height).isGreaterThan(50_000_000L)
    }

    @Test
    fun heifAndDisplayP3JpegToASafFolder() {
        val uri = arw()
        val folder = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/export").apply { mkdirs() }
        val destination = ExportDestination.Folder(SyntheticDocumentsProvider.treeUri("export").toString(), "export")
        val p3 =
            export(
                uri,
                ExportOptions(colour = ExportColour.DISPLAY_P3, size = ExportSize.WEB, destination = destination),
            )
        val decoded =
            checkNotNull(context.contentResolver.openInputStream(p3.uri).use { BitmapFactory.decodeStream(it) })
        assertThat(decoded.colorSpace).isEqualTo(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        assertThat(decoded.width).isEqualTo(2048)
        Log.i(TAG, "ARW web P3 JPEG ${p3.width}x${p3.height}: render ${p3.renderMs} ms, ${p3.bytes} bytes")
        val heif = export(uri, ExportOptions(format = ExportFormat.HEIF, quality = 90, destination = destination))
        val heifBitmap =
            checkNotNull(context.contentResolver.openInputStream(heif.uri).use { BitmapFactory.decodeStream(it) })
        assertThat(heifBitmap.width).isEqualTo(6024)
        Log.i(
            TAG,
            "ARW full HEIF ${heif.width}x${heif.height}: render ${heif.renderMs} ms, " +
                "encode ${heif.encodeMs} ms, ${heif.bytes} bytes",
        )
        assertThat(folder.listFiles().orEmpty().map { it.name }).containsAtLeast(p3.fileName, heif.fileName)
        if (!keep) folder.deleteRecursively()
    }

    @Test
    fun shareLeavesNoFileBehind() {
        val uri = TestPhotos.insert("card.jpg", "image/jpeg", TestPhotos.syntheticJpeg()).also { inserted += it }
        ShareCache.clear(context)
        val result = export(uri, ExportOptions(destination = ExportDestination.Share), "card.jpg", Recipe())
        assertThat(result.uri.authority).isEqualTo(ShareCache.authority(context))
        // The receiving app reads it through the FileProvider.
        assertThat(
            context.contentResolver.openInputStream(result.uri).use {
                it?.readBytes()?.size ?: 0
            },
        ).isGreaterThan(0)
        assertThat(ShareCache.count(context)).isEqualTo(1)
        // What the sheet does when the share sheet returns (and the app does at start).
        ShareCache.clear(context)
        assertThat(ShareCache.count(context)).isEqualTo(0)
        // Nothing else stays in the cache either (the encoded intermediate is deleted).
        assertThat(File(context.cacheDir, "export").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun aRemovedDestinationFailsWithAClearStatus() {
        val uri = TestPhotos.insert("card.jpg", "image/jpeg", TestPhotos.syntheticJpeg()).also { inserted += it }
        val gone = ExportDestination.Folder(SyntheticDocumentsProvider.treeUri("no-such-card").toString(), "SD")
        val failure = runCatching {
            export(uri, ExportOptions(destination = gone), "card.jpg", Recipe())
        }.exceptionOrNull()
        assertThat((failure as ExportException).failure).isEqualTo(ExportFailure.DESTINATION_UNAVAILABLE)
    }

    @Test
    fun batchOfFiveCanBeCancelled() {
        val jpeg = TestPhotos.syntheticJpeg(3000, 2000)
        val items = (1..5).map { i ->
            val uri = TestPhotos.insert("batch$i.jpg", "image/jpeg", jpeg).also { inserted += it }
            ExportJobItem(uri = uri.toString(), name = "batch$i.jpg", recipeJson = RecipeCodec.Default.encode(recipe))
        }
        val jobId = ExportWorker.enqueue(context, items, ExportOptions(destination = ExportDestination.Pictures), 1)
        val work = WorkManager.getInstance(context)
        val store = ExportJobStore(context)
        waitFor { store.states(jobId).count { it.status == ItemStatus.DONE } >= 2 }
        work.cancelAllWorkByTag(jobId).result.get()
        waitFor { work.getWorkInfosByTag(jobId).get().first().state.isFinished }
        val states = store.states(jobId)
        exported += states.mapNotNull { s -> s.resultUri?.let(Uri::parse) }
        assertThat(work.getWorkInfosByTag(jobId).get().first().state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(states.count { it.status == ItemStatus.DONE }).isIn(2..4)
        assertThat(states.count { it.status == ItemStatus.PENDING }).isAtLeast(1)
        // Nothing half-written stays behind.
        assertThat(states.all { it.partialUri == null }).isTrue()
        Log.i(TAG, "batch cancelled after ${states.count { it.status == ItemStatus.DONE }} of 5")
        store.delete(jobId)
    }

    /**
     * Kill/restart, step 1 (run on its own, then kill the process from adb, then run [killedBatchFinished]): queues a
     * batch of five and returns while it runs.
     */
    @Test
    fun startBatchForKill() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kill") == "start")
        val jpeg = TestPhotos.syntheticJpeg(4000, 3000)
        val items = (1..5).map { i ->
            val uri = TestPhotos.insert("kill$i.jpg", "image/jpeg", jpeg)
            ExportJobItem(uri = uri.toString(), name = "kill$i.jpg", recipeJson = RecipeCodec.Default.encode(recipe))
        }
        val jobId = ExportWorker.enqueue(context, items, ExportOptions(), 1)
        File(context.filesDir, "kill-job").writeText(jobId)
        waitFor { ExportJobStore(context).states(jobId).count { it.status == ItemStatus.DONE } >= 1 }
        Log.i(TAG, "kill test: job $jobId running, ${ExportJobStore(context).states(jobId).map { it.status }}")
    }

    /** Kill/restart, step 2: after the process was killed and the app started again, the job finished without dupes. */
    @Test
    fun killedBatchFinished() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kill") == "check")
        val jobId = File(context.filesDir, "kill-job").readText()
        val work = WorkManager.getInstance(context)
        waitFor(TIMEOUT_MS * 4) { work.getWorkInfosByTag(jobId).get().firstOrNull()?.state?.isFinished == true }
        val states = ExportJobStore(context).states(jobId)
        Log.i(TAG, "kill test: ${states.map { it.status to it.fileName }}")
        assertThat(states.map { it.status }).containsExactly(*Array(5) { ItemStatus.DONE })
        assertThat(states.mapNotNull { it.fileName }.toSet()).hasSize(5)
        states.mapNotNull {
            it.resultUri?.let(Uri::parse)
        }.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        ExportJobStore(context).job(jobId)?.items?.mapNotNull { it.uri?.let(Uri::parse) }?.forEach(TestPhotos::delete)
    }

    private fun waitFor(timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    /** Peak of the native heap while an export runs (sampled every 20 ms). */
    private class PeakNative {
        val baseline = Debug.getNativeHeapAllocatedSize()
        private val peak = AtomicLong(baseline)

        @Volatile
        private var running = true
        private val sampler = thread {
            while (running) {
                peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                Thread.sleep(SAMPLE_MS)
            }
        }

        fun stop() {
            running = false
            sampler.join()
        }

        val peakMb: Long get() = peak.get() / MB
        val baselineMb: Long get() = baseline / MB
    }

    /** Mean of each 8x8 block (RGBA, alpha 255): tone and colour without the per-pixel detail. */
    private fun blockMean(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val bw = width / BLOCK
        val bh = height / BLOCK
        val out = ByteArray(bw * bh * 4)
        for (block in 0 until bw * bh) {
            for (c in 0 until 4) out[block * 4 + c] = blockChannel(rgba, width, block % bw, block / bw, c)
        }
        return out
    }

    private fun blockChannel(rgba: ByteArray, width: Int, bx: Int, by: Int, c: Int): Byte {
        var sum = 0
        for (i in 0 until BLOCK * BLOCK) {
            val x = bx * BLOCK + i % BLOCK
            val y = by * BLOCK + i / BLOCK
            sum += rgba[(y * width + x) * 4 + c].toInt() and 0xff
        }
        return ((sum + BLOCK * BLOCK / 2) / (BLOCK * BLOCK)).toByte()
    }

    private data class DiffStats(val mean: Double, val p99: Int, val max: Int) {
        companion object {
            fun of(a: ByteArray, b: ByteArray): DiffStats {
                val histogram = IntArray(256)
                var sum = 0L
                var n = 0
                for (i in a.indices) {
                    if (i % 4 == 3) continue
                    val d = abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
                    histogram[d]++
                    sum += d
                    n++
                }
                var acc = 0
                var p99 = 0
                for (d in histogram.indices) {
                    acc += histogram[d]
                    if (acc >= n * 0.99) {
                        p99 = d
                        break
                    }
                }
                return DiffStats(sum.toDouble() / n, p99, histogram.indexOfLast { it > 0 })
            }
        }
    }

    private companion object {
        const val TAG = "REVEAL_EXPORT"
        const val ARW = "sony-zv-e10.arw"
        const val PROXY = 2400
        const val HEADER_BYTES = 64 * 1024
        const val TIMEOUT_MS = 120_000L
        const val POLL_MS = 100L
        const val SAMPLE_MS = 20L
        const val MB = 1024 * 1024

        /**
         * Preview = export tolerance (docs/adr/0009), 8-bit levels. Per pixel: the editor's proxy is a binned half-size
         * decode, the export a full AHD demosaic downscaled with Lanczos, so fine detail differs (measured mean 2.9,
         * p99 14). 8x8 block means (tone and colour): measured mean 0.55, p99 4.
         */
        const val PREVIEW_MEAN_LEVELS = 3.5
        const val PREVIEW_P99_LEVELS = 16
        const val PREVIEW_BLOCK_MEAN_LEVELS = 1.0
        const val PREVIEW_BLOCK_P99_LEVELS = 6
        const val BLOCK = 8
    }
}
