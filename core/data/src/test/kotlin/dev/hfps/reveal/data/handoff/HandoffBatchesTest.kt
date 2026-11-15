package dev.hfps.reveal.data.handoff

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.RevealDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

    private fun batches() = HandoffBatches(db) { now }

    @Test
    fun `the shown intent gets every member's not found counts summed, once`() = runTest {
        val batches = batches()
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
        val batches = batches()
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
        val batches = batches()
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
    fun `members join one stamp and the later ones are followers`() = runTest {
        val batches = batches()
        val key = "app\u0000Trip"
        val first = batches.join(key)
        now += 2_000
        val second = batches.join(key)
        assertThat(first.follower).isFalse()
        assertThat(second.follower).isTrue()
        assertThat(second.at).isEqualTo(first.at)
        // After the window a hand-off with the same title is a new batch.
        now += HandoffBatches.WINDOW_MS + 1
        assertThat(batches.join(key).at).isNotEqualTo(first.at)
    }

    @Test
    fun `a batch is complete once BATCH_COUNT intents joined, absent counts never say so`() = runTest {
        val batches = batches()
        val key = "app\u0000Trip"
        assertThat(batches.join(key, 3).complete).isFalse()
        assertThat(batches.join(key, 3).complete).isFalse()
        assertThat(batches.join(key, 3).complete).isTrue()
        now += HandoffBatches.WINDOW_MS + 1
        val other = "app\u0000Other"
        assertThat(batches.join(other, null).complete).isFalse()
        assertThat(batches.join(other, null).complete).isFalse()
    }

    @Test
    fun `an incomplete batch is never complete, its late member reports on its own`() = runTest {
        val batches = batches()
        val key = "app\u0000Trip"
        batches.join(key, 3)
        assertThat(batches.join(key, 3).complete).isFalse() // the third never came
        batches.takeNotices(key, HandoffNotice(notFound = 1))
        val late = ArrayList<HandoffNotice>()
        val job = backgroundScope.launchCollect(batches, late)
        runCurrent()
        batches.report(key, HandoffNotice(notFound = 4))
        runCurrent()
        assertThat(late).containsExactly(HandoffNotice(notFound = 4))
        job.cancel()
    }

    @Test
    fun `a marked intent batches even without a title`() {
        assertThat(HandoffBatches.key("app", null)).isNull()
        assertThat(HandoffBatches.key("app", null, marked = true)).isNotNull()
        assertThat(HandoffBatches.key("app", "Trip")).isNotNull()
    }

    @Test
    fun `a batch without a key is not merged`() = runTest {
        val own = HandoffNotice(notFound = 2)
        assertThat(batches().takeNotices(null, own)).isEqualTo(own)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(b: HandoffBatches, into: MutableList<HandoffNotice>) =
        launch { b.lateNotices.collect { into += it } }
}
