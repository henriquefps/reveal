package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.SyncUploadEntity
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.sync.api.SyncHttpException
import dev.hfps.reveal.sync.blobs.BlobUploader
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Chunked, resumable preview uploads against the fake server (ADR 0017 "Chunked, resumable uploads"). */
@RunWith(AndroidJUnit4::class)
class BlobUploaderTest {
    private val server = FakeSyncServer()
    private lateinit var phone: TestDevice
    private lateinit var dir: File
    private val noTrees = DocumentTreeFactory { error("no documents in this test") }

    @Before
    fun setUp() {
        val url = server.start()
        server.chunkSize = 1000
        phone = TestDevice("Phone", url, server.addDevice("Phone"), server.serverId)
        dir = Files.createTempDirectory("previews").toFile()
    }

    @After
    fun tearDown() {
        phone.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun uploader() = BlobUploader(phone.db, phone.store, noTrees, SmartPreviewMaker(dir)) { phone.now }

    /** A preview file queued for photo [photoId] as the maker would leave it. */
    private fun queue(photoId: Long, bytes: ByteArray): Pair<String, File> {
        val file = File(dir, "p${bytes.size}.rvsp").apply { writeBytes(bytes) }
        val sha = BlobUploader.sha256(ByteArrayInputStream(bytes))
        runBlocking {
            phone.db.syncDao().insertUpload(
                SyncUploadEntity(
                    photoId = photoId, kind = SyncUploadEntity.KIND_PREVIEW, sha256 = sha, size = bytes.size.toLong(),
                    fingerprint = "v1:" + "0".repeat(64), file = file.path, width = 40, height = 30, updatedAt = 0,
                ),
            )
        }
        return sha to file
    }

    @Test
    fun `a preview goes up in chunks and the photo record then names it`() {
        val photo = phone.addPhoto("DSC1", Random(1).nextBytes(512))
        val bytes = Random(2).nextBytes(4500)
        val (sha, file) = queue(photo, bytes)
        runBlocking { uploader().previews(phone.api, limit = 0) }
        assertThat(server.blobs[sha]).isEqualTo(bytes)
        assertThat(server.patchCount.get()).isEqualTo(5)
        assertThat(file.exists()).isFalse()
        val cloud = runBlocking { phone.db.syncDao().cloud(photo) }!!
        assertThat(cloud.previewSha).isEqualTo(sha)
        assertThat(cloud.previewWidth).isEqualTo(40)
        // The photo record is pushed with its preview, and a second device learns it.
        phone.sync()
        assertThat(server.record("photo", runBlocking { phone.db.photoDao().get(photo)!!.uuid })!!.data.toString())
            .contains(sha)
    }

    @Test
    fun `an interrupted chunk resumes at the offset the server kept`() {
        val photo = phone.addPhoto("DSC2", Random(3).nextBytes(512))
        val bytes = Random(4).nextBytes(3000)
        val (sha, _) = queue(photo, bytes)
        server.cutNextChunkAt = 400
        runBlocking { uploader().previews(phone.api, limit = 0) }
        assertThat(server.blobs[sha]).isEqualTo(bytes)
    }

    @Test
    fun `an upload killed mid-way resumes in a new process from the server's offset`() {
        val photo = phone.addPhoto("DSC3", Random(5).nextBytes(512))
        val bytes = Random(6).nextBytes(5000)
        val (sha, _) = queue(photo, bytes)
        // The first run dies after two chunks (the server keeps failing until the process gives up).
        val first = uploader()
        server.failPatches = 0
        var chunks = 0
        assertThrows(SyncHttpException::class.java) {
            runBlocking {
                first.previews(phone.api, limit = 0) { sent, _ ->
                    chunks++
                    if (chunks == 2) server.failPatches = 100
                    assertThat(sent).isAtMost(2000L)
                }
            }
        }
        val stored = runBlocking { phone.db.syncDao().pendingUploads(SyncUploadEntity.KIND_PREVIEW, 10).single() }
        assertThat(stored.uploadId).isNotNull()
        assertThat(stored.offset).isEqualTo(2000L)
        // A new uploader (as after a restart) asks HEAD and goes on from 2000: three more chunks, not five.
        server.failPatches = 0
        val before = server.patchCount.get()
        runBlocking { uploader().previews(phone.api, limit = 0) }
        assertThat(server.blobs[sha]).isEqualTo(bytes)
        assertThat(server.patchCount.get() - before).isEqualTo(3)
    }

    @Test
    fun `a blob the server already holds is not sent again`() {
        val photo = phone.addPhoto("DSC4", Random(7).nextBytes(512))
        val bytes = Random(8).nextBytes(2000)
        val (sha, _) = queue(photo, bytes)
        server.blobs[sha] = bytes
        runBlocking { uploader().previews(phone.api, limit = 0) }
        assertThat(server.patchCount.get()).isEqualTo(0)
        assertThat(runBlocking { phone.db.syncDao().cloud(photo) }!!.previewSha).isEqualTo(sha)
    }
}
