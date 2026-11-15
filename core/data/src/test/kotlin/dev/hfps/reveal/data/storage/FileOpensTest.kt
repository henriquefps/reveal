package dev.hfps.reveal.data.storage

import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertThrows
import org.junit.Test

/** The app-wide open throttle and the busy-provider retry (ADR 0018 "W3/C", Hoard ADR 0010). */
class FileOpensTest {
    private fun busy() = FileNotFoundException("busy: 8 hand-off reads in progress, retry")

    @Test
    fun `only the exact busy prefix is a busy provider, and it is never an access error`() {
        assertThat(isProviderBusy(busy())).isTrue()
        assertThat(isAccessError(busy())).isFalse()
        assertThat(isProviderBusy(java.io.IOException("wrapped", busy()))).isTrue()
        // Any other FileNotFoundException keeps its meaning: gone or refused.
        val gone = FileNotFoundException("No such document")
        assertThat(isProviderBusy(gone)).isFalse()
        assertThat(isAccessError(gone)).isTrue()
        assertThat(isProviderBusy(FileNotFoundException("Busy: capitalised"))).isFalse()
        assertThat(isProviderBusy(FileNotFoundException(" busy: leading space"))).isFalse()
        assertThat(isAccessError(ProviderBusyException(busy()))).isFalse()
    }

    @Test
    fun `a busy provider is retried with the backoff outside the slot and then read`() {
        val waits = ArrayList<Long>()
        val heldWhileWaiting = ArrayList<Int>()
        var left = 3
        val value = FileOpens.gated(sleep = { waits += it; heldWhileWaiting += FileOpens.open }) { release ->
            try {
                if (left-- > 0) throw busy()
                "read"
            } finally {
                release()
            }
        }
        assertThat(value).isEqualTo("read")
        assertThat(waits).containsExactly(250L, 500L, 1_000L).inOrder()
        // The pause is not an open: no slot is held while waiting.
        assertThat(heldWhileWaiting).containsExactly(0, 0, 0)
    }

    @Test
    fun `a provider busy through every retry gives up after about 30 s as a transient failure`() {
        val waits = ArrayList<Long>()
        val error = assertThrows(ProviderBusyException::class.java) {
            FileOpens.gated(sleep = { waits += it }) { throw busy() }
        }
        assertThat(waits.first()).isEqualTo(250L)
        assertThat(waits.max()).isEqualTo(4_000L)
        assertThat(waits.sum()).isIn(com.google.common.collect.Range.closed(25_000L, 32_000L))
        assertThat(isAccessError(error)).isFalse()
        assertThat(FileOpens.open).isEqualTo(0)
    }

    @Test
    fun `other errors are not retried`() {
        var calls = 0
        assertThrows(SecurityException::class.java) {
            FileOpens.gated(sleep = { error("no wait") }) {
                calls++
                throw SecurityException("Permission Denial")
            }
        }
        assertThat(calls).isEqualTo(1)
        assertThat(FileOpens.open).isEqualTo(0)
    }

    @Test
    fun `a cancelled waiter frees nothing it never held and the slots are all usable afterwards`() {
        val holders = List(FileOpens.BACKGROUND_SLOTS) {
            var r: () -> Unit = {}
            FileOpens.gated { release -> r = release }
            r
        }
        assertThat(FileOpens.open).isEqualTo(FileOpens.BACKGROUND_SLOTS)
        // The fifth caller waits (no bypass after any time); interrupting it (a cancelled coroutine) ends the wait.
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val waiter = Thread { runCatching { FileOpens.gated { it } }.onFailure(failure::set) }
        waiter.start()
        Thread.sleep(200)
        assertThat(waiter.isAlive).isTrue()
        assertThat(FileOpens.open).isEqualTo(FileOpens.BACKGROUND_SLOTS)
        waiter.interrupt()
        waiter.join(5_000)
        assertThat(failure.get()).isInstanceOf(InterruptedException::class.java)
        holders.forEach { it() }
        assertThat(FileOpens.open).isEqualTo(0)
        // A backing-off caller holds no slot either: four others open while it pauses.
        val pausing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val busyCaller = Thread {
            runCatching {
                FileOpens.gated(sleep = { pausing.countDown(); proceed.await() }) { release ->
                    release()
                    throw busy()
                }
            }
        }
        busyCaller.start()
        assertThat(pausing.await(5, TimeUnit.SECONDS)).isTrue()
        val again = List(FileOpens.BACKGROUND_SLOTS) {
            var r: () -> Unit = {}
            FileOpens.gated { release -> r = release }
            r
        }
        assertThat(FileOpens.open).isEqualTo(FileOpens.BACKGROUND_SLOTS)
        again.forEach { it() }
        busyCaller.interrupt()
        proceed.countDown()
        busyCaller.join(5_000)
    }

    @Test
    fun `background, editor and export opens share one cap of four`() {
        val pool = Executors.newFixedThreadPool(12)
        val open = AtomicInteger()
        val peak = AtomicInteger()
        val done = CountDownLatch(36)
        repeat(36) {
            pool.execute {
                // Thumbnails and checks (gated directly), the editor and the export (openDocument, the same gate) alike.
                FileOpens.gated { release ->
                    peak.accumulateAndGet(open.incrementAndGet(), ::maxOf)
                    Thread.sleep(10)
                    open.decrementAndGet()
                    release()
                }
                done.countDown()
            }
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(peak.get()).isAtMost(FileOpens.BACKGROUND_SLOTS)
    }

    @Test
    fun `at most four inputs are open at once through the gate, and a failed open frees its slot`() {
        assertThrows(IllegalStateException::class.java) { FileOpens.gated<Unit> { error("open failed") } }
        val pool = Executors.newFixedThreadPool(12)
        val open = AtomicInteger()
        val peak = AtomicInteger()
        val done = CountDownLatch(24)
        repeat(24) {
            pool.execute {
                FileOpens.gated { release ->
                    peak.accumulateAndGet(open.incrementAndGet(), ::maxOf)
                    Thread.sleep(20)
                    open.decrementAndGet()
                    release()
                }
                done.countDown()
            }
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(peak.get()).isAtMost(FileOpens.BACKGROUND_SLOTS)
        assertThat(FileOpens.open).isEqualTo(0)
    }
}
