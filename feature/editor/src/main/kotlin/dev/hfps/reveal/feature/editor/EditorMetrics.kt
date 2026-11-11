package dev.hfps.reveal.feature.editor

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import dev.hfps.reveal.pipeline.gl.FrameStats
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.Collections
import java.util.Locale

/**
 * Timings and memory of the last photo opened in the editor, for logs (`adb logcat -s RevealEditor`), instrumented
 * tests and the measurements in ADR 0002/0004. All times are milliseconds since the open started; `-1` = not yet.
 * Cheap enough to stay on in release builds (a few clock reads and counters per photo).
 */
object EditorMetrics {
    private const val TAG = "RevealEditor"
    private const val BYTES_PER_MB = 1024f * 1024f
    private const val MAX_FRAMES = 2_000
    private const val BENCH = "REVEAL_BENCH"

    /** When true, the editor's renderer ends every frame with `glFinish` so [FrameStats.gpuWallMs] is meaningful. */
    @Volatile
    var measureGpuTime: Boolean = false

    @Volatile private var startedAt = 0L

    /** Embedded preview / subsampled standard image on screen. */
    @Volatile var previewMs: Long = -1
        private set

    /** Proxy decoded and mapped to pipeline input. */
    @Volatile var proxyMs: Long = -1
        private set

    /** GPU renderer created (it starts in parallel with the decode). */
    @Volatile var gpuReadyMs: Long = -1
        private set

    /** Proxy uploaded to the GPU. */
    @Volatile var uploadedMs: Long = -1
        private set

    /** First developed frame presented (the editable GPU proxy). */
    @Volatile var developedMs: Long = -1
        private set

    /** Full-resolution source decoded and handed to the renderer (the zoom's tiles can start). */
    @Volatile var fullResolutionMs: Long = -1
        private set

    /** Peak Java heap in use (MB) sampled at each step of the open. */
    @Volatile var peakHeapMb: Float = 0f
        private set

    /** Peak native heap allocated (MB) sampled at each step of the open. */
    @Volatile var peakNativeMb: Float = 0f
        private set

    /**
     * The renderer of the photo on screen (null when none), so instrumented tests can read back what the real flow
     * renders (`renderOutput`). Never used by the app itself.
     */
    @Volatile var renderer: GpuDevelopRenderer? = null

    /** For tests only: sets and commits a recipe control of the open photo, as a slider release would. */
    @Volatile var setControl: ((ControlId, Float) -> Unit)? = null
        internal set

    /** For tests only: the working and committed recipes of the open photo. */
    @Volatile var recipes: (() -> Pair<Recipe, Recipe>)? = null
        internal set

    /** Last error, if the open failed. */
    @Volatile var error: String? = null
        private set

    /** Stats of every presented frame since the photo opened (for frame times while dragging). */
    val frames: MutableList<FrameStats> = Collections.synchronizedList(ArrayList())

    /** `SystemClock.elapsedRealtimeNanos()` when each of [frames] was presented. */
    val frameTimesNanos: MutableList<Long> = Collections.synchronizedList(ArrayList())

    /** True once a developed frame of the current photo is on screen. */
    val isDeveloped: Boolean get() = developedMs >= 0

    internal fun start() {
        startedAt = SystemClock.elapsedRealtime()
        previewMs = -1
        proxyMs = -1
        gpuReadyMs = -1
        uploadedMs = -1
        developedMs = -1
        fullResolutionMs = -1
        peakHeapMb = 0f
        peakNativeMb = 0f
        error = null
        frames.clear()
        frameTimesNanos.clear()
        sampleMemory()
    }

    internal fun preview() {
        previewMs = since()
        sampleMemory()
    }

    internal fun proxy() {
        proxyMs = since()
        sampleMemory()
    }

    internal fun gpuReady() {
        gpuReadyMs = since()
    }

    internal fun uploaded() {
        uploadedMs = since()
        sampleMemory()
    }

    internal fun fullResolution(decodeMs: Long, width: Int, height: Int) {
        fullResolutionMs = since()
        sampleMemory()
        Log.i(BENCH, "full-resolution source ${width}x$height: decode $decodeMs ms, ready at $fullResolutionMs ms")
    }

    internal fun frame(stats: FrameStats) {
        if (developedMs < 0) {
            developedMs = since()
            sampleMemory()
            Log.i(TAG, summary())
        }
        if (frames.size < MAX_FRAMES) {
            frameTimesNanos.add(SystemClock.elapsedRealtimeNanos())
            frames.add(stats)
        }
    }

    internal fun failed(reason: String) {
        error = reason
        Log.w(TAG, "open failed: $reason")
    }

    /** One Auto run (analysis + rules), logged for ADR 0010's cost figure. */
    fun auto(scope: String, millis: Long) {
        Log.i(TAG, "auto $scope: $millis ms")
    }

    /** Samples the Java heap in use and the native heap. Also called by tests while they drive the UI. */
    fun sampleMemory() {
        val runtime = Runtime.getRuntime()
        val heap = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
        val native = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB
        if (heap > peakHeapMb) peakHeapMb = heap
        if (native > peakNativeMb) peakNativeMb = native
    }

    /** One line for logs and test reports. */
    fun summary(): String = "preview=${previewMs}ms proxy=${proxyMs}ms gpu=${gpuReadyMs}ms uploaded=${uploadedMs}ms " +
        String.format(
            Locale.ROOT,
            "developed=%dms peakHeap=%.0fMB peakNative=%.0fMB",
            developedMs,
            peakHeapMb,
            peakNativeMb,
        )

    private fun since() = SystemClock.elapsedRealtime() - startedAt
}
