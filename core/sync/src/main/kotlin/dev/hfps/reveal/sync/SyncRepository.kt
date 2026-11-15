package dev.hfps.reveal.sync

import android.util.Log
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.account.ServerAddresses
import dev.hfps.reveal.sync.account.SyncAccount
import dev.hfps.reveal.sync.account.SyncAccountStore
import dev.hfps.reveal.sync.account.SyncOptions
import dev.hfps.reveal.sync.api.DeviceCandidate
import dev.hfps.reveal.sync.api.DeviceEntry
import dev.hfps.reveal.sync.api.DeviceView
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.PairResponse
import dev.hfps.reveal.sync.api.PhysicalDevice
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.engine.ConflictPair
import dev.hfps.reveal.sync.engine.EditPreviews
import dev.hfps.reveal.sync.engine.ServerChangedException
import dev.hfps.reveal.sync.engine.ServerHeaders
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

/**
 * "This device" as the paired server knows it (Hoard ADR 0017): this installation, the phone it is grouped into (with
 * the other apps there, e.g. Hoard) and the phones it could join. [supported] false: the server predates device
 * identity (nothing to show but the device name).
 */
data class ThisDevice(
    val supported: Boolean,
    val deviceId: String,
    val name: String,
    val app: String?,
    val model: String?,
    val features: List<String> = emptyList(),
    val physicalDevice: PhysicalDevice? = null,
    val candidates: List<DeviceCandidate> = emptyList(),
) {
    /** The other apps grouped with this one on the same phone. */
    val others: List<DeviceEntry> get() = physicalDevice?.devices.orEmpty().filter { it.id != deviceId }
}

/** Why a claim failed. */
enum class ClaimProblem { SAME_APP, NOT_FOUND, UNREACHABLE, SERVER_ERROR }

class ClaimException(val problem: ClaimProblem, cause: Throwable? = null) : IOException(problem.name, cause)

/** Why pairing failed. */
enum class PairProblem {
    INVALID_LINK,
    INVALID_TOKEN,
    OTHER_SERVER,
    UNREACHABLE,
    SERVER_ERROR,

    /**
     * Pairing again while paired (ADR 0018 "W5 (g)"): the code's `server=` names another server than the paired one.
     * Nothing was sent; switching servers goes through Unpair first.
     */
    PAIRED_ELSEWHERE,
}

class PairException(val problem: PairProblem, cause: Throwable? = null) : IOException(problem.name, cause)

/**
 * Pairing again while paired (ADR 0018 "W5 (g)"): the code had no `server=` and the server that answered is not the
 * paired one. Nothing local changed; the new pairing waits in memory for [SyncRepository.switchServer] (forget this
 * server, keep the new one) or [SyncRepository.cancelSwitch]. The pairing already registered a device named
 * [deviceName] on [serverName] (`/v1` has no way for a client to remove itself): a cancel leaves it there.
 */
class ServerSwitchException(val serverName: String, val deviceName: String) : IOException("other server")

/** The question before reusing the key at a new address of the paired server (ADR 0018 "W5 (f)"). */
data class KeyReuseQuestion(
    /** The new address's host, as shown to the user. */
    val host: String,
    /** https, or only a private network reaches it ([ServerAddresses.keySafe]): no warning in the question. */
    val protected: Boolean,
)

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
    /** Edited previews for the server's gallery (Hoard ADR 0020); null = never (tests, no renderer). */
    private val editPreviews: EditPreviews? = null,
    /** The library thumbnails (ADR 0018 "W3/C"): forgotten with the server. */
    private val libraryThumbs: dev.hfps.reveal.sync.thumbs.LibraryThumbs? = null,
) {
    /**
     * The device's name, platform and app version, sent when pairing, and how it describes itself to a server with
     * device identity ([device], Hoard ADR 0017).
     */
    data class ClientInfo(
        val deviceName: String,
        val platform: String,
        val appVersion: String,
        val device: ClientDevice? = null,
    )

    private val candidates = MutableStateFlow<List<DeviceCandidate>>(emptyList())

    /**
     * Right after pairing: the phones this one may be (another app paired there with the same model), for the one
     * question of Hoard ADR 0017 ("Is this the same phone as Hoard on Pixel 8?"). Empty when there is nothing to ask.
     */
    val pairCandidates: StateFlow<List<DeviceCandidate>> = candidates.asStateFlow()

    /** The question was answered (or dismissed): not asked again. */
    fun dismissCandidates() = candidates.update { emptyList() }

    private val mutex = Mutex()

    /** A hand-off asked for the culling pull while a run held [mutex]: pulled right after that run. */
    private val cullingWanted = java.util.concurrent.atomic.AtomicBoolean(false)
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

    /**
     * Pairs with the server of [link]; the device key goes to the Keystore. Throws [PairException].
     *
     * Pairing again with the server this installation is paired with (its address changed, ADR 0018 "W5 (f)") keeps
     * everything: when the current key still works at the link's address the device stays the same (no new device on
     * the server, its physical device and Hoard's grouping kept, the pairing token unused); otherwise the new device
     * keeps the library's sync state (records, cursors, culling) and joins the physical device the previous one was in
     * when the server offers it.
     */
    suspend fun pair(link: PairingLink, keyReuse: KeyReuse = KeyReuse.AUTO): SyncAccount = withContext(Dispatchers.IO) {
        pendingSwitch = null
        val current = accounts.account.value
        // Pairing again names the paired server or none: another server goes through Unpair (ADR 0018 "W5 (g)").
        if (current != null && link.serverId != null && link.serverId != current.serverId) {
            throw PairException(PairProblem.PAIRED_ELSEWHERE)
        }
        sameDevice(link, keyReuse)?.let { return@withContext it }
        val response = requestPairing(link)
        if (link.serverId != null && link.serverId != response.serverId) throw PairException(PairProblem.OTHER_SERVER)
        val paired = accounts.account.value
        if (paired != null && paired.serverId != response.serverId) {
            // A code without `server=` for another server: never forget the paired one without asking. Nothing local
            // changes until the user chooses (the new key stays in memory only).
            pendingSwitch = PendingSwitch(link, response, paired)
            throw ServerSwitchException(response.serverName, clientInfo.deviceName)
        }
        val previous = paired
        val previousGroup = previous?.let { db.metaDao().get(SyncEngine.KEY_PHYSICAL_DEVICE) }
        val account = mutex.withLock {
            if (previous == null) {
                // A new pairing starts from nothing: whatever an earlier server knew does not apply.
                forgetLocked()
            } else {
                // The same server under a new device: the library's sync state stays (its revisions are the server's);
                // only what was reported about the old device goes, so the new one declares itself on its next sync.
                engine.forgetDevice()
            }
            saveLocked(link, response)
        }
        // The previous device of this installation was in a physical device (with Hoard): the new one goes back there
        // when the server offers it (the old device is no longer live in it). The user answered that question already.
        val rejoin = previousGroup?.takeIf { group ->
            group != previous.deviceId && response.candidates.any { it.physicalDeviceId == group }
        }
        if (rejoin != null) {
            runCatching { claim(rejoin) }.onFailure { Log.w(TAG, "re-pair: could not rejoin the previous phone") }
        }
        account
    }

    /** `POST /v1/pair` with [link]'s token; failures as [PairException]. */
    private fun requestPairing(link: PairingLink): PairResponse = try {
        newApi(link.url, null).pair(
            PairRequest(
                token = link.token,
                deviceName = clientInfo.deviceName,
                platform = clientInfo.platform,
                appVersion = clientInfo.appVersion,
                app = clientInfo.device?.app,
                manufacturer = clientInfo.device?.manufacturer,
                model = clientInfo.device?.model,
            ),
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

    private suspend fun saveLocked(link: PairingLink, response: PairResponse): SyncAccount {
        val account =
            SyncAccount(
                link.url,
                response.serverId,
                response.serverName,
                response.deviceId,
                System.currentTimeMillis(),
            )
        accounts.save(account, response.apiKey)
        response.physicalDeviceId?.let { db.metaDao().put(MetaEntity(SyncEngine.KEY_PHYSICAL_DEVICE, it)) }
        // The device was reported with the pairing itself: the first sync does not need to send it again unless it
        // also declares culling (the signature includes the features).
        candidates.update { response.candidates.filter(DeviceCandidate::modelMatch) }
        runtime.update { SyncStatus() }
        return account
    }

    /** A pairing with another server waiting for the user's answer ([ServerSwitchException]); memory only. */
    private class PendingSwitch(val link: PairingLink, val response: PairResponse, val from: SyncAccount)

    @Volatile
    private var pendingSwitch: PendingSwitch? = null

    /**
     * "Switch" after a [ServerSwitchException]: Unpair, then keep the pairing just made with the other server (its
     * token is spent, so it is not asked again). Null when there is nothing waiting, or the paired account changed
     * meanwhile (nothing is done then).
     */
    suspend fun switchServer(): SyncAccount? = withContext(Dispatchers.IO) {
        val pending = pendingSwitch ?: return@withContext null
        pendingSwitch = null
        mutex.withLock {
            if (accounts.account.value != pending.from) return@withLock null
            accounts.clear()
            forgetLocked()
            dismissCandidates()
            saveLocked(pending.link, pending.response)
        }
    }

    /** "Cancel" after a [ServerSwitchException]: the paired server stays; the other server's key is dropped. */
    fun cancelSwitch() {
        pendingSwitch = null
    }

    /**
     * The current device again, at [link]'s address, when this installation is paired with that server and its key
     * still works there (`GET /v1/server` answers with the same server and device ids); null otherwise.
     *
     * The key is a secret of the paired server: it is only ever sent to an address the link proves to be that server,
     * i.e. a link whose `server=` (hoard-server prints it in every pairing code) is the account's server id. A link
     * without one, or naming another server, pairs normally and never sees the key (`/v1` has no unauthenticated way to
     * ask an address which server it is: `/v1/health` names none, and `/v1/server` needs a key). Even then it goes on
     * its own only to the host the account already uses (a new port or scheme there); any other host only when the
     * user confirmed it ([keyReuseQuestion], [KeyReuse.CONFIRMED]), and never after a [KeyReuse.DECLINED].
     */
    @Suppress("ReturnCount")
    private suspend fun sameDevice(link: PairingLink, keyReuse: KeyReuse): SyncAccount? {
        val account = accounts.account.value ?: return null
        if (link.serverId == null || link.serverId != account.serverId) return null
        // Another host goes only after the user said it is the same server.
        if (keyReuse == KeyReuse.DECLINED) return null
        if (keyReuse == KeyReuse.AUTO && !sameHost(link, account)) return null
        val key = accounts.apiKey() ?: return null
        val info = try {
            newApi(link.url, key).server()
        } catch (e: SyncHttpException) {
            // A revoked key (`401`) or a server that refuses: pair as a new device.
            Log.i(TAG, "re-pair: the current key is not accepted (${e.code}), pairing a new device")
            return null
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (info.serverId != account.serverId || info.deviceId != account.deviceId) return null
        return mutex.withLock {
            val moved = account.copy(url = link.url)
            accounts.save(moved, key)
            runtime.update { SyncStatus() }
            moved
        }
    }

    /**
     * "Same server at <host>?" before [pair]ing with [link] (ADR 0018 "W5 (f)"), or null when no question is needed:
     * the link is for another server (or names none), there is no key, or the address is on the host the account
     * already uses (a port or scheme change). Any other host asks, whatever its scheme; [KeyReuseQuestion.protected]
     * only changes the wording. The answer is passed to [pair] as [KeyReuse.CONFIRMED] or [KeyReuse.DECLINED].
     */
    suspend fun keyReuseQuestion(link: PairingLink): KeyReuseQuestion? = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        when {
            account == null || link.serverId == null || link.serverId != account.serverId -> null
            sameHost(link, account) || accounts.apiKey() == null -> null
            else -> KeyReuseQuestion(ServerAddresses.host(link.url) ?: link.url, ServerAddresses.keySafe(link.url))
        }
    }

    private fun sameHost(link: PairingLink, account: SyncAccount): Boolean {
        val host = ServerAddresses.host(link.url)
        return link.url.trimEnd('/') == account.url.trimEnd('/') ||
            (host != null && host.equals(ServerAddresses.host(account.url), ignoreCase = true))
    }

    /** Whether [pair] may try this installation's key at a new address of the same server (ADR 0018 "W5 (f)"). */
    enum class KeyReuse {
        /** Only on the host the account already uses (a new port or scheme there). */
        AUTO,

        /** The user confirmed the address is the same server: any address. */
        CONFIRMED,

        /** The user said no: a normal pairing (a new device keeping the library's sync state). */
        DECLINED,
    }

    /** Forgets the server: the key, the cursors, the Server source and the cached server files. Local photos stay. */
    suspend fun unpair() = withContext(Dispatchers.IO) {
        pendingSwitch = null
        mutex.withLock {
            accounts.clear()
            forgetLocked()
            dismissCandidates()
            runtime.update { SyncStatus() }
        }
    }

    private suspend fun forgetLocked() {
        store.forget()
        editPreviews?.forget()
        engine.resetCursors()
        files.clear()
        libraryThumbs?.clear()
    }

    fun setOptions(options: SyncOptions) = accounts.setOptions(options)

    /** "This device" (Settings -> Sync -> This device); null when not paired. Throws [IOException] when offline. */
    suspend fun thisDevice(): ThisDevice? = withContext(Dispatchers.IO) {
        val account = accounts.account.value ?: return@withContext null
        val api = api() ?: return@withContext null
        val info = api.server()
        if (!info.has(SyncEngine.FEATURE_DEVICE_IDENTITY)) {
            val name = runCatching { api.devices().devices.firstOrNull { it.id == account.deviceId }?.name }
                .getOrNull()
            return@withContext ThisDevice(
                supported = false,
                deviceId = account.deviceId,
                name = name ?: clientInfo.deviceName,
                app = null,
                model = null,
            )
        }
        val view = api.device()
        view.physicalDevice?.id?.let { db.metaDao().put(MetaEntity(SyncEngine.KEY_PHYSICAL_DEVICE, it)) }
        thisDevice(view)
    }

    /**
     * Joins this installation to physical device [physicalDeviceId] (another app's phone), or moves it out to one of
     * its own when null (`POST /v1/device/claim`). Throws [ClaimException].
     */
    suspend fun claim(physicalDeviceId: String?): ThisDevice = withContext(Dispatchers.IO) {
        val api = api() ?: throw ClaimException(ClaimProblem.SERVER_ERROR)
        val view = try {
            api.claim(physicalDeviceId)
        } catch (e: SyncHttpException) {
            throw ClaimException(
                when {
                    // `409 conflict`, `details.reason = same_app`: that phone already has a live Reveal of this
                    // variant.
                    e.status == HTTP_CONFLICT -> ClaimProblem.SAME_APP

                    e.status == HTTP_NOT_FOUND -> ClaimProblem.NOT_FOUND

                    else -> ClaimProblem.SERVER_ERROR
                },
                e,
            )
        } catch (e: IOException) {
            throw ClaimException(ClaimProblem.UNREACHABLE, e)
        }
        view.physicalDevice?.id?.let { db.metaDao().put(MetaEntity(SyncEngine.KEY_PHYSICAL_DEVICE, it)) }
        dismissCandidates()
        thisDevice(view)
    }

    private fun thisDevice(view: DeviceView) = ThisDevice(
        supported = true,
        deviceId = view.deviceId,
        name = view.name,
        app = view.app,
        model = listOfNotNull(view.manufacturer, view.model).joinToString(" ").takeIf { it.isNotBlank() },
        features = view.features,
        physicalDevice = view.physicalDevice,
        candidates = view.candidates,
    )

    /**
     * Pulls the culling records and applies them (a hand-off's server references, ADR 0011 "Amendment W3/H"), unless
     * the server does not sync culling. While a sync run is going the pull is left to follow that run (it may have
     * passed its own culling step already): [syncMetadata] does it as soon as the run ends.
     */
    suspend fun pullCulling() = withContext(Dispatchers.IO) {
        val api = api() ?: return@withContext
        if (!mutex.tryLock()) {
            cullingWanted.set(true)
            return@withContext
        }
        try {
            val info = api.server()
            if (info.has("culling")) engine.culling.run(api, info)
        } finally {
            mutex.unlock()
        }
    }

    /** One metadata sync; null when not paired. Problems end up in [status] (and are rethrown when worth a retry). */
    suspend fun syncMetadata(): SyncReport? = withContext(Dispatchers.IO) {
        val account = accounts.account.value ?: return@withContext null
        val api =
            api() ?: return@withContext null.also { runtime.update { it.copy(problem = SyncProblem.UNAUTHORIZED) } }
        try {
            runLocked(account, api)
        } finally {
            if (cullingWanted.getAndSet(false)) {
                try {
                    pullCulling()
                } catch (e: IOException) {
                    Log.i(TAG, "culling pull after the sync failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun runLocked(account: SyncAccount, api: SyncApi): SyncReport? = mutex.withLock {
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
            // Neither of these may fail the sync (they are housekeeping after it): each guards itself.
            val adopted = adoptHandoffs()
            if (adopted > 0) Log.i(TAG, "$adopted hand-offs the server holds are Server photos now")
            val headers = readServerHeaders(api)
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

    /**
     * Blob work: smart previews (when on; on an [unmetered] network also a few made again in the current format),
     * dropping the previews no longer needed and, when [originalsAllowed] (the network suits the Wi-Fi rule), the
     * originals backup. Returns true when more work is left for another run.
     */
    @Suppress("CyclomaticComplexMethod")
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
                // Edited previews follow the originals' network rule (a few MB each, rendered on this device).
                val edits = editPreviews
                if (edits != null && options.editedPreviews && originalsAllowed) {
                    val report = edits.run(api, EDITS_PER_RUN)
                    if (report.progress > 0) Log.i(TAG, "edited previews: $report")
                    progress += report.progress
                    if (report.uploaded >= EDITS_PER_RUN) return@withContext true
                }
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
     * Capture times of the Server source's photos (NAS files, other devices' photos without one), so they sort like
     * local photos (ADR 0018 "Server photos"). A server with items (Hoard ADR 0014) describes them: one lookup per
     * [ITEM_HEADERS_PER_RUN] photos, no file opened (ADR 0018 "W3/C"); an older server's headers are read by range,
     * [HEADERS_PER_RUN] per sync.
     */
    @Suppress("ReturnCount")
    private suspend fun readServerHeaders(api: SyncApi): Int {
        val source = db.syncDao().serverSource() ?: return 0
        val items = runCatching { api.server().has(SyncEngine.FEATURE_ITEMS) }.getOrDefault(false)
        if (items) {
            return runCatching { ServerHeaders.read(db, api, source.id, ITEM_HEADERS_PER_RUN) }
                .onFailure { Log.w(TAG, "server headers: ${it.javaClass.simpleName}") }.getOrDefault(0)
        }
        val factory = trees ?: return 0
        return runCatching {
            LibraryIndexer(db).readServerMetadata(source.id, factory.open(source), HEADERS_PER_RUN)
        }.onFailure { Log.w(TAG, "server headers: ${it.javaClass.simpleName}") }.getOrDefault(0)
    }

    /**
     * Hand-off photos whose file the server holds become Server photos (ADR 0018 "W3/C"), [SyncStore.ADOPT_BATCH] per
     * transaction until none is left: a one-off pass over an existing library (resumable: each batch is committed) and,
     * after every sync's lookups, the new hand-offs whose file the server turned out to hold.
     */
    private suspend fun adoptHandoffs(): Int {
        var total = 0
        try {
            while (true) {
                val batch = store.adoptNext()
                total += batch.moved
                // Failed photos are marked and skipped next time, so the loop ends: nothing tried = nothing left.
                if (batch.moved + batch.failed == 0) return total
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "adopting hand-offs: ${e.javaClass.simpleName}")
            return total
        }
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

        /** Edited previews rendered per blob run (each a develop render at 2560 px). */
        const val EDITS_PER_RUN = 5
        const val HEADERS_PER_RUN = 300
        const val ITEM_HEADERS_PER_RUN = 5_000
        const val HTTP_CONFLICT = 409
        const val HTTP_NOT_FOUND = 404
    }
}
