package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES20
import android.opengl.GLES30

/**
 * Per-pass timing of frames for measurements, switched on only by measurement tests ([enabled]). Each [mark] closes
 * the section that began at the previous [begin] or [mark].
 *
 * - With `GL_EXT_disjoint_timer_query` ([gpuTimers], set by the renderer from the driver's extensions) every section
 *   is a `GL_TIME_ELAPSED_EXT` query: the GPU's own time for that section's commands, with no `glFinish` between
 *   passes, so the passes overlap as they do on screen. Results are collected when available (never stalling a
 *   frame) and in [snapshot]; a disjoint event (frequency change, context loss) drops the pending results.
 * - Without it every [mark] ends with `glFinish` and the wall time since the previous mark is that section's GPU + CPU
 *   cost (each figure includes a sync).
 *
 * The CPU time spent issuing each section is recorded in both modes ([cpuSnapshot]). GL thread only, except that
 * [enabled] may be set from any thread.
 */
internal class PassProfiler {
    @Volatile
    var enabled: Boolean = false

    /** True when sections are timed by GPU timer queries (see the class comment). */
    var gpuTimers: Boolean = false

    private var last = 0L
    private var open = 0
    private val free = ArrayDeque<Int>()
    private val pending = ArrayDeque<Pair<String?, Int>>()
    private val totals = LinkedHashMap<String, Double>()
    private val counts = LinkedHashMap<String, Int>()
    private val cpuTotals = LinkedHashMap<String, Double>()
    private val cpuCounts = LinkedHashMap<String, Int>()
    private val ints = IntArray(1)

    /** Starts a frame's first section (closing, unlabelled, a section left open by the previous frame). */
    fun begin() {
        if (!enabled) return
        if (gpuTimers) {
            closeOpen(null)
            collect(wait = false)
            open = startQuery()
        } else {
            GLES20.glFinish()
        }
        last = System.nanoTime()
    }

    /** Ends the current section as [section] and starts the next one. */
    fun mark(section: String) {
        if (!enabled) return
        if (gpuTimers) {
            val now = System.nanoTime()
            add(cpuTotals, cpuCounts, section, (now - last) / NANOS_PER_MS)
            closeOpen(section)
            open = startQuery()
            last = System.nanoTime()
        } else {
            GLES20.glFinish()
            val now = System.nanoTime()
            add(totals, counts, section, (now - last) / NANOS_PER_MS)
            add(cpuTotals, cpuCounts, section, (now - last) / NANOS_PER_MS)
            last = now
        }
    }

    /**
     * Mean milliseconds per occurrence of each section since the last [reset]: GPU time with [gpuTimers], else the
     * wall time between synced marks. Waits for the outstanding queries.
     */
    fun snapshot(): Map<String, Float> {
        if (gpuTimers) {
            closeOpen(null)
            GLES20.glFinish()
            collect(wait = true)
        }
        return means(totals, counts)
    }

    /** Mean CPU milliseconds spent issuing each section (without the sync in the `glFinish` mode). */
    fun cpuSnapshot(): Map<String, Float> = means(cpuTotals, cpuCounts)

    fun reset() {
        totals.clear()
        counts.clear()
        cpuTotals.clear()
        cpuCounts.clear()
    }

    /** Deletes the query objects (GL thread, before the context goes). */
    fun release() {
        closeOpen(null)
        val ids = (free + pending.map { it.second }).toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteQueries(ids.size, ids, 0)
        free.clear()
        pending.clear()
    }

    private fun startQuery(): Int {
        val id = free.removeFirstOrNull() ?: run {
            GLES30.glGenQueries(1, ints, 0)
            ints[0]
        }
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, id)
        return id
    }

    private fun closeOpen(section: String?) {
        if (open == 0) return
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        pending.addLast(section to open)
        open = 0
    }

    private fun collect(wait: Boolean) {
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, ints, 0)
        val disjoint = ints[0] != 0
        while (pending.isNotEmpty()) {
            val (section, id) = pending.first()
            if (!disjoint && !wait) {
                GLES30.glGetQueryObjectuiv(id, GLES30.GL_QUERY_RESULT_AVAILABLE, ints, 0)
                if (ints[0] == 0) return
            }
            pending.removeFirst()
            if (!disjoint && section != null) {
                GLES30.glGetQueryObjectuiv(id, GLES30.GL_QUERY_RESULT, ints, 0)
                add(totals, counts, section, (ints[0].toUInt().toDouble()) / NANOS_PER_MS)
            }
            free.addLast(id)
        }
    }

    private fun add(t: MutableMap<String, Double>, c: MutableMap<String, Int>, section: String, ms: Double) {
        t[section] = (t[section] ?: 0.0) + ms
        c[section] = (c[section] ?: 0) + 1
    }

    private fun means(t: Map<String, Double>, c: Map<String, Int>): Map<String, Float> =
        t.mapValues { (k, v) -> (v / c.getValue(k)).toFloat() }

    companion object {
        private const val NANOS_PER_MS = 1_000_000.0
        private const val GL_TIME_ELAPSED_EXT = 0x88BF
        private const val GL_GPU_DISJOINT_EXT = 0x8FBB
        const val TIMER_EXTENSION = "GL_EXT_disjoint_timer_query"
    }
}
