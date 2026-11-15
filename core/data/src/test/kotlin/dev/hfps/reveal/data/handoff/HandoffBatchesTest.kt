package dev.hfps.reveal.data.handoff

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.RevealDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The notices of a cascade batch are merged and shown once (ADR 0011 "Batches over 500"). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HandoffBatchesTest {
    private lateinit var db: RevealDatabase
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun batches(scope: TestScope) = HandoffBatches(db, scope) { now }

    @Test
    fun `the shown intent gets every member's not found counts summed, once`() = runTest {
        val batches = batches(this)
        val key = "app\u0000Trip"
        batches.join(key)
        batches.report(key, HandoffNotice(notFound = 2))
        batches.report(key, HandoffNotice(notFound = 3))
        batches.report(key, HandoffNotice()) // a member with nothing to say
        val shown = batches.takeNotices(key, HandoffNotice(notFound = 1))
        assertThat(shown).isEqualTo(HandoffNotice(notFound = 6))
        assertThat(batches.takeNotices(key, HandoffNotice())).isEqualTo(HandoffNotice())
    }

    @Test
    fun `issues of the members are summed into one, not paired first`() = runTest {
        val batches = batches(this)
        val key = "app\u0000Trip"
        batches.join(key)
        batches.report(key, HandoffNotice(issue = ServerIssue.Unreachable(4)))
        batches.report(key, HandoffNotice(issue = ServerIssue.NotPaired(5)))
        batches.report(key, HandoffNotice(1, ServerIssue.NotPaired(2)))
        assertThat(batches.takeNotices(key, HandoffNotice()))
            .isEqualTo(HandoffNotice(1, ServerIssue.NotPaired(7)))

        val other = "app\u0000Other"
        now += 60_000
        batches.join(other)
        batches.report(other, HandoffNotice(issue = ServerIssue.OtherServer("Home NAS", 3)))
        batches.report(other, HandoffNotice(issue = ServerIssue.OtherServer("Home NAS", 2)))
        assertThat(batches.takeNotices(other, HandoffNotice(issue = ServerIssue.Unreachable(1))).issue)
            .isEqualTo(ServerIssue.OtherServer("Home NAS", 5))
    }

    @Test
    fun `a member that reports after the notice was taken goes out on its own`() = runTest {
        val batches = batches(this)
        val key = "app\u0000Trip"
        batches.join(key)
        batches.takeNotices(key, HandoffNotice(notFound = 1))
        val late = ArrayList<HandoffNotice>()
        val job = backgroundScope.launchCollect(batches, late)
        runCurrent()
        batches.report(key, HandoffNotice(notFound = 2))
        runCurrent()
        assertThat(late).containsExactly(HandoffNotice(notFound = 2))
        job.cancel()
    }

    @Test
    fun `the library fallback carries the batch's notices`() = runTest {
        val batches = batches(this)
        val key = "app\u0000Trip"
        val got = ArrayList<HandoffNotice>()
        val job = backgroundScope.launchCollectLibrary(batches, got)
        runCurrent()
        batches.join(key)
        batches.report(key, HandoffNotice(notFound = 2))
        batches.report(key, HandoffNotice(notFound = 1))
        batches.silentFinished(key)
        now += HandoffBatches.SETTLE_MS
        advanceTimeBy(HandoffBatches.SETTLE_MS + 1)
        // Room answers on its own threads: wait in real time for the emission.
        repeat(200) {
            runCurrent()
            if (got.isEmpty()) Thread.sleep(25)
        }
        assertThat(got).containsExactly(HandoffNotice(notFound = 3))
        job.cancel()
    }

    @Test
    fun `a batch without a key is not merged`() = runTest {
        val own = HandoffNotice(notFound = 2)
        assertThat(batches(this).takeNotices(null, own)).isEqualTo(own)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(b: HandoffBatches, into: MutableList<HandoffNotice>) =
        launch { b.lateNotices.collect { into += it } }

    private fun kotlinx.coroutines.CoroutineScope.launchCollectLibrary(
        b: HandoffBatches,
        into: MutableList<HandoffNotice>,
    ) = launch { b.openLibrary.collect { into += it } }
}
