package dev.hfps.reveal.library

import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.sync.account.PairingLink
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prepares the large library of W3/C's measurements (ADR 0018 "W3/C") and keeps it (no tear-down): pairs with a
 * local hoard-server whose synthetic NAS holds 20,000+ photos (`-e syncLink`), hands over the files pushed to
 * `-e handoffDir` (copies of NAS files: the same fingerprints, so the server holds them too) through the debug
 * provider, then syncs until the Server source is complete. Skipped without its arguments, so the full suite never runs
 * it; unpair with `-e class ...LargeLibrarySetup#unpair`.
 */
@RunWith(AndroidJUnit4::class)
class LargeLibrarySetup {
    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun prepare() {
        val link = args.getString("syncLink")
        assumeTrue("needs -e syncLink <link>", link != null)
        val sync = access.sync()
        runBlocking {
            if (sync.account.value == null) sync.pair(checkNotNull(PairingLink.parse(checkNotNull(link))))
        }
        args.getString("handoffDir")?.let(::handOver)
        val started = System.nanoTime()
        var last = -1
        var stable = 0
        while (stable < STABLE_RUNS && System.nanoTime() - started < TIMEOUT_NS) {
            runBlocking { sync.syncMetadata() }
            val count = runBlocking { access.library().serverPhotoCount().first() }
            log("server photos: $count")
            stable = if (count == last) stable + 1 else 0
            last = count
        }
        val db = access.database()
        val onServer = db.query("SELECT COUNT(*) FROM photo_files WHERE on_server = 1", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        log("ready: $last server photos, $onServer files on the server")
    }

    /** Hands the files of [dir] (pushed with adb) over in batches, like a share of several photos from another app. */
    private fun handOver(dir: String) {
        val inbox = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/w3c-handoffs").apply { mkdirs() }
        val files = File(dir).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        files.chunked(BATCH).forEach { batch ->
            val uris = batch.map { file ->
                file.copyTo(File(inbox, file.name), overwrite = true)
                DocumentsContract.buildDocumentUri(SyntheticDocumentsProvider.AUTHORITY, "w3c-handoffs/${file.name}")
            }
            runBlocking { access.handoffs().receive(HandoffRequest(uris)) }
        }
        log("handed over ${files.size} files")
    }

    @Test
    fun unpair() {
        assumeTrue("needs -e unpair true", args.getString("unpair") == "true")
        runBlocking { access.sync().unpair() }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "REVEAL_W3C"
        const val BATCH = 50
        const val STABLE_RUNS = 3
        const val TIMEOUT_NS = 30L * 60 * 1_000_000_000
    }
}
