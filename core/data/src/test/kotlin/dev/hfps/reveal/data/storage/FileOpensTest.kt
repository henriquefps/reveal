package dev.hfps.reveal.data.storage

import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test

/** The app-wide open throttle and the busy-provider retry (ADR 0018 "W3/C", Hoard ADR 0010). */
class FileOpensTest {
    @org.junit.Before
    fun fresh() = FileOpens.resetForTests()

    @org.junit.After
    fun noLeaks() {
        val leaked = FileOpens.open
        FileOpens.resetForTests()
        assertThat(leaked).isEqualTo(0)
    }

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
        val value = FileOpens.gated(sleep = {
            waits += it
            heldWhileWaiting += FileOpens.open
        }) { release ->
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
                FileOpens.gated(sleep = {
                    pausing.countDown()
                    proceed.await()
                }) { release ->
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
                // Thumbnails, checks, the editor and the export all take the same gate.
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

    /** Takes every slot; returns their releases. */
    private fun fill(): List<() -> Unit> = List(FileOpens.BACKGROUND_SLOTS) {
        var r: () -> Unit = {}
        FileOpens.gated(tag = "holder$it") { release -> r = release }
        r
    }

    /** An input-like holder of one slot, released when closed (as `ImageInput(..., onClose = release)`). */
    private class Held(private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertThat(condition()).isTrue()
    }

    @Test
    fun `a cancelled coroutine leaves the queue at once, takes and frees nothing`() = runBlocking<Unit> {
        val holders = fill()
        val waiter = launch(FileOpens.io("test")) { FileOpens.gated { release -> release() } }
        waitUntil { FileOpens.waiting == 1 }
        val started = System.nanoTime()
        withTimeout(2_000) { waiter.cancelAndJoin() }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_000L)
        assertThat(FileOpens.waiting).isEqualTo(0)
        assertThat(FileOpens.open).isEqualTo(FileOpens.BACKGROUND_SLOTS)
        holders.forEach { it() }
        assertThat(FileOpens.open).isEqualTo(0)
        // All four slots usable again.
        fill().forEach { it() }
    }

    @Test
    fun `a waiter keeps its place in the queue while it polls for cancellation`() = runBlocking<Unit> {
        val holders = fill()
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val first = async(Dispatchers.IO + FileOpens.waits("first")) {
            FileOpens.gated { release ->
                order += "first"
                release()
            }
        }
        waitUntil { FileOpens.waiting == 1 }
        // Longer than a poll: the first waiter has woken up several times meanwhile.
        delay(FileOpens.POLL_MS * 4)
        val second = async(FileOpens.io("second")) {
            FileOpens.gated { release ->
                order += "second"
                release()
            }
        }
        waitUntil { FileOpens.waiting == 2 }
        delay(FileOpens.POLL_MS * 4)
        holders.first()()
        withTimeout(5_000) {
            first.await()
            second.await()
        }
        holders.drop(1).forEach { it() }
        assertThat(order).containsExactly("first", "second").inOrder()
    }

    @Test
    fun `release is idempotent and never frees another caller's slot`() {
        var release: () -> Unit = {}
        FileOpens.gated { release = it }
        val others = List(FileOpens.BACKGROUND_SLOTS - 1) {
            var r: () -> Unit = {}
            FileOpens.gated { r = it }
            r
        }
        assertThat(FileOpens.open).isEqualTo(FileOpens.BACKGROUND_SLOTS)
        release()
        release()
        release()
        assertThat(FileOpens.open).isEqualTo(FileOpens.BACKGROUND_SLOTS - 1)
        others.forEach { it() }
        assertThat(FileOpens.open).isEqualTo(0)
    }

    @Test
    fun `slots come back after failed decodes, an OutOfMemoryError and a cancelled decode`() = runBlocking<Unit> {
        repeat(FileOpens.BACKGROUND_SLOTS * 2) {
            assertThrows(java.io.IOException::class.java) {
                FileOpens.gated { Held(it) }.use { throw java.io.IOException("corrupt") }
            }
        }
        assertThat(FileOpens.open).isEqualTo(0)
        repeat(FileOpens.BACKGROUND_SLOTS * 2) {
            assertThrows(OutOfMemoryError::class.java) {
                FileOpens.gated { Held(it) }.use { throw OutOfMemoryError("decode") }
            }
            // Also when the open itself runs out of memory: an Error frees the slot as well.
            assertThrows(OutOfMemoryError::class.java) { FileOpens.gated<Unit> { throw OutOfMemoryError("open") } }
        }
        assertThat(FileOpens.open).isEqualTo(0)
        val decoding = CompletableDeferredLatch()
        val job = launch(FileOpens.io("test")) {
            FileOpens.gated { Held(it) }.use {
                decoding.open()
                delay(60_000)
            }
        }
        decoding.await()
        assertThat(FileOpens.open).isEqualTo(1)
        job.cancelAndJoin()
        assertThat(FileOpens.open).isEqualTo(0)
        fill().forEach { it() }
    }

    private class CompletableDeferredLatch {
        private val latch = CountDownLatch(1)

        fun open() = latch.countDown()

        fun await() = assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a busy provider's pause ends when the caller is cancelled`() = runBlocking<Unit> {
        val job = launch(FileOpens.io("test")) {
            FileOpens.gated(backoff = longArrayOf(60_000)) { release ->
                release()
                throw busy()
            }
        }
        delay(FileOpens.POLL_MS * 2)
        val started = System.nanoTime()
        withTimeout(2_000) { job.cancelAndJoin() }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_000L)
        assertThat(FileOpens.open).isEqualTo(0)
    }

    @Test
    fun `a long wait logs who holds the slots and keeps waiting`() {
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        FileOpens.report = { lines += it }
        FileOpens.stuckMs = 100
        val holders = List(FileOpens.BACKGROUND_SLOTS) {
            var r: () -> Unit = {}
            FileOpens.gated(name = "DSC000$it.ARW", tag = "thumbnails") { r = it }
            r
        }
        assertThat(FileOpens.holders()).hasSize(FileOpens.BACKGROUND_SLOTS)
        val got = CountDownLatch(1)
        val waiter = Thread {
            FileOpens.gated(name = "DSC0009.JPG", tag = "viewer") {
                got.countDown()
                it()
            }
        }
        waiter.start()
        waitUntil { lines.size >= 2 }
        // Still waiting: a pinned slot is reported, never bypassed.
        assertThat(got.count).isEqualTo(1)
        assertThat(lines.first()).contains("viewer (DSC0009.JPG)")
        assertThat(lines.first()).contains("thumbnails (DSC0000.ARW")
        holders.forEach { it() }
        assertThat(got.await(5, TimeUnit.SECONDS)).isTrue()
        waiter.join(5_000)
        waitUntil { lines.any { it.contains("ended after") } }
    }

    @Test
    fun `an input opened in withContext is closed when the switch back is cancelled`() = runBlocking<Unit> {
        // Plain withContext: the block returns the input, the cancelled coroutine throws at its exit and drops it.
        var dropped: Held? = null
        val naive = launch {
            val held = withContext(FileOpens.io("test")) {
                FileOpens.gated { Held(it) }.also {
                    dropped = it
                    this@launch.cancel()
                }
            }
            held.close()
        }
        naive.join()
        assertThat(FileOpens.open).isEqualTo(1)
        dropped?.close()
        assertThat(FileOpens.open).isEqualTo(0)
        // openedIn closes it instead: no slot lost.
        val job = launch {
            val held = openedIn(FileOpens.io("test")) {
                FileOpens.gated { Held(it) }.also { this@launch.cancel() }
            }
            held.close()
        }
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(FileOpens.open).isEqualTo(0)
        // Not cancelled: the input is the caller's, open.
        val kept = openedIn(FileOpens.io("test")) { FileOpens.gated { Held(it) } }
        assertThat(FileOpens.open).isEqualTo(1)
        kept.close()
        fill().forEach { it() }
    }

    @Test
    fun `a report that throws never costs a slot`() {
        FileOpens.report = { error("broken log") }
        FileOpens.stuckMs = 20
        val holders = fill()
        val got = CountDownLatch(1)
        var release: () -> Unit = {}
        val waiter = Thread {
            FileOpens.gated { r ->
                release = r
                got.countDown()
            }
        }
        waiter.start()
        Thread.sleep(150)
        holders.first()()
        assertThat(got.await(5, TimeUnit.SECONDS)).isTrue()
        waiter.join(5_000)
        assertThat(FileOpens.open).isEqualTo(FileOpens.BACKGROUND_SLOTS)
        release()
        holders.drop(1).forEach { it() }
        assertThat(FileOpens.open).isEqualTo(0)
    }

    @Test
    fun `readCatching keeps read failures but throws a cancelled wait on`() = runBlocking<Unit> {
        assertThat(readCatching { throw java.io.IOException("gone") }.isFailure).isTrue()
        assertThat(readCatching { throw OutOfMemoryError("big") }.isFailure).isTrue()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            readCatching { throw kotlinx.coroutines.CancellationException("stop") }
        }
        assertThrows(InterruptedException::class.java) { readCatching { throw InterruptedException() } }
        // A cancelled batch stops at its next gated read instead of reading on.
        val holders = fill()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val batch = launch(FileOpens.io("batch")) {
            repeat(5) {
                readCatching { FileOpens.gated { release -> release() } }
                reads.incrementAndGet()
            }
        }
        waitUntil { FileOpens.waiting == 1 }
        batch.cancelAndJoin()
        assertThat(reads.get()).isEqualTo(0)
        holders.forEach { it() }
    }
}
