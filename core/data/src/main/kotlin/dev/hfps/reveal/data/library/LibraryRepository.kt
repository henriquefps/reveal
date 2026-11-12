package dev.hfps.reveal.data.library

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.handoff.HandoffMerge
import dev.hfps.reveal.data.handoff.LocalPreviews
import dev.hfps.reveal.data.handoff.MergeReport
import dev.hfps.reveal.data.index.IndexProgress
import dev.hfps.reveal.data.index.IndexReport
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.storage.SafDocumentTree
import dev.hfps.reveal.data.storage.StorageVolumes
import dev.hfps.reveal.data.storage.isAccessError
import dev.hfps.reveal.data.sync.ServerTree
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the [DocumentTree] of a source (SAF in the app; a fake in tests). */
fun interface DocumentTreeFactory {
    fun open(source: SourceEntity): DocumentTree
}

/** A source as the sources list shows it. */
data class SourceItem(
    val id: Long,
    val name: String,
    val volumeName: String?,
    val removableVolume: Boolean,
    val state: SourceState,
    val writable: Boolean,
    val photoCount: Int,
    /** Non-null while the source is being indexed. */
    val indexing: IndexProgress?,
    /** Sync backs up the originals of this folder's device-only photos (ADR 0018). */
    val backup: Boolean = true,
)

/** A photo's files with their content URIs, the source state, and the EXIF read from the header on demand. */
data class PhotoDetail(
    val photo: PhotoEntity,
    val versionId: Long,
    val sourceState: SourceState,
    val files: List<DetailFile>,
) {
    val raw: DetailFile? get() = files.firstOrNull { it.role == FileRole.RAW }
    val rendered: DetailFile? get() = files.firstOrNull { it.role == FileRole.RENDERED }
}

data class DetailFile(val name: String, val size: Long, val role: FileRole, val uri: Uri, val documentId: String)

/**
 * What an export develops for a version: the RAW of a pair (else the rendering), its name, the photo's rating, and the
 * rendering (its EXIF fills gaps of the RAW's).
 */
data class ExportTarget(
    val versionId: Long,
    val uri: Uri,
    val name: String,
    val rating: Int,
    val renderedUri: Uri?,
    /** Where [uri] reads the original from (ADR 0018 "Export"). */
    val reach: OriginalReach = OriginalReach.DEVICE,
    /** [OriginalReach.PREVIEW] only: the local smart preview to export from while [uri] cannot be read. */
    val previewUri: Uri? = null,
)

/** Where a photo's original can be read now. */
enum class OriginalReach {
    /** A file on this device (a folder, a hand-off). */
    DEVICE,

    /** The sync server serves it ([ExportTarget.uri] reads it by range): download it before decoding. */
    SERVER,

    /** Only another device has it (this one has its smart preview): wait until its backup reaches the server. */
    ELSEWHERE,

    /**
     * A hand-off Reveal can no longer read (ADR 0011 "Unreadable hand-offs"): [ExportTarget.uri] is tried again, else
     * the export reads the local smart preview ([ExportTarget.previewUri]) at its size and says so.
     */
    PREVIEW,
}

/** What the editor opens for a version: the RAW of a pair (else the rendering), and the rendering for the preview. */
data class EditTarget(val photoId: Long, val versionId: Long, val uri: Uri, val name: String, val renderedUri: Uri?)

/** The result of adding a folder. */
sealed interface AddSourceResult {
    data class Added(val sourceId: Long) : AddSourceResult

    data class AlreadyAdded(val sourceId: Long) : AddSourceResult

    data object Unreadable : AddSourceResult
}

/**
 * The library (ADR 0006): sources (SAF trees, persisted grants, volume state), their index, the grid queries, and
 * the rating/flag/remove actions. Indexing runs in [scope] (application lifetime), one job per source.
 */
@Suppress("TooManyFunctions")
class LibraryRepository(
    private val resolver: ContentResolver,
    private val db: RevealDatabase,
    private val volumes: StorageVolumes?,
    private val trees: DocumentTreeFactory,
    private val sidecars: SidecarSync,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The sync provider's authority (`ServerTree.authority`), for smart preview URIs; null without sync. */
    private val serverAuthority: String? = null,
    /** Local previews of hand-offs (ADR 0011 "Unreadable hand-offs"); null in tests without them. */
    private val previews: LocalPreviews? = null,
) {
    private val sourceDao = db.sourceDao()
    private val photoDao = db.photoDao()
    private val indexer = LibraryIndexer(db, clock)
    private val merge = HandoffMerge(db, clock)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val fingerprintJobs = ConcurrentHashMap<Long, Job>()
    private val progress = MutableStateFlow<Map<Long, IndexProgress>>(emptyMap())
    private val reports = MutableStateFlow<Map<Long, IndexReport>>(emptyMap())

    /** Last finished run per source (measurements, tests). */
    val indexReports: StateFlow<Map<Long, IndexReport>> = reports.asStateFlow()

    /** Sources (with counts, state and indexing progress), by name. */
    fun sources(): Flow<List<SourceItem>> = combine(sourceDao.observeWithCounts(), progress) { rows, running ->
        val known = runCatching { volumes?.volumes() }.getOrNull().orEmpty()
        rows.map { (source, count) ->
            SourceItem(
                id = source.id,
                name = source.displayName,
                volumeName = source.volumeName,
                removableVolume = known.firstOrNull { it.id.equals(source.volumeId, true) }?.removable
                    ?: (source.volumeId != null && source.volumeId != StorageVolumes.PRIMARY),
                state = source.state,
                writable = source.writable,
                photoCount = count,
                indexing = running[source.id],
                backup = source.backup,
            )
        }
    }

    /** Hand-off documents Reveal cannot read now (the gallery access offer, ADR 0011). */
    fun unreadableHandoffs(): Flow<List<String>> = photoDao.observeUnreadableHandoffs()

    /** How many photos only the sync server has (the library's Server entry, ADR 0018). */
    fun serverPhotoCount(): Flow<Int> = db.syncDao().observeServerPhotoCount()

    fun photos(query: PhotoQuery): Flow<List<PhotoRow>> = photoDao.observeRows(PhotoQuerySql.toQuery(query))

    suspend fun photoList(query: PhotoQuery): List<PhotoRow> = photoDao.rows(PhotoQuerySql.toQuery(query))

    // ------------------------------------------------------------------ sources

    /**
     * Adds the folder of an `ACTION_OPEN_DOCUMENT_TREE` result: persists the grant (read + write when offered, so
     * sidecars can be written), records the volume, then indexes it in the background. Nothing is copied.
     */
    suspend fun addSource(treeUri: Uri): AddSourceResult = withContext(Dispatchers.IO) {
        sourceDao.byTree(treeUri.toString())?.let { return@withContext AddSourceResult.AlreadyAdded(it.id) }
        val writeGranted = persist(treeUri)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext AddSourceResult.Unreadable
        val tree = SafDocumentTree(resolver, treeUri, rootId)
        val root = runCatching { tree.stat(rootId) }.getOrNull() ?: return@withContext AddSourceResult.Unreadable
        val volumeId = StorageVolumes.volumeIdOf(treeUri)
        val volume = volumeId?.let { runCatching { volumes?.volume(it) }.getOrNull() }
        val id = sourceDao.insert(
            SourceEntity(
                treeUri = treeUri.toString(),
                rootDocumentId = rootId,
                displayName = root.name.ifBlank { rootId.substringAfterLast(':').substringAfterLast('/') },
                volumeId = volumeId,
                volumeName = volume?.description,
                state = SourceState.AVAILABLE,
                writable = writeGranted && (root.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0,
                addedAt = clock(),
            ),
        )
        index(id)
        AddSourceResult.Added(id)
    }

    /** Takes the persistable grant; true when it includes write. A provider without persistable grants is fine. */
    private fun persist(treeUri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val readWrite = runCatching { resolver.takePersistableUriPermission(treeUri, rw) }.isSuccess
        if (!readWrite) {
            runCatching { resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val persisted = resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        // No persisted row at all: a provider of our own process (debug test provider) that needs no grant.
        return persisted?.isWritePermission ?: true
    }

    /** Forgets a folder: its index, edits and the grant. The files are never touched. */
    suspend fun removeSource(sourceId: Long) = withContext(Dispatchers.IO) {
        jobs.remove(sourceId)?.cancel()
        val source = sourceDao.get(sourceId) ?: return@withContext
        runCatching {
            resolver.releasePersistableUriPermission(
                source.treeUri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        sourceDao.delete(sourceId)
    }

    /**
     * Re-checks every source (volume mounted, grant present, folder listable) and indexes the ones that are
     * available: incremental, so an unchanged folder costs one listing. Call on start and on volume changes.
     */
    suspend fun refresh(reindex: Boolean = true) = withContext(Dispatchers.IO) {
        for (source in sourceDao.all()) {
            val state = probe(source)
            val volumeName = source.volumeId?.let { id -> runCatching { volumes?.volume(id)?.description }.getOrNull() }
            if (state != source.state || (volumeName != null && volumeName != source.volumeName)) {
                sourceDao.setState(source.id, state, volumeName)
            }
            if (reindex && state == SourceState.AVAILABLE) index(source.id)
        }
        checkHandoffs()
    }

    /**
     * Which hand-off documents Reveal can still read (ADR 0011 "Unreadable hand-offs"): a temporary grant ends with
     * the process, a gallery permission given or taken changes it. Marks and clears `unreadable_at`; returns how many
     * cannot be read. Opens each document once (no read).
     */
    suspend fun checkHandoffs(): Int = withContext(Dispatchers.IO) {
        val source = sourceDao.handoff() ?: return@withContext 0
        val tree = trees.open(source)
        var unreadable = 0
        for (file in photoDao.handoffFiles()) {
            val readable = try {
                tree.openInput(file.documentId, file.name).close()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                !isAccessError(e)
            }
            if (readable) {
                if (file.unreadableAt != null) photoDao.setReadable(file.documentId)
            } else {
                unreadable++
                photoDao.setUnreadable(file.documentId, clock())
            }
        }
        unreadable
    }

    /** The source's state now. Never throws. */
    fun probe(source: SourceEntity): SourceState {
        val volumeId = source.volumeId
        val known = volumes
        if (volumeId != null && known != null) {
            val mounted = runCatching { known.volume(volumeId)?.mounted }.getOrNull()
            if (mounted != true) return SourceState.UNPLUGGED
        }
        return try {
            if (trees.open(source).stat(source.rootDocumentId) != null) SourceState.AVAILABLE else SourceState.UNPLUGGED
        } catch (e: SecurityException) {
            Log.w(TAG, "Grant lost for source ${source.id}", e)
            SourceState.PERMISSION_LOST
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Source ${source.id} unreachable", e)
            SourceState.UNPLUGGED
        }
    }

    /** Starts indexing [sourceId] unless a run is already going. */
    fun index(sourceId: Long): Job {
        jobs[sourceId]?.takeIf { it.isActive }?.let { return it }
        fingerprintJobs.remove(sourceId)?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            val source = sourceDao.get(sourceId) ?: return@launch
            progress.update { it + (sourceId to IndexProgress(dev.hfps.reveal.data.index.IndexPhase.LISTING, 0, 0)) }
            try {
                val report = indexer.index(sourceId, trees.open(source)) { p ->
                    progress.update { it + (sourceId to p) }
                }
                sourceDao.setIndexed(sourceId, clock())
                reports.update { it + (sourceId to report) }
                Log.i(TAG, "Indexed source $sourceId: $report")
                fingerprint(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                sourceDao.setState(sourceId, SourceState.PERMISSION_LOST, null)
                Log.w(TAG, "Indexing source $sourceId: grant lost", e)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                sourceDao.setState(
                    sourceId,
                    probe(source).takeIf { it != SourceState.AVAILABLE } ?: SourceState.UNPLUGGED,
                    null,
                )
                Log.w(TAG, "Indexing source $sourceId failed", e)
            } finally {
                progress.update { it - sourceId }
            }
        }
        jobs[sourceId] = job
        job.invokeOnCompletion { jobs.remove(sourceId, job) }
        return job
    }

    /**
     * Fills the content fingerprints of [source]'s new or changed files (ADR 0006 "Identity") after a walk, in its own
     * job so the library is "indexed" (and a rescan can start) without waiting for it; a new walk cancels it.
     */
    private fun fingerprint(source: SourceEntity) {
        fingerprintJobs.remove(source.id)?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            try {
                val stored = indexer.fillFingerprints(source.id, trees.open(source))
                if (stored > 0) Log.i(TAG, "Fingerprinted $stored files of source ${source.id}")
                mergeHandoffs(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "Fingerprints of source ${source.id} stopped", e)
            }
        }
        fingerprintJobs[source.id] = job
        job.invokeOnCompletion { fingerprintJobs.remove(source.id, job) }
    }

    /**
     * Makes each hand-off photo whose file this folder holds the folder's photo (ADR 0011 "One photo across sources");
     * runs after every fingerprint pass, so rows printed before this existed are merged on the first walk too.
     */
    suspend fun mergeHandoffs(sourceId: Long): MergeReport = withContext(Dispatchers.IO) {
        val report = merge.mergeInto(sourceId)
        if (report.merged > 0) {
            Log.i(TAG, "Merged ${report.merged} hand-off photos into source $sourceId")
            if (report.cullingChanged.isNotEmpty()) sidecars.write(report.cullingChanged)
            previews?.delete(report.fingerprints)
        }
        report
    }

    /** What the library showed last (an opaque string of the UI), so a fresh start reopens it. */
    suspend fun lastSelection(): String? = withContext(Dispatchers.IO) { db.metaDao().get(KEY_LAST_SELECTION) }

    suspend fun setLastSelection(value: String?) = withContext(Dispatchers.IO) {
        db.metaDao().put(MetaEntity(KEY_LAST_SELECTION, value.orEmpty()))
    }

    // ------------------------------------------------------------------ photos

    suspend fun setRating(photoIds: List<Long>, rating: Int): SidecarOutcome = withContext(Dispatchers.IO) {
        photoIds.chunked(SQL_ARGS).forEach { photoDao.setRating(it, rating.coerceIn(0, MAX_RATING)) }
        sidecars.write(photoIds)
    }

    suspend fun setFlag(photoIds: List<Long>, flag: PickFlag): SidecarOutcome = withContext(Dispatchers.IO) {
        photoIds.chunked(SQL_ARGS).forEach { photoDao.setFlag(it, flag) }
        sidecars.write(photoIds)
    }

    /** The per-folder switch of the originals backup (ADR 0018). */
    suspend fun setBackup(sourceId: Long, backup: Boolean) = withContext(Dispatchers.IO) {
        sourceDao.setBackup(sourceId, backup)
    }

    /** "Remove from library": hidden from every grid; files untouched. */
    suspend fun removeFromLibrary(photoIds: List<Long>) = withContext(Dispatchers.IO) {
        photoIds.chunked(SQL_ARGS).forEach { photoDao.setHidden(it, true) }
    }

    fun observePhoto(photoId: Long): Flow<PhotoEntity?> = photoDao.observe(photoId)

    suspend fun detail(photoId: Long): PhotoDetail? = withContext(Dispatchers.IO) {
        val photo = photoDao.get(photoId) ?: return@withContext null
        val source = sourceDao.get(photo.sourceId) ?: return@withContext null
        val version = photoDao.firstVersion(photoId) ?: return@withContext null
        val tree = trees.open(source)
        PhotoDetail(
            photo = photo,
            versionId = version,
            sourceState = source.state,
            files = photoDao.files(photoId).map { it.detail(tree) },
        )
    }

    /** EXIF of a photo (RAW header first, gaps from its rendering), read now from the files. */
    suspend fun metadata(detail: PhotoDetail): PhotoMetadata? = withContext(Dispatchers.IO) {
        val source = sourceDao.get(detail.photo.sourceId) ?: return@withContext null
        val tree = trees.open(source)
        val files = listOfNotNull(detail.raw, detail.rendered)
        files.mapNotNull { file ->
            runCatching { tree.openInput(file.documentId, file.name).use { it.inspection.metadata } }.getOrNull()
        }.reduceOrNull { acc, next -> acc.orElse(next) }
    }

    /**
     * What the editor opens for [versionId]; also marks the photo as recently opened. When the original is out of reach
     * (a server photo the server cannot serve, an unplugged folder) and the photo has a smart preview, the editor opens
     * that instead (ADR 0018 "Smart previews").
     */
    suspend fun editTarget(versionId: Long): EditTarget? = withContext(Dispatchers.IO) {
        val photoId = photoDao.photoIdOfVersion(versionId) ?: return@withContext null
        val detail = detail(photoId) ?: return@withContext null
        val main = detail.raw ?: detail.rendered ?: return@withContext null
        photoDao.setOpened(photoId, clock())
        localPreview(detail, main)?.let { local ->
            return@withContext EditTarget(photoId, versionId, local, main.name, null)
        }
        smartPreviewUri(detail)?.let { smart ->
            return@withContext EditTarget(photoId, versionId, smart, main.name, null)
        }
        EditTarget(photoId, versionId, main.uri, main.name, detail.rendered?.uri?.takeIf { detail.raw != null })
    }

    /**
     * The local smart preview of a hand-off Reveal cannot read now (ADR 0011 "Unreadable hand-offs"), or null (it can,
     * or there is none: the editor then reports the original's error).
     */
    @Suppress("ReturnCount")
    private suspend fun localPreview(detail: PhotoDetail, main: DetailFile): Uri? {
        val local = previews ?: return null
        val source = sourceDao.get(detail.photo.sourceId) ?: return null
        if (source.kind != SourceKind.HANDOFF || readable(source, main)) return null
        val fingerprint = photoDao.files(detail.photo.id).firstOrNull { it.documentId == main.documentId }?.fingerprint
        return local.smart(fingerprint)?.let(Uri::fromFile)
    }

    /** Opens [file] once; false (and marked) when access is refused or it is gone. */
    private suspend fun readable(source: SourceEntity, file: DetailFile): Boolean {
        val ok = try {
            trees.open(source).openInput(file.documentId, file.name).close()
            true
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            !isAccessError(e)
        }
        if (ok) photoDao.setReadable(file.documentId) else photoDao.setUnreadable(file.documentId, clock())
        return ok
    }

    /** The smart preview of a photo whose original cannot be read now, or null. */
    @Suppress("ReturnCount")
    private suspend fun smartPreviewUri(detail: PhotoDetail): Uri? {
        val authority = serverAuthority ?: return null
        val cloud = db.syncDao().cloud(detail.photo.id) ?: return null
        if (cloud.previewSha == null) return null
        val source = sourceDao.get(detail.photo.sourceId) ?: return null
        val reachable = when (source.kind) {
            SourceKind.SERVER -> cloud.originalOnServer
            SourceKind.FOLDER -> source.state == SourceState.AVAILABLE
            SourceKind.HANDOFF -> true
        }
        if (reachable) return null
        val main = photoDao.files(detail.photo.id).let { files ->
            files.firstOrNull { it.role == FileRole.RAW } ?: files.firstOrNull { it.role == FileRole.RENDERED }
        } ?: return null
        val fingerprint = main.fingerprint ?: return null
        return ServerTree.smartUri(authority, fingerprint, main.name)
    }

    /** What an export of [versionId] reads (no side effect, unlike [editTarget]); null when the version is gone. */
    @Suppress("CyclomaticComplexMethod") // one branch per place an original can be read from (ADR 0011, 0018)
    suspend fun exportTarget(versionId: Long): ExportTarget? = withContext(Dispatchers.IO) {
        val photoId = photoDao.photoIdOfVersion(versionId) ?: return@withContext null
        val detail = detail(photoId) ?: return@withContext null
        val main = detail.raw ?: detail.rendered ?: return@withContext null
        val pair = detail.rendered?.uri?.takeIf { detail.raw != null }
        val target = ExportTarget(versionId, main.uri, main.name, detail.photo.rating, pair)
        val source = sourceDao.get(detail.photo.sourceId) ?: return@withContext target
        val onServer = db.syncDao().cloud(photoId)?.originalOnServer == true
        localPreview(detail, main)?.let { local ->
            return@withContext target.copy(renderedUri = null, reach = OriginalReach.PREVIEW, previewUri = local)
        }
        when {
            source.kind == SourceKind.SERVER ->
                target.copy(renderedUri = null, reach = if (onServer) OriginalReach.SERVER else OriginalReach.ELSEWHERE)

            source.kind == SourceKind.FOLDER && source.state != SourceState.AVAILABLE && onServer -> {
                // An unplugged folder whose original the server holds (a backup): export from the server copy.
                val fingerprint = photoDao.files(photoId).firstOrNull { it.name == main.name }?.fingerprint
                val authority = serverAuthority
                if (fingerprint == null || authority == null) {
                    target
                } else {
                    val uri = ServerTree.uri(
                        authority,
                        ServerTree.ORIGINAL,
                        ServerTree.documentId(fingerprint, main.name),
                    )
                    target.copy(uri = uri, renderedUri = null, reach = OriginalReach.SERVER)
                }
            }

            else -> target
        }
    }

    private fun PhotoFileEntity.detail(tree: DocumentTree) =
        DetailFile(name, size, role, tree.uri(documentId), documentId)

    /**
     * How many of [versionIds] belong to hand-offs Reveal can no longer read (ADR 0011 "Unreadable hand-offs"): an
     * export of those reads their local preview.
     */
    suspend fun unreadableCount(versionIds: List<Long>): Int = withContext(Dispatchers.IO) {
        versionIds.chunked(SQL_ARGS).sumOf { photoDao.unreadableVersions(it) }
    }

    /** Opens a source's tree (the thumbnail loader and the viewer read files through it). */
    suspend fun tree(sourceId: Long): DocumentTree = withContext(Dispatchers.IO) {
        trees.open(sourceDao.get(sourceId) ?: throw IOException("No source $sourceId"))
    }

    companion object {
        private const val TAG = "RevealLibrary"
        private const val KEY_LAST_SELECTION = "library.lastSelection"
        private const val SQL_ARGS = 900
        private const val MAX_RATING = 5
    }
}
