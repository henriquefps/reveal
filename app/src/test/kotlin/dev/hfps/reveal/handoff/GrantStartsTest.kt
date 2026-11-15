package dev.hfps.reveal.handoff

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The grant service's starts (ADR 0011 "Amendment W4", "Grant service"): stopped in order, never in the background. */
class GrantStartsTest {
    private val stoppedUpTo = mutableListOf<Int>()
    private var stoppedAll = 0
    private val starts = GrantStarts(stopUpTo = { stoppedUpTo += it }, stopAll = { stoppedAll++ })
    private val released = mutableListOf<Int>()

    private fun holding(id: Int) {
        starts.begin(id)
        starts.hold(id) { released += id }
    }

    @Test
    fun `starts stop in order, a later one that settles first waits for the earlier ones`() {
        holding(1)
        holding(2)
        holding(3)
        starts.settle(2)
        assertThat(released).containsExactly(2)
        assertThat(stoppedUpTo).isEmpty()
        starts.settle(1)
        assertThat(stoppedUpTo).containsExactly(2)
        starts.settle(3)
        assertThat(stoppedUpTo).containsExactly(2, 3).inOrder()
        assertThat(released).containsExactly(2, 1, 3).inOrder()
        assertThat(starts.pending).isEmpty()
    }

    @Test
    fun `a start refused the foreground settles at once and lets go of the grant it was handed`() {
        starts.refused(1) { released += 1 }
        assertThat(released).containsExactly(1)
        // Nothing else running: the service stops instead of staying a background service.
        assertThat(stoppedUpTo).containsExactly(1)
        assertThat(starts.pending).isEmpty()
    }

    @Test
    fun `a refused start behind a running one settles without stopping the running one`() {
        holding(1)
        starts.refused(2) { released += 2 }
        assertThat(released).containsExactly(2)
        assertThat(stoppedUpTo).isEmpty()
        starts.settle(1)
        assertThat(stoppedUpTo).containsExactly(2)
    }

    @Test
    fun `the time limit settles every start and stops the service`() {
        holding(1)
        holding(2)
        starts.begin(3) // Not holding yet.
        starts.timeout()
        assertThat(released).containsExactly(1, 2)
        assertThat(stoppedAll).isEqualTo(1)
        assertThat(starts.pending).isEmpty()
        // The copies' coroutines settling later change nothing; a hold arriving late is let go at once.
        starts.settle(1)
        starts.hold(3) { released += 3 }
        assertThat(released).containsExactly(1, 2, 3)
        assertThat(stoppedUpTo).isEmpty()
    }

    @Test
    fun `a grant is let go of once`() {
        holding(1)
        holding(2)
        starts.settle(2)
        starts.settle(2)
        assertThat(released).containsExactly(2)
    }
}
