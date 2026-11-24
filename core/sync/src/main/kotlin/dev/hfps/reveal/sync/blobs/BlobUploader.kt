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
import dev.hfps.reveal.data.handoff.LocalPreviews
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.storage.FileOpens
import dev.hfps.reveal.data.storage.isAccessError
import dev.hfps.reveal.data.storage.openedIn
import dev.hfps.reveal.data.sync.BackupCoverage
import dev.hfps.reveal.data.sync.BackupRoot
import dev.hfps.reveal.data.sync.StorageLocation
import dev.hfps.reveal.pipeline.preview.MadePreview
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker.Companion.toHex
import dev.hfps.reveal.sync.api.DropResult
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import dev.hfps.reveal.sync.api.UploadCreateRequest
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Sync's opens of other apps' documents: a cancelled run stops waiting for a FileOpens slot (ADR 0018 "W3/C"). An
 * original's slot is held for its whole chunked upload (the file is read as it is sent, one upload at a time); nothing
 * inside the upload waits for another slot.
 */
private val SYNC_IO = FileOpens.io("sync")

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
    /** Hand-offs' local smart previews (ADR 0011 "Unreadable hand-offs"): uploaded as they are. */
    private val local: LocalPreviews? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sync = db.syncDao()
    private var serverChunk: Long? = null

    /** Progress callback: bytes sent of the current blob. */
    fun interface Progress {
        fun onBytes(sent: Long, total: Long)
    }

    // ------------------------------------------------------------------ previews

    /**
     * Makes and uploads the smart previews this device owes (up to [limit] new ones, ADR 0018 "Smart previews":
     * device-only photos whose original cannot go up, unreadable ones and, when [originalsOff], edited ones), plus up
     * to [reformat] made again because this device uploaded them in an older format (0.5.x, about 9 MB: the caller
     * asks for those on an unmetered network only), then the pending uploads. Returns how many candidates moved on:
     * made, sent, or set aside because they cannot be made (never tried again until their file changes).
     */
    suspend fun previews(
        api: SyncApi,
        limit: Int,
        originalsOff: Boolean = false,
        reformat: Int = 0,
        progress: Progress = Progress { _, _ -> },
    ): Int {
        var made = 0
        var parked = 0
        val outdated = if (reformat > 0) {
            sync.outdatedPreviews(originalsOff, SmartPreviewCodec.VERSION, reformat)
        } else {
            emptyList()
        }
        for (candidate in sync.previewCandidates(originalsOff, limit) + outdated) {
            currentCoroutineContext().ensureActive()
            val preview = runCatching { makePreview(candidate) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "No smart preview for photo ${candidate.photoId}: ${describe(it)}") }
                .getOrNull()
            if (preview == null) {
                setAside(candidate, outdated = candidate in outdated)
                parked++
            } else {
                queuePreview(candidate, preview)
                made++
            }
        }
        var sent = 0
        for (upload in sync.pendingUploads(SyncUploadEntity.KIND_PREVIEW, PENDING_BATCH)) {
            currentCoroutineContext().ensureActive()
            if (sendPreview(api, upload, progress)) sent++
        }
        return made + sent + parked
    }

    /** A preview that cannot be made: not tried again for this file. */
    private suspend fun setAside(candidate: CloudCandidate, outdated: Boolean) {
        val cloud = sync.cloud(candidate.photoId) ?: PhotoCloudEntity(candidate.photoId)
        if (outdated) {
            // Given up making it again: the old preview stays (it still develops).
            sync.putCloud(cloud.copy(previewFormat = SmartPreviewCodec.VERSION))
        } else {
            // Unreadable (unplugged, damaged): remember this file so it is not tried on every run.
            sync.putCloud(cloud.copy(previewFingerprint = candidate.fingerprint))
        }
    }

    private suspend fun queuePreview(candidate: CloudCandidate, preview: MadePreview) {
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
    }

    /**
     * Previews this device made for photos that no longer qualify (unedited again, or their original reached the
     * server): the photo record stops naming them (pushed by the next metadata sync) and a drop is queued for
     * [drops]. Local only; returns how many were marked.
     */
    suspend fun markDrops(limit: Int = DROP_BATCH): Int {
        val stale = sync.droppablePreviews(limit)
        for (preview in stale) {
            db.withTransaction {
                val cloud = sync.cloud(preview.photoId) ?: return@withTransaction
                sync.putCloud(
                    cloud.copy(
                        previewSha = null,
                        previewWidth = null,
                        previewHeight = null,
                        previewFingerprint = null,
                        previewFormat = null,
                    ),
                )
                queueDrop(preview.photoId, preview.sha256)
                markPhotoDirty(preview.photoId)
            }
        }
        return stale.size
    }

    /**
     * Asks the server to drop the queued previews (`DELETE /v1/blobs/{sha256}`, ADR 0017). One a photo record still
     * names there waits for the push (the next run asks again, a bounded number of times); a server without the route
     * keeps them all queued. Returns how many were settled.
     */
    suspend fun drops(api: SyncApi, limit: Int = DROP_BATCH): Int {
        var settled = 0
        for (drop in sync.pendingUploads(SyncUploadEntity.KIND_DROP, limit)) {
            currentCoroutineContext().ensureActive()
            if (sync.previewNamed(drop.sha256)) {
                // Named again here (the same preview made again): nothing to drop.
                sync.deleteUpload(drop.id)
                settled++
                continue
            }
            when (api.dropPreview(drop.sha256)) {
                DropResult.DROPPED, DropResult.GONE, DropResult.REFUSED, DropResult.MIRROR -> {
                    sync.deleteUpload(drop.id)
                    settled++
                }

                DropResult.REFERENCED -> {
                    val attempts = drop.attempts + 1
                    val state = if (attempts >= MAX_DROP_ATTEMPTS) SyncUploadEntity.STATE_FAILED else drop.state
                    sync.updateUpload(drop.copy(attempts = attempts, state = state, updatedAt = clock()))
                }

                DropResult.UNSUPPORTED -> {
                    Log.i(TAG, "The server cannot drop previews yet: they wait for its update")
                    return settled
                }
            }
        }
        return settled
    }

    /**
     * Deletes the originals the user deleted from the server (ADR 0018 "Deleting from the server"):
     * `DELETE /v1/originals/{fingerprint}` once the photos' tombstones are pushed (the sync run calls this after its
     * push). One a live record still names waits and is given up after a few tries (another device may have the photo
     * and edited it since: its record keeps the original); a server before 0.5.3 keeps them queued. Returns how many
     * left the queue.
     */
    suspend fun deleteOriginals(api: SyncApi, limit: Int = DROP_BATCH): Int {
        var done = 0
        var mirrored = 0
        for (queued in sync.pendingDeletes(limit)) {
            currentCoroutineContext().ensureActive()
            when (api.deleteOriginal(queued.fingerprint)) {
                DropResult.MIRROR -> {
                    // Hoard's phone backup holds it (ADR 0018 "R1"): done at once; the server keeps the file, so
                    // whatever here names it is still backed up, and the owner is told where it stays.
                    sync.deleteQueued(queued.fingerprint)
                    sync.setOnServer(queued.fingerprint, true)
                    mirrored++
                    done++
                }

                DropResult.DROPPED, DropResult.GONE, DropResult.REFUSED -> {
                    sync.deleteQueued(queued.fingerprint)
                    // Nothing here should think the server still has it.
                    sync.setOnServer(queued.fingerprint, false)
                    done++
                }

                DropResult.REFERENCED -> {
                    val attempts = queued.attempts + 1
                    if (attempts >= MAX_DROP_ATTEMPTS) {
                        sync.deleteQueued(queued.fingerprint)
                        done++
                    } else {
                        sync.updateDelete(queued.copy(attempts = attempts))
                    }
                }

                DropResult.UNSUPPORTED -> {
                    Log.i(TAG, "The server cannot delete originals yet: they wait for its update")
                    break
                }
            }
        }
        if (mirrored > 0) noteMirrorKept(mirrored)
        return done
    }

    /** Adds [count] files to the "stays in the phone's backup" notice the library shows once ([KEY_MIRROR_KEPT]). */
    private suspend fun noteMirrorKept(count: Int) {
        val meta = db.metaDao()
        val before = meta.get(KEY_MIRROR_KEPT)?.toIntOrNull() ?: 0
        meta.put(dev.hfps.reveal.data.db.MetaEntity(KEY_MIRROR_KEPT, (before + count).toString()))
    }

    private suspend fun queueDrop(photoId: Long, sha: String) {
        sync.insertUpload(
            SyncUploadEntity(
                photoId = photoId,
                kind = SyncUploadEntity.KIND_DROP,
                sha256 = sha,
                size = 0,
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun markPhotoDirty(photoId: Long) {
        val photo = db.photoDao().get(photoId) ?: return
        val record = sync.record(Kinds.PHOTO, photo.uuid)
        sync.putRecord(
            record?.copy(dirty = true, modifiedAt = clock())
                ?: SyncRecordEntity(Kinds.PHOTO, photo.uuid, 0, dirty = true, modifiedAt = clock()),
        )
    }

    @Suppress("ThrowsCount")
    private suspend fun makePreview(candidate: CloudCandidate): MadePreview {
        // A hand-off's local smart preview is the same thing, made while Reveal could read it (ADR 0011).
        local?.smart(candidate.fingerprint)?.let { return maker.adopt(it) }
        val source = db.sourceDao().get(candidate.sourceId) ?: throw IOException("no source")
        return try {
            withContext(SYNC_IO) {
                trees.open(source).openInput(candidate.documentId, candidate.name).use { maker.make(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (isAccessError(e)) db.photoDao().setUnreadable(candidate.documentId, clock())
            throw e
        }
    }

    private suspend fun sendPreview(api: SyncApi, upload: SyncUploadEntity, progress: Progress): Boolean {
        val file = upload.file?.let(::File)
        if (file == null || !file.exists()) {
            sync.updateUpload(upload.copy(state = SyncUploadEntity.STATE_FAILED, updatedAt = clock()))
            return false
        }
        val format = runCatching { file.inputStream().use(SmartPreviewCodec::version) }
            .getOrDefault(SmartPreviewCodec.VERSION)
        val done = send(api, upload, FileSource(file), progress)
        if (done) {
            previewUploaded(upload, format)
            file.delete()
        }
        return done
    }

    private suspend fun previewUploaded(upload: SyncUploadEntity, format: Int) {
        db.withTransaction {
            val cloud = sync.cloud(upload.photoId) ?: PhotoCloudEntity(upload.photoId)
            // The preview it replaces (an older format, or of the file before it changed), when this device made it:
            // dropped once the record names the new one.
            val replaced = cloud.previewSha?.takeIf {
                it != upload.sha256 && (cloud.previewFormat != null || cloud.previewFingerprint != null)
            }
            sync.putCloud(
                cloud.copy(
                    previewSha = upload.sha256,
                    previewWidth = upload.width ?: 0,
                    previewHeight = upload.height ?: 0,
                    previewFingerprint = upload.fingerprint,
                    previewFormat = format,
                ),
            )
            sync.cancelDrops(upload.sha256)
            if (replaced != null) queueDrop(upload.photoId, replaced)
            // The photo record now names its preview: push it.
            markPhotoDirty(upload.photoId)
        }
    }

    // ------------------------------------------------------------------ originals

    /**
     * Queues the device-only originals of folders with the backup on (and edited photos) and uploads them, newest
     * first.
     * With Hoard's phone backup (ADR 0018 "R1", Hoard ADR 0024: the server lists `backup` and this phone has roots), a
     * candidate whose file a root covers waits [GRACE_MS] from when it became one, is then looked up again and queued
     * only if the server still misses it; uncovered files go at once, as before.
     */
    suspend fun originals(api: SyncApi, limit: Int, progress: Progress = Progress { _, _ -> }): Int {
        val rootsJson = db.metaDao().get(BackupCoverage.META_ROOTS)
        val roots = BackupCoverage.decode(rootsJson)
        val now = clock()
        val due = ArrayList<CloudCandidate>()
        val folders = HashMap<Long, StorageLocation?>()
        // The wait applies only while Hoard covers the file: a root gone inactive (or out of the types) ends it.
        val uncovered = sync.inGrace(now - GRACE_MS).filter { roots.isEmpty() || !covered(it, roots, folders) }
        if (uncovered.isNotEmpty()) sync.endGrace(uncovered.map { it.photoId }.distinct())
        for (candidate in sync.backupCandidates(limit, now - GRACE_MS)) {
            val waited = roots.isNotEmpty() && covered(candidate, roots, folders)
            if (!waited) {
                queueOriginal(candidate)
                continue
            }
            val since = sync.cloud(candidate.photoId)?.candidateAt
            if (since == null) sync.startGrace(candidate.photoId, now) else due += candidate
        }
        if (due.isNotEmpty()) askAgain(api, due)
        var sent = 0
        val wait = rootsJson != null
        for (upload in sync.dueOriginals(clock(), limit)) {
            currentCoroutineContext().ensureActive()
            val ok = runCatching { sendOriginal(api, upload, progress, wait) }
                .onFailure { if (it is CancellationException || it is SyncHttpException) throw it }
                .onFailure { Log.w(TAG, "Original of photo ${upload.photoId} not uploaded: ${describe(it)}") }
                .getOrDefault(false)
            if (ok) sent++
        }
        return sent
    }

    private suspend fun queueOriginal(candidate: CloudCandidate) {
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

    /**
     * True when Hoard's phone backup covers [candidate]'s file (ADR 0018 "R1"): an `ExternalStorageProvider` folder
     * inside an active root of this phone, and a kind in that root's types. [folders] memoises the sources' locations.
     */
    private suspend fun covered(
        candidate: CloudCandidate,
        roots: List<BackupRoot>,
        folders: MutableMap<Long, StorageLocation?>,
    ): Boolean {
        val base = folders.getOrPut(candidate.sourceId) {
            db.sourceDao().get(candidate.sourceId)?.let { BackupCoverage.location(it.treeUri, it.rootDocumentId) }
        } ?: return false
        val photo = db.photoDao().get(candidate.photoId) ?: return false
        return BackupCoverage.coveringRoot(roots, base.child(photo.folder), candidate.name) != null
    }

    /**
     * Covered candidates whose grace is over: asked about again (`POST /v1/fingerprints/lookup`); those Hoard backed
     * up meanwhile are on the server now, the others are queued (and uploaded with `wait`).
     */
    private suspend fun askAgain(api: SyncApi, due: List<CloudCandidate>) {
        val found = HashMap<String, String>()
        val asked = due.map { it.fingerprint }.distinct()
        for (part in asked.chunked(LOOKUP_BATCH)) {
            for (hit in api.lookup(part).found) found[hit.fingerprint] = hit.source
        }
        store.filesLookedUp(asked, found.keys)
        for (candidate in due) {
            val source = found[candidate.fingerprint]
            if (source != null) store.lookedUp(candidate.photoId, source) else queueOriginal(candidate)
        }
    }

    private suspend fun sendOriginal(
        api: SyncApi,
        queued: SyncUploadEntity,
        progress: Progress,
        wait: Boolean = false,
    ): Boolean {
        val photo = db.photoDao().get(queued.photoId) ?: return false.also { sync.deleteUpload(queued.id) }
        val main = SyncStore.mainFile(db.photoDao().files(photo.id))
        val source = db.sourceDao().get(photo.sourceId)
        if (main == null || source == null || main.fingerprint != queued.fingerprint) {
            // The file changed or went away since it was queued: the next lookup decides again.
            sync.deleteUpload(queued.id)
            return false
        }
        val input = try {
            // Returned across the switch: closed (its slot freed) if the job is cancelled meanwhile.
            openedIn(SYNC_IO) { trees.open(source).openInput(main.documentId, main.name) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (!isAccessError(e)) throw e
            // Reveal cannot read it now (a hand-off whose grant ended, ADR 0011): parked, not retried every run. It
            // is queued again once it can be read (`backupCandidates` skips unreadable files); its smart preview goes
            // meanwhile.
            db.photoDao().setUnreadable(main.documentId, clock())
            sync.deleteUpload(queued.id)
            Log.i(TAG, "Original of photo ${photo.id} cannot be read: it waits until it can (${describe(e)})")
            return false
        }
        DocumentSource(input).use { blob ->
            var upload = queued
            if (upload.sha256.isEmpty() || upload.size != blob.size) {
                val sha = blob.open(0).use(::sha256)
                upload = upload.copy(sha256 = sha, size = blob.size, uploadId = null, offset = 0)
                sync.updateUpload(upload)
                val cloud = sync.cloud(photo.id) ?: PhotoCloudEntity(photo.id)
                sync.putCloud(cloud.copy(originalSha = sha))
            }
            val done = send(api, upload, blob, progress, wait)
            if (done) store.originalLanded(photo.id, main.fingerprint ?: queued.fingerprint.orEmpty())
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
    private suspend fun send(
        api: SyncApi,
        queued: SyncUploadEntity,
        blob: BlobSource,
        progress: Progress,
        wait: Boolean = false,
    ): Boolean {
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
            // `wait` only to a server that lists `backup` (Hoard ADR 0024); `claim` stays absent: Reveal's uploads
            // claim.
            val waitFlag = true.takeIf { wait && upload.kind == SyncUploadEntity.KIND_ORIGINAL }
            val created = api.createUpload(
                UploadCreateRequest(upload.sha256, upload.size, upload.kind, fingerprint, wait = waitFlag),
            )
            if (created.status == STATUS_EXISTS) {
                finish(upload)
                return true
            }
            if (created.status == STATUS_IN_PROGRESS) {
                // Another device is sending the same bytes: stays queued, asked again later, never a failure.
                val seconds = created.retryAfter?.takeIf { it > 0 } ?: DEFAULT_RETRY_AFTER_S
                sync.updateUpload(upload.copy(retryAt = clock() + seconds * MILLIS, updatedAt = clock()))
                return false
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

        /**
         * `meta`: files "Delete from server" could not delete because Hoard's phone backup holds them (`409 mirror`),
         * not yet told to the owner; the library shows it once and clears it.
         */
        const val KEY_MIRROR_KEPT = "sync.mirrorKept"

        /**
         * A failure for the log: its class, and where it was thrown in Reveal's code (class, method, line), so a
         * report from a release build points at the cause. The message only for programming errors (no URIs).
         */
        fun describe(error: Throwable): String {
            val frame = error.stackTrace.firstOrNull { it.className.startsWith("dev.hfps.") }
                ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
            val message = error.message?.takeIf { error is IllegalArgumentException || error is IllegalStateException }
                ?.let { ": " + it.take(MAX_MESSAGE) }.orEmpty()
            return error.javaClass.simpleName + message + frame
        }

        private const val MAX_MESSAGE = 120
        private const val STATUS_EXISTS = "exists"
        private const val STATUS_IN_PROGRESS = "in_progress"

        /** Seconds before asking again after `in_progress` when the server does not say (Hoard ADR 0024). */
        const val DEFAULT_RETRY_AFTER_S = 300L
        private const val MILLIS = 1000L

        /** How long a candidate Hoard's phone backup covers waits before Reveal asks again (Hoard ADR 0024). */
        const val GRACE_MS = 24L * 60 * 60 * 1000
        private const val LOOKUP_BATCH = 1000
        const val DEFAULT_CHUNK = 8L * 1024 * 1024
        private const val PENDING_BATCH = 50
        private const val DROP_BATCH = 50

        /** A drop the server keeps answering `referenced` for (the record never got pushed): given up after this. */
        private const val MAX_DROP_ATTEMPTS = 20
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
