package dev.hfps.reveal.sync

import android.util.Log
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.account.SyncAccount
import dev.hfps.reveal.sync.account.SyncAccountStore
import dev.hfps.reveal.sync.account.SyncOptions
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.engine.ConflictPair
import dev.hfps.reveal.sync.engine.ServerChangedException
import dev.hfps.reveal.sync.engine.ServerLibrary
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncReport
import dev.hfps.reveal.sync.engine.SyncStore
import dev.hfps.reveal.sync.files.ServerFiles
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why the last sync did not finish; each one has its own text on the Sync screen. */
enum class SyncProblem {
    /** No network, or the server did not answer. */
    OFFLINE,

    /** The device key was revoked (or the server was reset): pair again. */
    UNAUTHORIZED,

    /** The paired URL now answers as another server. */
    SERVER_CHANGED,

    /** The server has no backup folder yet: previews and originals wait. */
    STORAGE_NOT_CONFIGURED,

    /** Anything else the server answered with an error. */
    SERVER_ERROR,
}

/** What the Sync screen shows. */
data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    val problem: SyncProblem? = null,
    val pending: Int = 0,
    val uploadsPending: Int = 0,
    val uploadBytesPending: Long = 0,
    val uploadedBytes: Long = 0,
    val serverPhotos: Int = 0,
)

/** Why pairing failed. */
enum class PairProblem { INVALID_LINK, INVALID_TOKEN, OTHER_SERVER, UNREACHABLE, SERVER_ERROR }

class PairException(val problem: PairProblem, cause: Throwable? = null) : IOException(problem.name, cause)

/**
 * The app's sync (ADR 0018): pairing, the options, the status, and one sync run (metadata, then blobs) for the
 * workers. A single instance (Hilt singleton); runs never overlap.
 */
@Suppress("LongParameterList")
class SyncRepository(
    private val db: RevealDatabase,
    private val accounts: SyncAccountStore,
    private val store: SyncStore,
    private val engine: SyncEngine,
    private val uploader: BlobUploader,
    private val files: ServerFiles,
    private val clientInfo: ClientInfo,
    /** The library's trees: the Server source's headers are read through the server-files provider. */
    private val trees: DocumentTreeFactory? = null,
    private val library: ServerLibrary = ServerLibrary(db, store),
    private val newApi: (url: String, key: String?) -> SyncApi = { url, key -> SyncApi(url, key) },
) {
    /** The device's name, platform and app version, sent when pairing. */
    data class ClientInfo(val deviceName: String, val platform: String, val appVersion: String)

    private val mutex = Mutex()
    private val runtime = MutableStateFlow(SyncStatus())

    val account: StateFlow<SyncAccount?> = accounts.account
    val options: StateFlow<SyncOptions> = accounts.options

    /** The live status: the last run's outcome with the database's counts. */
    val status: Flow<SyncStatus> =
        combine(runtime, db.syncDao().observeCounts(), db.syncDao().observeServerPhotoCount()) {
                state,
                counts,
                server,
            ->
            state.copy(
                pending = counts.pending,
                uploadsPending = counts.uploadsPending,
                uploadBytesPending = counts.uploadBytesPending,
                uploadedBytes = counts.uploadedBytes,
                serverPhotos = server,
            )
        }

    /** The paired server's client, or null. */
    @Suppress("ReturnCount")
    fun api(): SyncApi? {
        val account = accounts.account.value ?: return null
        val key = accounts.apiKey() ?: return null
        return newApi(account.url, key)
    }

    /** Pairs with the server of [link]; the device key goes to the Keystore. Throws [PairException]. */
    suspend fun pair(link: PairingLink): SyncAccount = withContext(Dispatchers.IO) {
        val response = try {
            newApi(link.url, null).pair(
                PairRequest(link.token, clientInfo.deviceName, clientInfo.platform, clientInfo.appVersion),
            )
        } catch (e: SyncHttpException) {
            throw PairException(
                if (e.code == "invalid_pairing_token") PairProblem.INVALID_TOKEN else PairProblem.SERVER_ERROR,
                e,
            )
        } catch (e: IOException) {
            throw PairException(PairProblem.UNREACHABLE, e)
        } catch (e: IllegalArgumentException) {
            throw PairException(PairProblem.INVALID_LINK, e)
        }
        if (link.serverId != null && link.serverId != response.serverId) throw PairException(PairProblem.OTHER_SERVER)
        mutex.withLock {
            // A new pairing starts from nothing: whatever an earlier server knew does not apply.
            forgetLocked()
            val account =
                SyncAccount(
                    link.url,
                    response.serverId,
                    response.serverName,
                    response.deviceId,
                    System.currentTimeMillis(),
                )
            accounts.save(account, response.apiKey)
            runtime.update { SyncStatus() }
            account
        }
    }

    /** Forgets the server: the key, the cursors, the Server source and the cached server files. Local photos stay. */
    suspend fun unpair() = withContext(Dispatchers.IO) {
        mutex.withLock {
            accounts.clear()
            forgetLocked()
            runtime.update { SyncStatus() }
        }
    }

    private suspend fun forgetLocked() {
        store.forget()
        engine.resetCursors()
        files.clear()
    }

    fun setOptions(options: SyncOptions) = accounts.setOptions(options)

    /** One metadata sync; null when not paired. Problems end up in [status] (and are rethrown when worth a retry). */
    suspend fun syncMetadata(): SyncReport? = withContext(Dispatchers.IO) {
        val account = accounts.account.value ?: return@withContext null
        val api =
            api() ?: return@withContext null.also { runtime.update { it.copy(problem = SyncProblem.UNAUTHORIZED) } }
        mutex.withLock {
            // Unpaired (or paired again) while this run waited: its key and server are gone, do nothing.
            if (accounts.account.value != account) return@withLock null
            runtime.update { it.copy(running = true) }
            try {
                // Previews no longer needed stop being named before the push, so the blob run can drop them.
                uploader.markDrops()
                val report = engine.sync(api, account.serverId)
                // After the push: the server keeps an original while a live record names it.
                val deleted = uploader.deleteOriginals(api)
                if (deleted > 0) Log.i(TAG, "deleted $deleted originals from the server")
                val headers = readServerHeaders()
                Log.i(TAG, "sync: $report, $headers server headers")
                runtime.update { it.copy(running = false, problem = null, lastSyncAt = engine.lastSync()) }
                report
            } catch (@Suppress("SwallowedException") e: ServerChangedException) {
                Log.w(TAG, "The paired address answers as another server")
                runtime.update { it.copy(running = false, problem = SyncProblem.SERVER_CHANGED) }
                null
            } catch (e: SyncHttpException) {
                runtime.update { it.copy(running = false, problem = problemOf(e)) }
                if (e.transient) throw e
                null
            } catch (e: IOException) {
                runtime.update { it.copy(running = false, problem = SyncProblem.OFFLINE) }
                throw e
            } finally {
                runtime.update { it.copy(running = false) }
            }
        }
    }

    /**
     * Blob work: smart previews (when on; on an [unmetered] network also a few made again in the current format),
     * dropping the previews no longer needed and, when [originalsAllowed] (the network suits the Wi-Fi rule), the
     * originals backup. Returns true when more work is left for another run.
     */
    suspend fun syncBlobs(originalsAllowed: Boolean, unmetered: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val api = api() ?: return@withContext false
            val options = accounts.options.value
            try {
                var progress = 0
                if (options.previews) {
                    val reformat = if (unmetered) REFORMATS_PER_RUN else 0
                    progress +=
                        uploader.previews(api, PREVIEWS_PER_RUN, originalsOff = !options.originals, reformat = reformat)
                }
                progress += uploader.drops(api)
                if (options.originals && originalsAllowed) progress += uploader.originals(api, ORIGINALS_PER_RUN)
                val left = blobWorkLeft(options, originalsAllowed)
                // A run that moved nothing forward (every item unreadable or failing) does not start the next one at
                // once: the next metadata sync or the periodic job tries again.
                left && progress > 0
            } catch (e: SyncHttpException) {
                runtime.update { it.copy(problem = problemOf(e)) }
                if (e.code == "storage_not_configured" || !e.transient) false else throw e
            }
        }

    /** True when a blob run would find something to do (previews or originals; queued drops wait for a push). */
    private suspend fun blobWorkLeft(options: SyncOptions, originalsAllowed: Boolean): Boolean {
        val dao = db.syncDao()
        return dao.pendingUploads(dev.hfps.reveal.data.db.SyncUploadEntity.KIND_PREVIEW, 1).isNotEmpty() ||
            (options.previews && dao.previewCandidates(!options.originals, 1).isNotEmpty()) ||
            (options.originals && originalsAllowed && dao.backupCandidates(1).isNotEmpty())
    }

    /**
     * Capture times of the Server source's photos (NAS files, other devices' photos without one): their headers read by
     * range, [HEADERS_PER_RUN] per sync, so they sort like local photos (ADR 0018 "Server photos").
     */
    @Suppress("ReturnCount")
    private suspend fun readServerHeaders(): Int {
        val factory = trees ?: return 0
        val source = db.syncDao().serverSource() ?: return 0
        return runCatching {
            LibraryIndexer(db).readServerMetadata(source.id, factory.open(source), HEADERS_PER_RUN)
        }.onFailure { Log.w(TAG, "server headers: ${it.javaClass.simpleName}") }.getOrDefault(0)
    }

    /** How many photos of folder [sourceId] have an edit (the removal dialog's default for "Also delete"). */
    suspend fun editedInFolder(sourceId: Long): Int = withContext(Dispatchers.IO) { library.editedCount(sourceId) }

    /** True while paired with a server (the removal dialog asks about the server only then). */
    val paired: Boolean get() = accounts.account.value != null

    /**
     * Prepares the removal of folder [sourceId] for the server's view (ADR 0018 "Deleting from the server"); call it
     * before the library forgets the folder. Nothing when not paired.
     */
    suspend fun removeFolder(sourceId: Long, deleteFromServer: Boolean) = withContext(Dispatchers.IO) {
        if (accounts.account.value == null) return@withContext null
        mutex.withLock { library.removeFolder(sourceId, deleteFromServer) }
    }

    /** "Delete from server" for Server-scope photos: records, edits and uploaded originals, on every device. */
    suspend fun deleteFromServer(photoIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (accounts.account.value == null) return@withContext 0
        mutex.withLock { library.deleteFromServer(photoIds) }
    }

    /** Deletes a version (a conflict copy) here and on the other devices. */
    suspend fun deleteVersion(versionId: Long) = withContext(Dispatchers.IO) { store.deleteVersion(versionId) }

    /** The conflict [versionId] belongs to (a copy, or the version it copies), or null (ADR 0018 FIX/W). */
    suspend fun conflict(versionId: Long): ConflictPair? = withContext(Dispatchers.IO) { store.conflict(versionId) }

    /** The conflict of [photoId] (its first conflict copy), or null (the viewer's "Resolve conflict"). */
    suspend fun conflictOfPhoto(photoId: Long): ConflictPair? =
        withContext(Dispatchers.IO) { store.conflictOfPhoto(photoId) }

    /** "Keep this one": the other side goes here and, through sync, on every device. */
    suspend fun keep(pair: ConflictPair, keepCopy: Boolean) = withContext(Dispatchers.IO) {
        store.keep(pair, keepCopy)
    }

    private fun problemOf(e: SyncHttpException): SyncProblem = when {
        e.unauthorized -> SyncProblem.UNAUTHORIZED
        e.code == "storage_not_configured" -> SyncProblem.STORAGE_NOT_CONFIGURED
        else -> SyncProblem.SERVER_ERROR
    }

    private companion object {
        const val TAG = "RevealSync"
        const val PREVIEWS_PER_RUN = 20

        /** Previews made again in the current format per blob run (unmetered network only; never re-schedules). */
        const val REFORMATS_PER_RUN = 3
        const val ORIGINALS_PER_RUN = 10
        const val HEADERS_PER_RUN = 300
    }
}
