package dev.hfps.reveal.data.index

import androidx.room.withTransaction
import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ImageKind
import dev.hfps.image.format.ListedFile
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.image.format.Shot
import dev.hfps.image.format.ShotPairing
import dev.hfps.image.source.readRange
import dev.hfps.image.tiff.JpegMarkers
import dev.hfps.reveal.data.db.IndexedFile
import dev.hfps.reveal.data.db.IndexedPhoto
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.MetadataState
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Phases of one indexing run, in order. */
enum class IndexPhase { LISTING, SAVING, SIDECARS, METADATA, DONE }

/** Where an indexing run is: [done] of [total] items of [phase] (total 0 = unknown, while listing). */
data class IndexProgress(val phase: IndexPhase, val done: Int, val total: Int)

/** What a run did and how long each phase took (logged and reported in ADR 0006). */
data class IndexReport(
    val files: Int,
    val folders: Int,
    val photosAdded: Int,
    val photosChanged: Int,
    val photosRemoved: Int,
    val sidecarsRead: Int,
    val headersRead: Int,
    val listMs: Long,
    val saveMs: Long,
    val sidecarMs: Long,
    val metadataMs: Long,
) {
    val totalMs: Long get() = listMs + saveMs + sidecarMs + metadataMs
}

/**
 * Indexes one source (ADR 0006, "Indexing"): walks the tree with `DocumentsContract` queries (one per folder, rows
 * read in one pass, cancellable between folders), pairs each folder's files into photos with `ShotPairing`, and
 * writes the difference to the database in batched transactions: new photos, changed files (size or mtime),
 * removed files. Then reads the XMP sidecars that are new or changed, then the headers (EXIF capture time with
 * sub-seconds, size, orientation; embedded XMP of JPEGs) of the photos marked pending, a few at a time. Files are
 * never copied or written.
 */
class LibraryIndexer(private val db: RevealDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val photos = db.photoDao()

    /** Indexes [sourceId] through [tree]. Throws when the tree cannot be listed (nothing is deleted then). */
    suspend fun index(sourceId: Long, tree: DocumentTree, onProgress: (IndexProgress) -> Unit = {}): IndexReport {
        var t = System.nanoTime()
        val folders = walk(tree, onProgress)
        val listMs = msSince(t)

        t = System.nanoTime()
        val save = save(sourceId, folders, onProgress)
        val saveMs = msSince(t)

        t = System.nanoTime()
        val sidecars = readSidecars(tree, save.sidecarQueue, onProgress)
        val sidecarMs = msSince(t)

        t = System.nanoTime()
        val headers = readHeaders(sourceId, tree, onProgress)
        val metadataMs = msSince(t)
        onProgress(IndexProgress(IndexPhase.DONE, 0, 0))
        return IndexReport(
            files = folders.sumOf { it.images.size },
            folders = folders.size,
            photosAdded = save.added,
            photosChanged = save.changed,
            photosRemoved = save.removed,
            sidecarsRead = sidecars,
            headersRead = headers,
            listMs = listMs,
            saveMs = saveMs,
            sidecarMs = sidecarMs,
            metadataMs = metadataMs,
        )
    }

    /** Reads the headers of the photos of [sourceId] still `PENDING` (the hand-off source has no walk, ADR 0011). */
    suspend fun readMetadata(sourceId: Long, tree: DocumentTree): Int = readHeaders(sourceId, tree) {}

    // ------------------------------------------------------------------ 1. walk

    /** A listed folder: its image files and its `.xmp` sidecars by lower-case name. */
    internal class ListedFolder(
        val path: String,
        val documentId: String,
        val images: List<DocumentEntry>,
        val sidecars: Map<String, DocumentEntry>,
    )

    private suspend fun walk(tree: DocumentTree, onProgress: (IndexProgress) -> Unit): List<ListedFolder> {
        val out = ArrayList<ListedFolder>()
        val pending = ArrayDeque<Pair<String, String>>().apply { add("" to tree.rootDocumentId) }
        var seen = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (path, documentId) = pending.removeFirst()
            val images = ArrayList<DocumentEntry>()
            val sidecars = HashMap<String, DocumentEntry>()
            val children = try {
                tree.children(documentId)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // The root must list; a sub-folder that fails (permissions, a vanished folder) is skipped.
                if (path.isEmpty()) throw e else continue
            }
            for (entry in children) {
                val name = entry.name
                when {
                    name.startsWith('.') -> Unit
                    entry.isDirectory -> pending.add(join(path, name) to entry.documentId)
                    name.endsWith(XMP_SUFFIX, ignoreCase = true) -> sidecars[name.lowercase()] = entry
                    ImageFormats.classify(name).isDisplayable -> images += entry
                }
            }
            seen += images.size
            if (images.isNotEmpty() || sidecars.isNotEmpty()) out += ListedFolder(path, documentId, images, sidecars)
            onProgress(IndexProgress(IndexPhase.LISTING, seen, 0))
        }
        return out
    }

    // ------------------------------------------------------------------ 2. save the difference

    private class SaveResult(val added: Int, val changed: Int, val removed: Int, val sidecarQueue: List<SidecarJob>)

    /** A sidecar to read for a photo. */
    internal class SidecarJob(val photoId: Long, val sidecar: DocumentEntry)

    private class ShotPlan(
        val folder: ListedFolder,
        val byName: Map<String, DocumentEntry>,
        val shot: Shot,
        val sidecar: DocumentEntry?,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun save(
        sourceId: Long,
        folders: List<ListedFolder>,
        onProgress: (IndexProgress) -> Unit,
    ): SaveResult {
        val knownFiles: Map<String, IndexedFile> = photos.indexedFiles(sourceId).associateBy { it.relativePath }
        val knownPhotos: Map<String, IndexedPhoto> =
            photos.indexedPhotos(sourceId).associateBy { key(it.folder, it.stemKey) }
        val knownByPhoto: Map<Long, List<IndexedFile>> = knownFiles.values.groupBy { it.photoId }
        val plans = folders.flatMap { folder ->
            val byName = folder.images.associateBy { it.name }
            ShotPairing.pair(folder.images.map { ListedFile(it.name, it.size, it.modified) }).map { shot ->
                ShotPlan(folder, byName, shot, sidecarFor(shot, folder.sidecars))
            }
        }
        val seenPaths = HashSet<String>(knownFiles.size + plans.size * 2)
        val queue = ArrayList<SidecarJob>()
        var added = 0
        var changed = 0
        val now = clock()
        plans.chunked(BATCH).forEachIndexed { batchIndex, batch ->
            currentCoroutineContext().ensureActive()
            db.withTransaction {
                for (plan in batch) {
                    val folder = plan.folder
                    val members = members(plan.shot, plan.byName)
                    val memberPaths = members.map { join(folder.path, it.first.name) }
                    seenPaths += memberPaths
                    val main = members.first().first
                    val format = format(plan.shot)
                    val stemKey = plan.shot.stem.lowercase()
                    val known = knownPhotos[key(folder.path, stemKey)]
                    if (known == null) {
                        val id = photos.insertPhoto(
                            PhotoEntity(
                                sourceId = sourceId,
                                folder = folder.path,
                                folderDocumentId = folder.documentId,
                                stemKey = stemKey,
                                name = main.name,
                                format = format,
                                hasRaw = plan.shot.raw != null,
                                sortTime = localWallClock(main.modified),
                                addedAt = now,
                            ),
                        )
                        photos.insertVersion(VersionEntity(photoId = id, createdAt = now))
                        photos.insertFiles(
                            members.map { (entry, role) ->
                                fileEntity(id, sourceId, folder.path, entry, role)
                            },
                        )
                        plan.sidecar?.let { queue += SidecarJob(id, it) }
                        added++
                    } else {
                        var dirty = false
                        val fresh = ArrayList<PhotoFileEntity>()
                        for ((entry, role) in members) {
                            val path = join(folder.path, entry.name)
                            val old = knownFiles[path]
                            if (old == null) {
                                fresh += fileEntity(known.id, sourceId, folder.path, entry, role)
                                dirty = true
                            } else if (old.size != entry.size || old.modified != entry.modified ||
                                old.photoId != known.id
                            ) {
                                photos.updateFile(old.id, entry.size, entry.modified, entry.documentId, role.name)
                                dirty = true
                            }
                        }
                        if (fresh.isNotEmpty()) photos.insertFiles(fresh)
                        val removedMember = knownByPhoto[known.id].orEmpty().any { it.relativePath !in memberPaths }
                        if (dirty || removedMember) {
                            photos.updateShot(known.id, main.name, format, plan.shot.raw != null, folder.documentId)
                            changed++
                        }
                        val sidecar = plan.sidecar
                        if (sidecar != null &&
                            (sidecar.modified != known.xmpModified || sidecar.documentId != known.xmpDocumentId)
                        ) {
                            queue += SidecarJob(known.id, sidecar)
                        } else if (sidecar == null && known.xmpDocumentId != null) {
                            photos.setSidecar(known.id, null, null)
                        }
                    }
                }
            }
            onProgress(IndexProgress(IndexPhase.SAVING, minOf((batchIndex + 1) * BATCH, plans.size), plans.size))
        }
        // Files no longer listed: forget them, then every photo left without files.
        val gone = knownFiles.values.filter { it.relativePath !in seenPaths }.map { it.id }
        var removed = 0
        db.withTransaction {
            gone.chunked(SQL_ARGS).forEach { photos.deleteFiles(it) }
            removed = photos.deleteEmptyPhotos(sourceId)
        }
        return SaveResult(added, changed, removed, queue)
    }

    private fun members(shot: Shot, byName: Map<String, DocumentEntry>): List<Pair<DocumentEntry, FileRole>> =
        buildList {
            shot.raw?.let { add(byName.getValue(it.name) to FileRole.RAW) }
            shot.rendered?.let { add(byName.getValue(it.name) to FileRole.RENDERED) }
            shot.others.forEach { add(byName.getValue(it.name) to FileRole.OTHER) }
        }

    private fun fileEntity(photoId: Long, sourceId: Long, folder: String, entry: DocumentEntry, role: FileRole) =
        PhotoFileEntity(
            photoId = photoId,
            sourceId = sourceId,
            relativePath = join(folder, entry.name),
            documentId = entry.documentId,
            name = entry.name,
            size = entry.size,
            modified = entry.modified,
            role = role,
        )

    // ------------------------------------------------------------------ 3. sidecars

    private suspend fun readSidecars(
        tree: DocumentTree,
        jobs: List<SidecarJob>,
        onProgress: (IndexProgress) -> Unit,
    ): Int {
        var done = 0
        jobs.chunked(BATCH).forEach { batch ->
            currentCoroutineContext().ensureActive()
            val results = batch.map { job ->
                job to
                    runCatching {
                        XmpReader.read(tree.readBytes(job.sidecar.documentId, MAX_SIDECAR_BYTES))
                    }.getOrNull()
            }
            db.withTransaction {
                for ((job, fields) in results) {
                    if (fields == null) {
                        // Unreadable: remember it so it is not re-read until it changes; the ratings stay.
                        photos.setSidecar(job.photoId, job.sidecar.documentId, job.sidecar.modified)
                    } else {
                        photos.applyXmp(
                            job.photoId,
                            fields.rating,
                            fields.label,
                            fields.flag,
                            job.sidecar.documentId,
                            job.sidecar.modified,
                        )
                    }
                }
            }
            done += batch.size
            onProgress(IndexProgress(IndexPhase.SIDECARS, done, jobs.size))
        }
        return jobs.size
    }

    // ------------------------------------------------------------------ 4. headers

    /** One photo's header read result. */
    private class Header(val photoId: Long, val metadata: PhotoMetadata?, val embedded: XmpFields?)

    private suspend fun readHeaders(sourceId: Long, tree: DocumentTree, onProgress: (IndexProgress) -> Unit): Int {
        val pending = photos.pendingMetadataFiles(sourceId).groupBy { it.photoId }
        val total = pending.size
        var done = 0
        val gate = Semaphore(HEADER_PARALLELISM)
        pending.entries.chunked(HEADER_BATCH).forEach { batch ->
            currentCoroutineContext().ensureActive()
            val headers = coroutineScope {
                batch.map { (photoId, files) ->
                    async { gate.withPermit { readHeader(photoId, files, tree) } }
                }.awaitAll()
            }
            db.withTransaction {
                for (header in headers) {
                    val m = header.metadata
                    if (m == null) {
                        photos.setMetadata(header.photoId, null, null, null, null, 1, MetadataState.FAILED)
                        continue
                    }
                    photos.setMetadata(
                        id = header.photoId,
                        captureTime = m.captureSortMillis,
                        offset = m.offsetTime?.let { dev.hfps.image.format.ExifTime.offsetMinutes(it) },
                        width = m.width,
                        height = m.height,
                        orientation = m.orientation.coerceIn(1, MAX_ORIENTATION),
                        state = MetadataState.READ,
                    )
                    applyEmbedded(header)
                }
            }
            done += batch.size
            onProgress(IndexProgress(IndexPhase.METADATA, done, total))
        }
        return total
    }

    /**
     * Embedded ratings (JPEG XMP, TIFF Rating) only fill a photo nobody has rated: no sidecar, no stars, no flag,
     * no label. A sidecar or a rating set in Reveal always wins over what is inside the file.
     */
    private suspend fun applyEmbedded(header: Header) {
        val embedded = header.embedded ?: return
        val photo = photos.get(header.photoId) ?: return
        val unrated = photo.rating == 0 && photo.flag == PickFlag.NONE && photo.label == null
        if (photo.xmpDocumentId == null && unrated && embedded != XmpFields()) {
            photos.applyXmp(photo.id, embedded.rating, embedded.label, embedded.flag, null, null)
        }
    }

    /** One file's header: its EXIF essentials and, for a JPEG, the fields of its embedded XMP packet. */
    private fun readFile(file: PhotoFileEntity, tree: DocumentTree): Pair<PhotoMetadata, XmpFields?>? = runCatching {
        tree.openInput(file.documentId, file.name).use { input ->
            val inspection = input.inspection
            val xmp = if (inspection.type.kind == ImageKind.JPEG) runCatching { jpegXmp(input) }.getOrNull() else null
            inspection.metadata to xmp
        }
    }.getOrNull()

    /** The RAW's header first; the rendering fills what it lacks (capture time, size) and gives its embedded XMP. */
    private fun readHeader(photoId: Long, files: List<PhotoFileEntity>, tree: DocumentTree): Header {
        val raw = files.firstOrNull { it.role == FileRole.RAW }?.let { readFile(it, tree) }
        val rawMetadata = raw?.first
        val complete = rawMetadata?.captureTime != null && rawMetadata.width != null
        val rendered = if (complete) {
            null
        } else {
            files.firstOrNull {
                it.role == FileRole.RENDERED
            }?.let { readFile(it, tree) }
        }
        val metadata = listOfNotNull(rawMetadata, rendered?.first).reduceOrNull { acc, next -> acc.orElse(next) }
        val embedded = raw?.second ?: rendered?.second ?: metadata?.rating?.let(::fromRatingTag)
        return Header(photoId, metadata, embedded)
    }

    /** The TIFF/XMP rating as read by `ImageInspector`: -1 = rejected. */
    private fun fromRatingTag(rating: Int): XmpFields = if (rating <
        0
    ) {
        XmpFields(flag = PickFlag.REJECT)
    } else {
        XmpFields(rating = rating.coerceIn(0, XmpFields.MAX_RATING))
    }

    private fun jpegXmp(input: dev.hfps.image.ImageInput): XmpFields? = input.withSource { source ->
        val header = JpegMarkers.read(source, 0) ?: return@withSource null
        if (header.xmpOffset < 0 || header.xmpLength <= 0) return@withSource null
        XmpReader.readOrNull(source.readRange(header.xmpOffset, minOf(header.xmpLength, MAX_SIDECAR_BYTES)))
    }

    companion object {
        private const val XMP_SUFFIX = ".xmp"
        private const val BATCH = 500
        private const val SQL_ARGS = 900
        private const val HEADER_BATCH = 64
        private const val HEADER_PARALLELISM = 4
        private const val MAX_ORIENTATION = 8
        const val MAX_SIDECAR_BYTES = 1024 * 1024

        private fun key(folder: String, stemKey: String) = "$folder\u0000$stemKey"

        fun join(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

        private const val NANOS_PER_MS = 1_000_000

        private fun msSince(start: Long) = (System.nanoTime() - start) / NANOS_PER_MS

        /**
         * The sidecar of a shot: `<stem>.xmp` (Lightroom, Hoard), else `<file name>.xmp` (darktable) of its RAW, then
         * of its rendering. Names compare case-insensitively.
         */
        internal fun sidecarFor(shot: Shot, sidecars: Map<String, DocumentEntry>): DocumentEntry? {
            if (sidecars.isEmpty()) return null
            return sidecars["${shot.stem.lowercase()}$XMP_SUFFIX"]
                ?: shot.raw?.let { sidecars["${it.name.lowercase()}$XMP_SUFFIX"] }
                ?: shot.rendered?.let { sidecars["${it.name.lowercase()}$XMP_SUFFIX"] }
        }

        /** The badge format of a shot. */
        fun format(shot: Shot): PhotoFormat {
            if (shot.raw != null) return if (shot.rendered != null) PhotoFormat.RAW_PAIR else PhotoFormat.RAW
            return when (ImageFormats.classify(shot.rendered?.name.orEmpty()).kind) {
                ImageKind.HEIF -> PhotoFormat.HEIF
                ImageKind.PNG -> PhotoFormat.PNG
                ImageKind.WEBP -> PhotoFormat.WEBP
                else -> PhotoFormat.JPEG
            }
        }

        /**
         * A file mtime (a real instant) as the camera-clock convention of capture times: local wall-clock time
         * written as UTC milliseconds, so photos without EXIF sort and group by day with the others.
         */
        fun localWallClock(epochMillis: Long): Long = epochMillis + TimeZone.getDefault().getOffset(epochMillis)
    }
}
