package dev.hfps.reveal.handoff

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.data.handoff.ServerItemRef
import dev.hfps.reveal.data.handoff.ServerRefs
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.debug.HandoffHostActivity
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.library.HandoffServerTags
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.sync.account.PairingLink
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hand-offs of server references (ADR 0011 "Amendment W3/H") sent as real intents to [MainActivity]: not paired (the
 * sheet offers to pair, Cancel forgets the batch), paired with another server (the sheet names it), an item the server
 * does not have, a URI item mixed with one, and 500 items at once. The paired cases need `-e syncLink` (a local
 * hoard-server, `scripts/sync/local-server.sh pair <dir>`) and skip without it. Locale-independent (test tags).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HandoffServerRefTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = TestPhotos.context
    private val args = InstrumentationRegistry.getArguments()
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val stem = "HS${System.nanoTime() % 1_000_000_000}"
    private val inbox = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/inbox-$stem")
    private var paired = false

    @After
    fun tearDown() {
        runBlocking {
            access.handoffs().dropPending()
            access.library().setLastFilters(PhotoFilters.NONE)
            if (paired) access.sync().unpair()
        }
        inbox.deleteRecursively()
    }

    private fun pairWithLink() {
        val link = args.getString("syncLink")
        assumeTrue("needs -e syncLink (scripts/sync/local-server.sh pair)", link != null)
        runBlocking {
            access.sync().unpair()
            access.sync().pair(checkNotNull(PairingLink.parse(checkNotNull(link))))
        }
        paired = true
    }

    private fun send(refs: ServerRefs, uri: Uri? = null, title: String = stem): Intent = HandoffIntents.serverExtras(
        Intent(HandoffIntents.ACTION_DEVELOP).setClass(context, MainActivity::class.java)
            .putExtra(HandoffIntents.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        refs,
    ).also { if (uri != null) it.setData(uri) }

    private fun item() = ServerItemRef(UUID.randomUUID().toString(), "v1:" + "%064x".format(System.nanoTime()))

    private fun waitForSheet(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)

    @Test
    fun notPairedOffersToPairAndCancelForgetsTheBatch() {
        runBlocking { access.sync().unpair() }
        val intent = send(ServerRefs(UUID.randomUUID().toString(), listOf(item(), item())))
        ActivityScenario.launch<MainActivity>(intent).use {
            waitForSheet(HandoffServerTags.PAIR)
            assertThat(runBlocking { access.handoffs().hasPending() }).isTrue()
            compose.onNodeWithTag(HandoffServerTags.CANCEL).performClick()
            compose.waitUntil(TIMEOUT_MS) { !runBlocking { access.handoffs().hasPending() } }
        }
    }

    @Test
    fun pairedWithAnotherServerNamesItAndKeepsNothing() {
        pairWithLink()
        val intent = send(ServerRefs(UUID.randomUUID().toString(), listOf(item())))
        ActivityScenario.launch<MainActivity>(intent).use {
            waitForSheet(HandoffServerTags.OK)
            compose.onNodeWithTag(HandoffServerTags.OK).performClick()
            assertThat(runBlocking { access.handoffs().hasPending() }).isFalse()
        }
    }

    @Test
    fun anItemTheServerDoesNotHaveIsReported() {
        pairWithLink()
        val serverId = checkNotNull(access.sync().account.value).serverId
        ActivityScenario.launch<MainActivity>(send(ServerRefs(serverId, listOf(item())))).use {
            waitForSheet(HandoffServerTags.OK)
        }
    }

    @Test
    fun aUriItemOpensWhileTheMissingServerItemIsReported() {
        pairWithLink()
        val serverId = checkNotNull(access.sync().account.value).serverId
        inbox.mkdirs()
        val bytes = SyntheticFolder("unused-$stem").let { f ->
            f.gradientJpeg(7, 600, 400).also { f.dir.deleteRecursively() }
        } + stem.toByteArray()
        File(inbox, "$stem.jpg").writeBytes(bytes)
        val id = "inbox-$stem/$stem.jpg"
        val uri = DocumentsContract.buildDocumentUri(SyntheticDocumentsProvider.AUTHORITY, id)
        ActivityScenario.launch<MainActivity>(send(ServerRefs(serverId, listOf(item())), uri)).use {
            waitForSheet(HandoffServerTags.OK)
            val photos = runBlocking { access.library().photoList(PhotoQuery.FROM_APPS) }
            assertThat(photos.any { it.name.startsWith(stem) }).isTrue()
        }
        SyntheticDocumentsProvider.allow(context.filesDir, id)
    }

    @Test
    fun fiveHundredItemsAreOneBatch() {
        pairWithLink()
        val serverId = checkNotNull(access.sync().account.value).serverId
        val refs = ServerRefs(serverId, List(HandoffIntents.MAX_SERVER_ITEMS) { item() })
        val started = System.nanoTime()
        ActivityScenario.launch<MainActivity>(send(refs)).use {
            waitForSheet(HandoffServerTags.OK)
        }
        android.util.Log.i(
            "REVEAL_HANDOFF_TEST",
            "500 server items resolved in ${(System.nanoTime() - started) / 1_000_000} ms",
        )
    }

    /** A real JPEG in the debug provider, as another app would share it: its document id and URI. */
    private fun shared(name: String, seed: Int): Pair<String, Uri> {
        inbox.mkdirs()
        val bytes = SyntheticFolder("unused-$stem-$seed").let { f ->
            f.gradientJpeg(seed, 600, 400).also { f.dir.deleteRecursively() }
        } + "$stem-$name".toByteArray()
        File(inbox, name).writeBytes(bytes)
        val id = "inbox-$stem/$name"
        return id to DocumentsContract.buildDocumentUri(SyntheticDocumentsProvider.AUTHORITY, id)
    }

    /**
     * Another app's activity starts Reveal as Hoard does: `startActivity(s)` with no `FLAG_ACTIVITY_NEW_TASK`, so
     * Reveal's `MainActivity` lands in the sender's task and is never the task root.
     */
    private fun fromAnotherApp(vararg intents: Intent, block: () -> Unit) {
        ActivityScenario.launch(HandoffHostActivity::class.java).use { host ->
            host.onActivity { activity ->
                if (intents.size == 1) activity.startActivity(intents[0]) else activity.startActivities(intents)
            }
            block()
        }
    }

    private fun sharedIntent(photo: Pair<String, Uri>, index: Int?, initial: Boolean = false) =
        Intent(HandoffIntents.ACTION_DEVELOP)
            .setClass(context, MainActivity::class.java)
            .setData(photo.second)
            .putExtra(HandoffIntents.EXTRA_TITLE, stem)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also {
                if (index != null) it.putExtra(HandoffIntents.EXTRA_BATCH_INDEX, index)
                if (initial) it.putExtra(HandoffIntents.EXTRA_INITIAL_URI, photo.second)
            }

    private fun stemPhotos() = runBlocking { access.library().photoList(PhotoQuery.FROM_APPS) }
        .filter { it.name.contains(stem) }

    private fun mainActivities(): List<MainActivity> {
        val found = ArrayList<MainActivity>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val registry = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.entries.forEach { stage ->
                found += registry.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
            }
        }
        return found.distinct()
    }

    /**
     * An ordinary multi-select hand-off (Hoard's browse "Edit in Reveal"): one intent with a title, several photos and
     * no `INITIAL_URI`, started into the sender's task. It must not be taken for a silent member of a cascade: the
     * batch shows (the activity stays), whatever the task says.
     */
    @Test
    fun anOrdinaryMultiSelectFromAnotherAppShowsTheBatch() {
        val photos = (1..2).map { shared("$stem-$it.jpg", it) }
        val intent = Intent(HandoffIntents.ACTION_DEVELOP)
            .setClass(context, MainActivity::class.java)
            .putExtra(HandoffIntents.EXTRA_TITLE, stem)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = android.content.ClipData.newRawUri(stem, photos[0].second)
            .also { clip -> clip.addItem(android.content.ClipData.Item(photos[1].second)) }
        fromAnotherApp(intent) {
            compose.waitUntil(TIMEOUT_MS) { stemPhotos().size == 2 }
            // Not finished, not flashing back to the sender: it stays up for a while after ingesting.
            Thread.sleep(SETTLE_MS)
            val mains = mainActivities()
            assertThat(mains).hasSize(1)
            assertThat(mains.single().isFinishing).isFalse()
        }
        photos.forEach { SyntheticDocumentsProvider.allow(context.filesDir, it.first) }
    }

    /**
     * Hoard sends a big batch as several intents with one title through `startActivities` (docs/adr/0011 "Batches over
     * 500"), each with its `BATCH_INDEX`; the first carries the item to open. All of them are one "Opened from other
     * apps" batch, one stamp, one editor and no stack of `MainActivity` records.
     */
    @Test
    fun startActivitiesWithOneTitleIsOneBatchAndOneActivity() {
        val photos = (1..3).map { shared("$stem-$it.jpg", it) }
        fromAnotherApp(
            sharedIntent(photos[0], 0, initial = true),
            sharedIntent(photos[1], 1),
            sharedIntent(photos[2], 2),
        ) {
            compose.waitUntil(TIMEOUT_MS) { stemPhotos().size == 3 }
            val rows = stemPhotos()
            assertThat(rows.map { it.handoffAt }.distinct()).hasSize(1)
            assertThat(rows.map { it.handoffTitle }.distinct()).containsExactly(stem)
            // The cascade unwound: one MainActivity record is left, the one that carries the editor.
            compose.waitUntil(TIMEOUT_MS) { mainActivities().count { !it.isFinishing } == 1 }
        }
        photos.forEach { SyntheticDocumentsProvider.allow(context.filesDir, it.first) }
    }

    /** The same cascade with no item to open anywhere: the first member shows the library, it is not lost. */
    @Test
    fun aCascadeWithoutAnInitialItemStillShowsTheBatch() {
        val photos = (1..3).map { shared("$stem-$it.jpg", it) }
        fromAnotherApp(sharedIntent(photos[0], 0), sharedIntent(photos[1], 1), sharedIntent(photos[2], 2)) {
            compose.waitUntil(TIMEOUT_MS) { stemPhotos().size == 3 }
            Thread.sleep(SETTLE_MS)
            val mains = mainActivities().filter { !it.isFinishing }
            assertThat(mains).hasSize(1)
            assertThat(stemPhotos().map { it.handoffAt }.distinct()).hasSize(1)
        }
        photos.forEach { SyntheticDocumentsProvider.allow(context.filesDir, it.first) }
    }

    /**
     * Every member of a cascade reports a server item the server does not have: the notice is shown once for the
     * batch (the summed count is in `HandoffBatchesTest`), also for the members that finished silently.
     */
    @Test
    fun aCascadeShowsOneNoticeForAllItsMembers() {
        pairWithLink()
        val serverId = checkNotNull(access.sync().account.value).serverId
        val intents = (0..2).map { i ->
            send(ServerRefs(serverId, listOf(item())))
                .putExtra(HandoffIntents.EXTRA_BATCH_INDEX, i)
                .also { it.flags = it.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
        }
        fromAnotherApp(*intents.toTypedArray()) {
            waitForSheet(HandoffServerTags.OK)
            compose.onNodeWithTag(HandoffServerTags.OK).performClick()
            Thread.sleep(SETTLE_MS)
            compose.onAllNodes(hasTestTag(HandoffServerTags.OK)).assertCountEquals(0)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 3_000L
    }
}
