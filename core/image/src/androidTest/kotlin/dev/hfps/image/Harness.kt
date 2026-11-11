package dev.hfps.image

import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assume.assumeTrue

/** Shared helpers of the instrumented harness: where the samples are, logging, peak native memory. */
object Harness {
    const val TAG = "RevealHarness"

    /**
     * Folder with the sample files: `-e dir <path>`, default the test app's private `files/samples`
     * (`scripts/harness-push.sh` copies them there with `run-as`).
     */
    fun samplesDir(): File {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = args.getString("dir")?.let(::File) ?: File(context.filesDir, "samples")
        if (!anonymize) log("samples dir $dir exists=${dir.isDirectory} files=${dir.list()?.size}")
        assumeTrue(
            "No samples in $dir (run scripts/fetch-samples.sh and push them, see CLAUDE.md)",
            dir.isDirectory && !dir.list().isNullOrEmpty(),
        )
        return dir
    }

    /** Samples whose name ends with one of [extensions] (lowercase, no dot). */
    fun samples(vararg extensions: String): List<File> = samplesDir().listFiles().orEmpty().filter { f ->
        extensions.any { f.name.lowercase().endsWith(".$it") }
    }.sortedBy { it.name }

    /** `-e anonymize true`: log aggregates only, never file names (opt-in runs on private photos). */
    val anonymize: Boolean get() = InstrumentationRegistry.getArguments().getString("anonymize") == "true"

    /** A file's name for logs, or `file#N` when [anonymize] is on. */
    fun label(file: File, index: Int): String = if (anonymize) "file#$index" else file.name

    fun log(message: String) {
        Log.i(TAG, message)
    }

    /** Runs [block] while sampling the native heap every 5 ms; returns the result and the peak growth in MB. */
    fun <T> measurePeakNativeMb(block: () -> T): Pair<T, Long> {
        System.gc()
        val base = Debug.getNativeHeapAllocatedSize()
        val peak = AtomicLong(base)

        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                Thread.sleep(SAMPLE_MS)
            }
        }
        sampler.start()
        try {
            val result = block()
            peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
            return result to (peak.get() - base) / MB
        } finally {
            running.set(false)
            sampler.join()
        }
    }

    fun mean(values: List<Long>): Long = if (values.isEmpty()) 0 else values.sum() / values.size

    private const val SAMPLE_MS = 5L
    private const val MB = 1024L * 1024L
}
