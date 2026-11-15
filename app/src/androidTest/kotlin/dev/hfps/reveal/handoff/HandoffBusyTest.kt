package dev.hfps.reveal.handoff

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.storage.FileOpens
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.library.SyntheticFolder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A busy sender (Hoard ADR 0010, `busy:`; ADR 0018 "W3/C") on a device: the debug provider answers "busy" to a hand-off
 * document's next opens. The access check retries with the backoff and finds it readable; a sender busy through
 * every retry leaves the document as it was (never "unreadable"); any other refusal still marks it. Locale-independent.
 */
@RunWith(AndroidJUnit4::class)
class HandoffBusyTest {
    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val stem = "HB${System.nanoTime() % 1_000_000_000}"
    private val inbox = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/inbox-$stem")
    private val ids = mutableListOf<String>()

    @After
    fun tearDown() {
        FileOpens.busyBackoffMs = FileOpens.BUSY_BACKOFF_MS
        ids.forEach {
            SyntheticDocumentsProvider.busy(context.filesDir, it, 0)
            SyntheticDocumentsProvider.allow(context.filesDir, it)
        }
        inbox.deleteRecursively()
    }

    private fun share(name: String): Pair<String, Uri> {
        val bytes = SyntheticFolder("unused-$stem").let { f ->
            f.gradientJpeg(name.hashCode(), 320, 213).also { f.dir.deleteRecursively() }
        } + "$stem-$name".toByteArray()
        inbox.mkdirs()
        File(inbox, name).writeBytes(bytes)
        val id = "inbox-$stem/$name"
        ids += id
        val uri = DocumentsContract.buildDocumentUri(SyntheticDocumentsProvider.AUTHORITY, id)
        runBlocking { access.handoffs().receive(HandoffRequest(listOf(uri))) }
        return id to uri
    }

    private fun unreadable(name: String) = runBlocking {
        access.library().photoList(PhotoQuery.FROM_APPS).single { it.name == name }.unreadable
    }

    @Test
    fun aBusySenderIsRetriedAndStaysReadable() {
        val name = "${stem}_1.jpg"
        val (id, _) = share(name)
        SyntheticDocumentsProvider.busy(context.filesDir, id, 3)
        runBlocking { access.library().checkHandoffs() }
        assertThat(SyntheticDocumentsProvider.busyLeft(context.filesDir, id)).isEqualTo(0)
        assertThat(unreadable(name)).isFalse()
    }

    @Test
    fun aSenderBusyThroughEveryRetryIsNeverMarkedUnreadable() {
        val busyName = "${stem}_2.jpg"
        val refusedName = "${stem}_3.jpg"
        val (busy, _) = share(busyName)
        val (refused, _) = share(refusedName)
        FileOpens.busyBackoffMs = longArrayOf(10, 20, 40)
        SyntheticDocumentsProvider.busy(context.filesDir, busy, 1_000)
        SyntheticDocumentsProvider.refuse(context.filesDir, refused)
        runBlocking { access.library().checkHandoffs() }
        assertThat(unreadable(busyName)).isFalse()
        assertThat(unreadable(refusedName)).isTrue()
    }
}
