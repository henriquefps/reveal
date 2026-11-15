package dev.hfps.reveal.handoff

import java.util.TreeMap

/**
 * The grant service's starts (ADR 0011 "Amendment W4", "Grant service"), kept apart from the service so the rules are
 * unit-tested: each start may hold its ingest's grant ([hold]); a start that settles lets go of it, and the service
 * stops every start up to the last one whose earlier starts all settled ([stopUpTo] = `stopSelfResult`, which drops
 * those starts with their grants). [timeout] (the system's time limit for a dataSync service) and a refused
 * foreground start ([refused]) never leave the service running in the background.
 */
internal class GrantStarts(private val stopUpTo: (Int) -> Unit, private val stopAll: () -> Unit) {
    private class Start(var release: (() -> Unit)? = null, var settled: Boolean = false)

    private val starts = TreeMap<Int, Start>()

    /** The starts not stopped yet. */
    val pending: Set<Int> @Synchronized get() = starts.keys.toSet()

    @Synchronized
    fun begin(startId: Int) {
        starts.getOrPut(startId) { Start() }
    }

    /** [startId] holds its ingest's grant until it settles; [release] lets go of it. */
    @Synchronized
    fun hold(startId: Int, release: () -> Unit) {
        val start = starts[startId]
        if (start == null || start.settled) {
            release()
        } else {
            start.release = release
        }
    }

    /** [startId] settled: lets go of its grant and stops every start up to the last one whose earlier ones settled. */
    @Synchronized
    fun settle(startId: Int) {
        val start = starts[startId] ?: return
        start.release?.invoke()
        start.release = null
        start.settled = true
        var last: Int? = null
        for ((id, s) in starts) {
            if (!s.settled) break
            last = id
        }
        val upTo = last ?: return
        starts.headMap(upTo, true).clear()
        stopUpTo(upTo)
    }

    /**
     * [startId] could not go to the foreground: it settles at once ([release] lets go of the grant its ingest was
     * handed), so the service is never a background service waiting for copies; the copies still pending lose the
     * grant and are reported as not copied.
     */
    @Synchronized
    fun refused(startId: Int, release: (() -> Unit)?) {
        begin(startId)
        release?.let { hold(startId, it) }
        settle(startId)
    }

    /** The system's time limit ended the foreground service: every start settles now and the service stops. */
    @Synchronized
    fun timeout() {
        starts.values.forEach { it.release?.invoke() }
        starts.clear()
        stopAll()
    }
}
