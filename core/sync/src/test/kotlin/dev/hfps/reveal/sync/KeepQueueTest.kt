package dev.hfps.reveal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.sync.files.RangeCache
import dev.hfps.reveal.sync.files.RemoteFile
import dev.hfps.reveal.sync.files.ServerFiles
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.junit.Test
import org.junit.runner.RunWith

/** Keeping whole originals is bounded: a big export does not queue hundreds of downloads (ADR 0018 "W3/C"). */
@RunWith(AndroidJUnit4::class)
class KeepQueueTest {
    @Test
    fun `at most one download runs and a few wait, the rest of a 500-photo export is not queued`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        val dir = Files.createTempDirectory("keep").toFile()
        val files = ServerFiles(db, { null }, dir)
        val cache = RangeCache(Files.createTempDirectory("ranges").toFile())
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        repeat(500) { n ->
            val remote = RemoteFile(RangeCache.BLOCK * 2, "k$n", cache) { _, length ->
                started.countDown()
                gate.await()
                ByteArray(length)
            }
            files.keep("v1:%064x".format(n), remote)
        }
        started.await()
        // One running plus the queue (4): nothing else is held.
        assertThat(files.pendingKeeps).isAtMost(5)
        gate.countDown()
        db.close()
    }
}
