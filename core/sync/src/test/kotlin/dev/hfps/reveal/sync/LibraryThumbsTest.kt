package dev.hfps.reveal.sync

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.ServerThumbState
import dev.hfps.reveal.data.storage.CacheBudget
import dev.hfps.reveal.data.storage.CacheItem
import dev.hfps.reveal.data.storage.CacheTier
import dev.hfps.reveal.data.storage.LibraryThumbsPreferences
import dev.hfps.reveal.data.thumb.ServerThumb
import dev.hfps.reveal.sync.thumbs.LibraryThumbs
import dev.hfps.reveal.sync.thumbs.ThumbCodec
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The library thumbnails (ADR 0018 "W3/C", Hoard ADR 0015) against the fake server: batched lookups and warms, the
 * immutable GETs, offline reads from the store, the prefetch's order and limits, and eviction last.
 */
@RunWith(AndroidJUnit4::class)
class LibraryThumbsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = FakeSyncServer()
    private lateinit var url: String
    private lateinit var phone: TestDevice
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = LibraryThumbsPreferences(null)
    private var reachable = true
    private var cap = Long.MAX_VALUE

    /** Stores the server's bytes as they are; "decodes" to a 1 x 1 bitmap. */
    private val codec = object : ThumbCodec {
        override fun shrink(bytes: ByteArray, edge: Int) = bytes.takeIf { it.isNotEmpty() }

        override fun decode(bytes: ByteArray): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun decode(file: File): Bitmap? = if (file.isFile) decode(file.readBytes()) else null
    }

    @Before
    fun setUp() {
        url = server.start()
        server.hoard(edits = true)
        phone = TestDevice("Phone", url, server.addDevice("Phone"), server.serverId)
    }

    @After
    fun tearDown() {
        scope.cancel()
        phone.close()
        server.shutdown()
    }

    private fun thumbs(dir: File = File(temp.root, "thumbs")) = LibraryThumbs(
        db = phone.db,
        api = { if (reachable) phone.api else dev.hfps.reveal.sync.api.SyncApi("http://127.0.0.1:9/", "rvk1_x") },
        dir = dir,
        preferences = preferences,
        scope = scope,
        codec = codec,
        cap = { cap },
        clock = { phone.now },
    )

    /** A hand-off photo whose file the server holds as an item with a ready thumb; returns its fingerprint. */
    private fun shot(seed: Int, status: String = "ready", time: Long = seed.toLong()): String {
        val jpeg = Random(seed).nextBytes(2048)
        val print = FileFingerprint.of(jpeg)
        val photo = phone.addHandoffPhoto("IMG$seed", jpeg)
        runBlocking {
            phone.db.syncDao().setOnServer(print, true)
            phone.db.photoDao().setMetadata(
                photo,
                time,
                null,
                null,
                null,
                1,
                dev.hfps.reveal.data.model.MetadataState.READ,
            )
        }
        val item = server.culling.addItem(listOf(print))
        server.previews.thumbs[item] = FakePreviews.Preview(status, "v$seed", "thumb-$seed".toByteArray())
        return print
    }

    @Test
    fun `the grid's cells share one lookup and one warm, then read the store offline`(): Unit = runBlocking {
        val prints = (1..6).map { shot(it) }
        val thumbs = thumbs()
        val results = coroutineScope { prints.map { async { thumbs.thumbnail(it) } }.map { it.await() } }
        assertThat(results.all { it is ServerThumb.Image }).isTrue()
        // Batched: the cells' requests join one or two warm requests, never one each.
        assertThat(server.previews.warms.size).isAtMost(2)
        assertThat(server.previews.warms.flatten()).hasSize(6)
        assertThat(server.previews.previewGets).hasSize(6)
        assertThat(server.previews.previewGets.all { it.contains("?v=v") }).isTrue()
        assertThat(phone.db.serverThumbDao().storedCount()).isEqualTo(6)

        // Offline: the store answers, nothing is asked.
        reachable = false
        val gets = server.previews.previewGets.size
        assertThat(thumbs().thumbnail(prints[0])).isInstanceOf(ServerThumb.Image::class.java)
        assertThat(server.previews.previewGets).hasSize(gets)
    }

    @Test
    fun `a pending thumb is later, an unavailable one is not served, a file the server lacks is not served`(): Unit =
        runBlocking {
            val pending = shot(1, "pending")
            val unavailable = shot(2, "unavailable")
            val lacking = FileFingerprint.of(Random(9).nextBytes(100))
            val thumbs = thumbs()
            assertThat(thumbs.thumbnail(pending)).isEqualTo(ServerThumb.Later)
            assertThat(thumbs.thumbnail(unavailable)).isEqualTo(ServerThumb.NotServed)
            assertThat(thumbs.thumbnail(lacking)).isEqualTo(ServerThumb.NotServed)
            assertThat(phone.db.serverThumbDao().get(pending)!!.state).isEqualTo(ServerThumbState.PENDING)
            // An unavailable one is not asked again for a week.
            val warms = server.previews.warms.size
            assertThat(thumbs.thumbnail(unavailable)).isEqualTo(ServerThumb.NotServed)
            assertThat(server.previews.warms).hasSize(warms)
        }

    @Test
    fun `the prefetch stores the whole library newest first, resumes, and stops at the budget`(): Unit = runBlocking {
        val prints = (1..12).map { shot(it, time = it * 1_000L) }
        val thumbs = thumbs()
        val first = thumbs.prefetch(maxItems = 5)
        assertThat(first.stored).isAtLeast(5)
        // Newest shot first.
        assertThat(server.previews.warms.first().first()).isEqualTo(
            server.culling.items.entries.first {
                prints[11] in it.value
            }.key,
        )
        val rest = thumbs.prefetch()
        assertThat(first.stored + rest.stored).isEqualTo(12)
        assertThat(rest.left).isFalse()
        assertThat(thumbs.prefetch().stored).isEqualTo(0)

        // Some evicted: a full budget stops it; the switch off stops it; then it fills the gaps.
        thumbs.evict(prints.take(3))
        cap = 1
        assertThat(thumbs.prefetch().stored).isEqualTo(0)
        cap = Long.MAX_VALUE
        preferences.setKeepAll(false)
        assertThat(thumbs.prefetch().stored).isEqualTo(0)
        preferences.setKeepAll(true)
        assertThat(thumbs.prefetch().stored).isEqualTo(3)
    }

    @Test
    fun `library thumbnails are evicted after ordinary caches and pinned originals`(): Unit = runBlocking {
        val prints = (1..3).map { shot(it) }
        val thumbs = thumbs()
        thumbs.prefetch()
        assertThat(thumbs.items().all { it.tier == CacheTier.LIBRARY }).isTrue()
        val budget = CacheBudget(null, scope)
        budget.register(thumbs)
        val evicted = java.util.Collections.synchronizedList(ArrayList<String>())
        val others = java.util.Collections.synchronizedList(
            mutableListOf(
                CacheItem("old-original", 100, lastUsed = 1, tier = CacheTier.PINNED),
                CacheItem("new-part", 100, lastUsed = Long.MAX_VALUE),
            ),
        )
        budget.register(
            object : dev.hfps.reveal.data.storage.CacheArea {
                override val kind = dev.hfps.reveal.data.storage.StorageKind.SERVER_FILES

                override suspend fun items() = others.toList()

                override suspend fun evict(keys: Collection<String>) {
                    evicted += keys
                    others.removeAll { it.key in keys }
                }

                override suspend fun clear() = Unit
            },
        )
        budget.setMaxBytes(thumbs.items().sumOf { it.bytes })
        budget.trim()
        // Everything else went first, even a part used later than every thumbnail.
        assertThat(evicted).containsExactly("new-part", "old-original").inOrder()
        assertThat(thumbs.items().map { it.key }).containsExactlyElementsIn(prints)
    }

    @Test
    fun `an edit made on another device is shown from the server's edited preview`(): Unit = runBlocking {
        val print = shot(1)
        server.previews.editThumbs["version-uuid"] = "edited".toByteArray()
        assertThat(thumbs().editedThumbnail(print, "version-uuid")).isNotNull()
        assertThat(thumbs().editedThumbnail(print, "other")).isNull()
    }
}
