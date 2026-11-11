package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES20

/**
 * Per-pass timing of on-screen frames for measurements: when [enabled], every [mark] ends with `glFinish`, so the
 * wall time since the previous mark is that section's GPU + CPU cost. Costs the overlap between passes, so it is
 * only switched on by measurement tests. GL thread only; [snapshot] is read from any thread.
 */
internal class PassProfiler {
    @Volatile
    var enabled: Boolean = false

    private var last = 0L
    private val totals = LinkedHashMap<String, Double>()
    private val counts = LinkedHashMap<String, Int>()

    @Volatile
    private var published: Map<String, Float> = emptyMap()

    fun begin() {
        if (!enabled) return
        GLES20.glFinish()
        last = System.nanoTime()
    }

    fun mark(section: String) {
        if (!enabled) return
        GLES20.glFinish()
        val now = System.nanoTime()
        totals[section] = (totals[section] ?: 0.0) + (now - last) / NANOS_PER_MS
        counts[section] = (counts[section] ?: 0) + 1
        last = now
    }

    /** Mean milliseconds per occurrence of each section since the last [reset]. */
    fun snapshot(): Map<String, Float> {
        published = totals.mapValues { (k, v) -> (v / counts.getValue(k)).toFloat() }
        return published
    }

    fun reset() {
        totals.clear()
        counts.clear()
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000.0
    }
}
