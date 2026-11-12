@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.blobs

import android.util.Log
import androidx.room.withTransaction
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.db.CloudCandidate
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.db.SyncUploadEntity
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import dev.hfps.reveal.sync.api.UploadCreateRequest
import dev.hfps.reveal.sync.blobs.SmartPreviewMaker.Companion.toHex
import dev.hfps.reveal.sync.engine.Kinds
import dev.hfps.reveal.sync.engine.SyncStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A readable blob: its size and a stream from an offset. */
private interface BlobSource : AutoCloseable {
    val size: Long

    fun open(offset: Long): InputStream
}

/**
 * Smart preview and originals backup uploads (ADR 0017 "Chunked, resumable uploads", ADR 0018). Every upload is a
 * `sync_uploads` row whose server upload id and offset are saved after each chunk, so a process killed mid-way resumes
 * from the server's `Upload-Offset` (asked with `HEAD`). Originals are read from their document, never copied.
 */
class BlobUploader(
    private val db: RevealDatabase,
    private val store: SyncStore,
    private val trees: DocumentTreeFactory,
    private val maker: SmartPreviewMaker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sync = db.syncDao()
    private var serverChunk: Long? = null

    /** Progress callback: bytes sent of the current blob. */
    fun interface Progress {
        fun onBytes(sent: Long, total: Long)
    }

    // ------------------------------------------------------------------ previews

    /** Makes and uploads the smart previews this device owes (up to [limit] new ones), then the pending uploads. */
    suspend fun previews(api: SyncApi, limit: Int, noBackup: Boolean, progress: Progress = Progress { _, _ -> }): Int {
        var made = 0
        for (candidate in sync.previewCandidates(noBackup, limit)) {
            currentCoroutineContext().ensureActive()
            val preview = runCatching { makePreview(candidate) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "No smart preview for photo ${candidate.photoId}: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (preview == null) {
                // Unreadable (unplugged, damaged): remember this file so it is not tried on every run.
                val cloud = sync.cloud(candidate.photoId) ?: PhotoCloudEntity(candidate.photoId)
                sync.putCloud(cloud.copy(previewFingerprint = candidate.fingerprint))
                continue
            }
            sync.insertUpload(
                SyncUploadEntity(
                    photoId = candidate.photoId,
                    kind = SyncUploadEntity.KIND_PREVIEW,
                    sha256 = preview.sha256,
                    size = preview.size,
                    fingerprint = candidate.fingerprint,
                    file = preview.file.path,
                    width = preview.width,
                    height = preview.height,
                    updatedAt = clock(),
                ),
            )
            made++
        }
        var sent = 0
        for (upload in sync.pendingUploads(SyncUploadEntity.KIND_PREVIEW, PENDING_BATCH)) {
            currentCoroutineContext().ensureActive()
            if (sendPreview(api, upload, progress)) sent++
        }
        return made + sent
    }

    private suspend fun makePreview(candidate: CloudCandidate): MadePreview {
        val source = db.sourceDao().get(candidate.sourceId) ?: throw IOException("no source")
        return trees.open(source).openInput(candidate.documentId, candidate.name).use { maker.make(it) }
    }

    private suspend fun sendPreview(api: SyncApi, upload: SyncUploadEntity, progress: Progress): Boolean {
        val file = upload.file?.let(::File)
        if (file == null || !file.exists()) {
            sync.updateUpload(upload.copy(state = SyncUploadEntity.STATE_FAILED, updatedAt = clock()))
            return false
        }
        val done = send(api, upload, FileSource(file), progress)
        if (done) {
            previewUploaded(upload.photoId, upload.sha256, upload.width ?: 0, upload.height ?: 0, upload.fingerprint)
            file.delete()
        }
        return done
    }

    private suspend fun previewUploaded(photoId: Long, sha: String, width: Int, height: Int, fingerprint: String?) {
        db.withTransaction {
            val cloud = sync.cloud(photoId) ?: PhotoCloudEntity(photoId)
            sync.putCloud(
                cloud.copy(
                    previewSha = sha,
                    previewWidth = width,
                    previewHeight = height,
                    previewFingerprint = fingerprint,
                ),
            )
            // The photo record now names its preview: push it.
            val photo = db.photoDao().get(photoId) ?: return@withTransaction
            val record = sync.record(Kinds.PHOTO, photo.uuid)
            sync.putRecord(
                record?.copy(dirty = true, modifiedAt = clock())
                    ?: SyncRecordEntity(Kinds.PHOTO, photo.uuid, 0, dirty = true, modifiedAt = clock()),
            )
        }
    }

    // ------------------------------------------------------------------ originals

    /** Queues the device-only originals of folders with the backup on and uploads them, newest first. */
    suspend fun originals(api: SyncApi, limit: Int, progress: Progress = Progress { _, _ -> }): Int {
        for (candidate in sync.backupCandidates(limit)) {
            sync.insertUpload(
                SyncUploadEntity(
                    photoId = candidate.photoId,
                    kind = SyncUploadEntity.KIND_ORIGINAL,
                    sha256 = "",
                    size = candidate.size,
                    fingerprint = candidate.fingerprint,
                    updatedAt = clock(),
                ),
            )
        }
        var sent = 0
        for (upload in sync.pendingUploads(SyncUploadEntity.KIND_ORIGINAL, limit)) {
            currentCoroutineContext().ensureActive()
            val ok = runCatching { sendOriginal(api, upload, progress) }
                .onFailure { if (it is CancellationException || it is SyncHttpException) throw it }
                .onFailure {
                    Log.w(TAG, "Original of photo ${upload.photoId} not uploaded: ${it.javaClass.simpleName}")
                }
                .getOrDefault(false)
            if (ok) sent++
        }
        return sent
    }

    private suspend fun sendOriginal(api: SyncApi, queued: SyncUploadEntity, progress: Progress): Boolean {
        val photo = db.photoDao().get(queued.photoId) ?: return false.also { sync.deleteUpload(queued.id) }
        val main = SyncStore.mainFile(db.photoDao().files(photo.id))
        val source = db.sourceDao().get(photo.sourceId)
        if (main == null || source == null || main.fingerprint != queued.fingerprint) {
            // The file changed or went away since it was queued: the next lookup decides again.
            sync.deleteUpload(queued.id)
            return false
        }
        val input = trees.open(source).openInput(main.documentId, main.name)
        DocumentSource(input).use { blob ->
            var upload = queued
            if (upload.sha256.isEmpty() || upload.size != blob.size) {
                val sha = blob.open(0).use(::sha256)
                upload = upload.copy(sha256 = sha, size = blob.size, uploadId = null, offset = 0)
                sync.updateUpload(upload)
                val cloud = sync.cloud(photo.id) ?: PhotoCloudEntity(photo.id)
                sync.putCloud(cloud.copy(originalSha = sha))
            }
            val done = send(api, upload, blob, progress)
            if (done) store.lookedUp(photo.id, SyncStore.SOURCE_BACKUP)
            return done
        }
    }

    // ------------------------------------------------------------------ the chunked upload

    /** Uploads [blob] for [queued], resuming where the server stands. True when the server holds it. */
    @Suppress(
        "CyclomaticComplexMethod",
        "LoopWithTooManyJumpStatements",
        "LongMethod",
        "NestedBlockDepth",
        "ThrowsCount",
    )
    private suspend fun send(api: SyncApi, queued: SyncUploadEntity, blob: BlobSource, progress: Progress): Boolean {
        var upload = queued
        var offset: Long
        var chunk = DEFAULT_CHUNK
        val known = upload.uploadId?.let { api.uploadOffset(it) }
        if (upload.uploadId != null && known != null) {
            offset = known
            // Resuming: the chunk size is the server's (ADR 0017 limits), asked once per uploader.
            chunk = serverChunk ?: (api.server().limit("chunkSize") ?: DEFAULT_CHUNK).also { serverChunk = it }
            chunk = chunk.coerceIn(1, DEFAULT_CHUNK)
        } else {
            // Only an original names its fingerprint (the server checks it); a preview must not (ADR 0017).
            val fingerprint = upload.fingerprint.takeIf { upload.kind == SyncUploadEntity.KIND_ORIGINAL }
            val created = api.createUpload(UploadCreateRequest(upload.sha256, upload.size, upload.kind, fingerprint))
            if (created.status == STATUS_EXISTS) {
                finish(upload)
                return true
            }
            upload = upload.copy(uploadId = created.uploadId, offset = created.offset, updatedAt = clock())
            sync.updateUpload(upload)
            offset = created.offset
            if (created.chunkSize > 0) chunk = created.chunkSize.coerceAtMost(DEFAULT_CHUNK)
        }
        val id = checkNotNull(upload.uploadId)
        var failures = 0
        while (offset < upload.size) {
            currentCoroutineContext().ensureActive()
            val length = minOf(chunk, upload.size - offset).toInt()
            val start = offset
            val result = try {
                blob.open(start).use { stream ->
                    api.patchUpload(id, start, length) { sink -> copy(stream, sink, length) }
                }
            } catch (e: SyncHttpException) {
                val serverOffset = e.details?.get("offset")?.jsonPrimitive?.longOrNull
                when {
                    e.code == "checksum_mismatch" -> {
                        upload = upload.copy(attempts = upload.attempts + 1, offset = 0)
                        if (upload.kind == SyncUploadEntity.KIND_ORIGINAL || upload.attempts >= MAX_CHECKSUM_FAILURES) {
                            // The file is not what was hashed (edited in place?): start over from a new hash later.
                            sync.updateUpload(upload.copy(state = SyncUploadEntity.STATE_FAILED, updatedAt = clock()))
                            return false
                        }
                        offset = 0
                    }

                    serverOffset != null -> offset = serverOffset

                    e.transient && ++failures <= MAX_FAILURES -> offset = api.uploadOffset(id) ?: return false

                    else -> throw e
                }
                sync.updateUpload(upload.copy(offset = offset, updatedAt = clock()))
                continue
            } catch (e: IOException) {
                // The connection broke mid-chunk: the bytes that arrived are kept; ask where to go on.
                if (++failures > MAX_FAILURES) throw e
                offset = api.uploadOffset(id) ?: throw e
                continue
            }
            offset = result.offset
            upload = upload.copy(offset = offset, updatedAt = clock())
            sync.updateUpload(upload)
            progress.onBytes(offset, upload.size)
            if (result.complete) break
        }
        finish(upload)
        return true
    }

    private suspend fun finish(upload: SyncUploadEntity) {
        sync.updateUpload(upload.copy(state = SyncUploadEntity.STATE_DONE, offset = upload.size, updatedAt = clock()))
    }

    private class FileSource(private val file: File) : BlobSource {
        override val size: Long = file.length()

        override fun open(offset: Long): InputStream = FileInputStream(file).also { it.channel.position(offset) }

        override fun close() = Unit
    }

    /** A document read through its descriptor (a duplicate per stream, positioned with the channel). */
    private class DocumentSource(private val input: ImageInput) : BlobSource {
        override val size: Long = input.descriptor.statSize

        override fun open(offset: Long): InputStream {
            val dup = input.descriptor.dup()
            val stream = object : FileInputStream(dup.fileDescriptor) {
                override fun close() {
                    super.close()
                    dup.close()
                }
            }
            stream.channel.position(offset)
            return stream
        }

        override fun close() = input.close()
    }

    companion object {
        private const val TAG = "RevealSync"
        private const val STATUS_EXISTS = "exists"
        const val DEFAULT_CHUNK = 8L * 1024 * 1024
        private const val PENDING_BATCH = 50
        private const val MAX_FAILURES = 5
        private const val MAX_CHECKSUM_FAILURES = 3
        private const val BUFFER = 64 * 1024

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().toHex()
        }

        private fun copy(input: InputStream, sink: okio.BufferedSink, length: Int) {
            val buffer = ByteArray(BUFFER)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, left))
                if (n < 0) throw IOException("the file ended early")
                sink.write(buffer, 0, n)
                left -= n
            }
        }
    }
}
