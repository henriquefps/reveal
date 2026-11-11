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
import dev.hfps.reveal.data.index.IndexProgress
import dev.hfps.reveal.data.index.IndexReport
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.storage.SafDocumentTree
import dev.hfps.reveal.data.storage.StorageVolumes
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
data class ExportTarget(val versionId: Long, val uri: Uri, val name: String, val rating: Int, val renderedUri: Uri?)

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
) {
    private val sourceDao = db.sourceDao()
    private val photoDao = db.photoDao()
    private val indexer = LibraryIndexer(db, clock)
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
            )
        }
    }

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
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "Fingerprints of source ${source.id} stopped", e)
            }
        }
        fingerprintJobs[source.id] = job
        job.invokeOnCompletion { fingerprintJobs.remove(source.id, job) }
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

    /** What the editor opens for [versionId]; also marks the photo as recently opened. */
    suspend fun editTarget(versionId: Long): EditTarget? = withContext(Dispatchers.IO) {
        val photoId = photoDao.photoIdOfVersion(versionId) ?: return@withContext null
        val detail = detail(photoId) ?: return@withContext null
        val main = detail.raw ?: detail.rendered ?: return@withContext null
        photoDao.setOpened(photoId, clock())
        EditTarget(photoId, versionId, main.uri, main.name, detail.rendered?.uri?.takeIf { detail.raw != null })
    }

    /** What an export of [versionId] reads (no side effect, unlike [editTarget]); null when the version is gone. */
    suspend fun exportTarget(versionId: Long): ExportTarget? = withContext(Dispatchers.IO) {
        val photoId = photoDao.photoIdOfVersion(versionId) ?: return@withContext null
        val detail = detail(photoId) ?: return@withContext null
        val main = detail.raw ?: detail.rendered ?: return@withContext null
        val pair = detail.rendered?.uri?.takeIf { detail.raw != null }
        ExportTarget(versionId, main.uri, main.name, detail.photo.rating, pair)
    }

    private fun PhotoFileEntity.detail(tree: DocumentTree) =
        DetailFile(name, size, role, tree.uri(documentId), documentId)

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
