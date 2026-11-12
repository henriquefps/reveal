package dev.hfps.reveal.handoff

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilter
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.library.SyntheticFolder
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unavailable photos (FIX/U) on a device: a document of the debug provider is handed to Reveal, then the provider
 * refuses it (the sender's grant ended) and its local previews are gone: nothing can show it. It is "unavailable" (the
 * filter and its count), "Try again" clears it once the provider reads again, and "Remove from Reveal" forgets it.
 * Locale-independent (data only).
 */
@RunWith(AndroidJUnit4::class)
class UnavailablePhotoTest {
    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val stem = "UP${System.nanoTime() % 1_000_000_000}"
    private val inbox = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/inbox-$stem")
    private val refused = mutableListOf<String>()

    @After
    fun tearDown() {
        refused.forEach { SyntheticDocumentsProvider.allow(context.filesDir, it) }
        inbox.deleteRecursively()
    }

    private fun jpeg(seed: Int) = SyntheticFolder("unused-$stem").let { f ->
        f.gradientJpeg(seed, 640, 427).also { f.dir.deleteRecursively() }
    } + "$stem-$seed".toByteArray()

    private fun share(name: String, bytes: ByteArray): Pair<String, Uri> {
        inbox.mkdirs()
        File(inbox, name).writeBytes(bytes)
        val id = "inbox-$stem/$name"
        return id to DocumentsContract.buildDocumentUri(SyntheticDocumentsProvider.AUTHORITY, id)
    }

    private fun rows(filter: PhotoFilter = PhotoFilter.ALL) = runBlocking {
        access.library().photoList(PhotoQuery(scope = LibraryScope.FromApps, filter = filter))
            .filter { it.name.startsWith(stem) }
    }

    /** Hands two photos over, then makes the first unavailable: no grant, no local preview. */
    private fun twoPhotosOneGone(): Triple<String, Uri, Long> {
        val bytes = jpeg(1)
        val (id, uri) = share("${stem}_1.jpg", bytes)
        val (_, other) = share("${stem}_2.jpg", jpeg(2))
        runBlocking { access.handoffs().receive(HandoffRequest(listOf(uri, other))) }
        runBlocking { access.handoffPreviewer().idle() }
        access.handoffPreviewer().previews.delete(listOf(FileFingerprint.of(bytes)))
        SyntheticDocumentsProvider.refuse(context.filesDir, id)
        refused += id
        assertThat(runBlocking { access.library().checkHandoffs() }).isAtLeast(1)
        return Triple(id, uri, rows().first { it.name.startsWith("${stem}_1") }.id)
    }

    @Test
    fun aPhotoWithNoGrantAndNoPreviewIsUnavailableAndTheFilterFindsIt() {
        val (_, _, goneId) = twoPhotosOneGone()

        assertThat(rows(PhotoFilter.UNAVAILABLE).map { it.id }).containsExactly(goneId)
        assertThat(rows().first { it.id == goneId }.unavailable).isTrue()
        assertThat(rows().filter { it.id != goneId }.map { it.unavailable }).containsExactly(false)
        assertThat(runBlocking { access.library().unavailableCount(LibraryScope.FromApps).first() }).isAtLeast(1)
    }

    @Test
    fun aPreviewKeepsAnUnreadablePhotoOutOfTheFilter() {
        val bytes = jpeg(3)
        val (id, uri) = share("${stem}_3.jpg", bytes)
        val (_, other) = share("${stem}_4.jpg", jpeg(4))
        runBlocking { access.handoffs().receive(HandoffRequest(listOf(uri, other))) }
        runBlocking { access.handoffPreviewer().idle() }
        SyntheticDocumentsProvider.refuse(context.filesDir, id)
        refused += id
        runBlocking { access.library().checkHandoffs() }

        val row = rows().first { it.name.startsWith("${stem}_3") }
        assertThat(row.unreadable).isTrue()
        assertThat(row.unavailable).isFalse()
        assertThat(rows(PhotoFilter.UNAVAILABLE)).isEmpty()
    }

    @Test
    fun tryAgainClearsThePhotoOnceItsGrantIsBack() {
        val (id, _, goneId) = twoPhotosOneGone()
        assertThat(runBlocking { access.library().recheckAccess(listOf(goneId), force = true) }).isAtLeast(1)
        assertThat(rows(PhotoFilter.UNAVAILABLE)).isNotEmpty()

        SyntheticDocumentsProvider.allow(context.filesDir, id)
        assertThat(runBlocking { access.library().recheckAccess(listOf(goneId), force = true) }).isEqualTo(0)
        assertThat(rows(PhotoFilter.UNAVAILABLE)).isEmpty()
        assertThat(rows().first { it.id == goneId }.unreadable).isFalse()
    }

    @Test
    fun removingFromRevealForgetsThePhotoAndKeepsTheOthers() {
        val (_, _, goneId) = twoPhotosOneGone()
        val others = rows().filter { it.id != goneId }.map { it.id }

        runBlocking { access.library().forget(listOf(goneId)) }

        assertThat(rows().map { it.id }).containsExactlyElementsIn(others)
        assertThat(rows(PhotoFilter.UNAVAILABLE)).isEmpty()
    }
}
