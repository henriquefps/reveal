package dev.hfps.reveal.data.handoff

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ImageKind
import dev.hfps.image.format.ListedFile
import dev.hfps.image.format.ShotPairing
import dev.hfps.reveal.data.db.FingerprintMatch
import dev.hfps.reveal.data.db.PhotoDao
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.SidecarImport
import dev.hfps.reveal.data.model.CullingField
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.storage.fingerprint
import dev.hfps.reveal.data.xmp.SidecarCulling
import dev.hfps.reveal.data.xmp.SidecarNames
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the UI does after a hand-off (ADR 0011). */
sealed interface HandoffOutcome {
    /**
     * Open [versionId] in the editor. [closeOnBack]: it is the only photo, so back returns to the calling app.
     * [libraryBehind]: it was picked among several, so "Opened from other apps" sits behind the editor.
     */
    data class OpenEditor(
        val versionId: Long,
        val closeOnBack: Boolean,
        val libraryBehind: Boolean,
        /** Server items that do not exist on the server (skipped; ADR 0011 "Amendment W3/H"). */
        val notFound: Int = 0,
        val issue: ServerIssue? = null,
    ) : HandoffOutcome

    /** Several shots: show them in "Opened from other apps". */
    data class OpenLibrary(val photoIds: List<Long>, val notFound: Int = 0, val issue: ServerIssue? = null) :
        HandoffOutcome

    /** Only server references and none opened: [issue] (pair, another server, unreachable) and/or [notFound]. */
    data class ServerRefs(val issue: ServerIssue?, val notFound: Int = 0) : HandoffOutcome

    /** One document that is not a recognised image name: the editor opens it by URI and reports what is wrong. */
    data class OpenUri(val uri: Uri) : HandoffOutcome

    /** Nothing usable in the intent (no readable image). */
    data object Nothing : HandoffOutcome
}

/**
 * Receives the photos other apps hand to Reveal (ADR 0011) and files them in the "Opened from other apps" source: a
 * normal source row whose [HandoffTree] keys documents by content URI, so everything downstream (thumbnails, viewer,
 * editor, XMP) is shared with added folders. Pairing is by lower-case stem inside the same parent (as `ShotPairing`),
 * among the files of one request; a photo that was handed off before is found by file identity (its URI, size and
 * mtime), never by name, so re-sending the same file updates it and keeps its edits and a different file with the
 * same name is a new photo.
 */
// One request's whole path (resolve, pair, file, culling, copies, previews, retention) lives here (ADR 0011).
@Suppress("LargeClass")
class HandoffRepository(
    private val resolver: ContentResolver,
    private val db: RevealDatabase,
    private val indexer: LibraryIndexer,
    private val tree: DocumentTree = HandoffTree(resolver),
    /** Keeps a local preview of each hand-off while its grant is alive (ADR 0011 "Unreadable hand-offs"). */
    private val previewer: HandoffPreviewer? = null,
    /** Keeps the originals the sender's grant covers only while it lives (MediaStore Downloads, W4); null in tests. */
    private val originals: LocalOriginals? = null,
    /** The paired server, to resolve server references (ADR 0011 "Amendment W3/H"); never paired by default. */
    private val server: ServerItems = ServerItems.None,
    /**
     * Where the rest of a request's work runs after its outcome is known (B8: the copies after the initial photo's, the
     * previews): the app's scope; null = inline, before [receiveStaged] returns (tests).
     */
    private val background: CoroutineScope? = null,
    /** The photo permission is FULL: `MediaStore.Images` documents stay readable after the grant (W4). */
    private val mediaReadable: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val photos = db.photoDao()
    private val refs = ServerRefResolver(db, server, clock)

    /** One resolved document of the request; [fingerprint] is its content fingerprint (null: unreadable). */
    private class Item(val uri: Uri, val entry: DocumentEntry) {
        val name: String get() = entry.name
        val parent: String get() = parentKey(uri, entry.relativePath)
        var fingerprint: String? = null
    }

    /** A paired shot ready to store: files with roles, and the sidecar item if the sender gave one. */
    private class Shot(val folder: String, val stem: String, val main: Item, val files: List<Pair<Item, FileRole>>) {
        var sidecar: Item? = null
    }

    /** What a request did once everything that needs the sender's grant is done ([Received.settled]). */
    data class Settled(
        /** Originals that were not kept for lack of space ("not enough space": they show their preview only). */
        val noSpace: Int = 0,
    )

    /**
     * A request's [outcome] (known once the photo to open first is in: its rows, and its local copy of a Downloads
     * original) and [settled], completed when the rest is done: the other copies and the display previews of the
     * shots whose documents only the sender's grant covers. The activity that holds the grant waits for [settled]
     * before it finishes (a silent batch member, ADR 0011 "Amendment W4").
     */
    class Received(val outcome: HandoffOutcome, val settled: Deferred<Settled>)

    /** [receiveStaged], waiting for everything ([Received.settled]) before it returns the outcome. */
    suspend fun receive(request: HandoffRequest): HandoffOutcome =
        receiveStaged(request).also { it.settled.await() }.outcome

    /**
     * Receives [request] (ADR 0011). [holderAlive]: whether the activity that holds the intent's grant still lives
     * (smart previews of documents only that grant covers are made only while it does).
     */
    suspend fun receiveStaged(request: HandoffRequest, holderAlive: () -> Boolean = { true }): Received =
        withContext(Dispatchers.IO) {
            // Server references first: they never touch the sender, so they resolve whatever the URIs do.
            val batch = request.server?.let { refs.resolve(it, request.title, at = request.batchAt) }
            if (request.uris.isEmpty()) {
                return@withContext Received(
                    serverOutcome(batch),
                    CompletableDeferred(Settled()),
                )
            }
            receiveUris(request, batch, holderAlive)
        }

    /**
     * The photos kept for [HandoffOutcome.ServerRefs] when Reveal was not paired: after pairing with the sender's
     * server ([serverId]) the batch resolves as if it had just arrived, and opens. Null when nothing waits for it.
     */
    suspend fun resumePending(serverId: String): HandoffOutcome? = withContext(Dispatchers.IO) {
        // An atomic take: with two live activities (Reveal's own task and the sender's) only one resolves it.
        val groups = refs.takePending(serverId).takeIf { it.isNotEmpty() } ?: return@withContext null
        val at = clock()
        serverOutcome(
            groups.map { refs.resolve(refs.refsOf(it), it.title, at = at) }.reduce(ServerBatch::plus),
        )
    }

    /** True when a batch waits for a pairing. */
    suspend fun hasPending(): Boolean = refs.pendingGroups().isNotEmpty()

    /** The server id the pending batch waits for, if any. */
    suspend fun pendingServerId(): String? = refs.pendingGroups().firstOrNull()?.serverId

    /** The pending batch is not wanted any more (the sheet was cancelled). */
    suspend fun dropPending() = refs.clearPending()

    /**
     * Fills the photos that opened from what Reveal had while the server was unreachable; true when none is left
     * unreachable. Each batch keeps the time it was handed over, so its photos stay under their header.
     */
    suspend fun retryUnreachable(): Boolean = withContext(Dispatchers.IO) {
        val paired = server.pairing()?.serverId
        val groups = refs.retryGroups().filter { it.serverId == paired }
        if (groups.isEmpty()) return@withContext false
        groups.map { refs.resolve(refs.refsOf(it), it.title, refresh = true, at = it.at) }
            .all { it.issue !is ServerIssue.Unreachable }
    }

    private fun serverOutcome(batch: ServerBatch?): HandoffOutcome {
        batch ?: return HandoffOutcome.Nothing
        val shown = batch.photos
        return when {
            shown.isEmpty() -> if (batch.issue != null || batch.notFound > 0) {
                HandoffOutcome.ServerRefs(batch.issue, batch.notFound)
            } else {
                HandoffOutcome.Nothing
            }

            batch.initialVersionId != null || shown.size == 1 -> HandoffOutcome.OpenEditor(
                batch.initialVersionId ?: shown.first().versionId,
                closeOnBack = shown.size == 1,
                libraryBehind = shown.size > 1,
                notFound = batch.notFound,
                issue = batch.issue,
            )

            else -> HandoffOutcome.OpenLibrary(shown.map { it.photoId }, batch.notFound, batch.issue)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun receiveUris(
        request: HandoffRequest,
        batch: ServerBatch?,
        holderAlive: () -> Boolean,
    ): Received {
        val done: Deferred<Settled> = CompletableDeferred(Settled())
        val persisted = HashSet<Uri>()
        val items = request.uris.mapNotNull { uri ->
            if (persist(uri, request.persistable)) persisted += uri
            val entry = tree.stat(uri.toString())
            // A provider that cannot be queried still gets its last path segment as a name.
            Item(
                uri,
                entry ?: DocumentEntry(uri.toString(), uri.lastPathSegment.orEmpty().substringAfterLast('/'), "", 0, 0),
            )
        }
        val (sidecarItems, named) = items.partition { SidecarNames.isSidecar(it.name) }
        // A Downloads item may have no extension: its first bytes decide, and it is named after what it is (B9).
        val imageItems = named.map(::sniffed)
        val displayable = imageItems.filter { ImageFormats.classify(it.name).isDisplayable }
        if (displayable.isEmpty()) {
            // Keep M0's behaviour for a single unrecognised name: the editor opens it by URI and explains.
            val single = imageItems.singleOrNull()
            val serverHasSay = batch != null && (batch.photos.isNotEmpty() || batch.issue != null)
            val outcome = if (serverHasSay || (batch != null && batch.notFound > 0)) {
                serverOutcome(batch)
            } else if (single != null && request.uris.size == 1) {
                HandoffOutcome.OpenUri(single.uri)
            } else {
                HandoffOutcome.Nothing
            }
            return Received(outcome, done)
        }
        val shots = pair(displayable, sidecarItems)
        val source = ensureSource()
        fingerprint(source.id, displayable)
        val now = request.batchAt ?: clock()
        val stored = ArrayList<Pair<Shot, StoredShot>>()
        db.withTransaction {
            for (shot in shots) {
                stored += shot to store(source.id, shot, now, request.title, joinBatch = request.batchAt != null)
            }
            photos.deleteEmptyPhotos(source.id)
        }
        // Headers first: what is embedded in a file (JPEG XMP) only fills an unrated photo, and the sender's culling
        // values or the sidecar applied next win over it.
        indexer.readMetadata(source.id, tree)
        applyRatings(stored, request.culling)
        prune(source.id)
        val initial = request.initial?.let { wanted ->
            stored.firstOrNull { (shot, _) ->
                shot.files.any {
                    it.first.uri ==
                        wanted
                }
            }
        }
        val served = batch?.photos.orEmpty()
        val total = stored.size + served.size
        val first = initial ?: stored.firstOrNull()
        // The photo the editor or the library opens first: its Downloads original is kept before the outcome (B8).
        val head = listOfNotNull(first)
        val rest = stored.filter { it !== first }
        val headNoSpace = keepOriginals(source.id, head, parallel = 1)
        // The editor opens after this returns (one photo, or the initial one): its decode comes first.
        if (initial != null || stored.size == 1) previewer?.expectEditor()
        val headPreviews = schedulePreviews(source.id, head, persisted, holderAlive)
        val settled = later {
            val noSpace = headNoSpace + keepOriginals(source.id, rest, parallel = COPY_PARALLELISM)
            schedulePreviews(source.id, rest, persisted, holderAlive).join()
            headPreviews.join()
            Settled(noSpace)
        }
        val notFound = batch?.notFound ?: 0
        val issue = batch?.issue
        val outcome = when {
            // An item to open at once wins over a URI one (ADR 0011 "Amendment W3/H").
            batch?.initialVersionId != null ->
                HandoffOutcome.OpenEditor(batch.initialVersionId, total == 1, total > 1, notFound, issue)

            first == null -> serverOutcome(batch)

            initial != null || total == 1 ->
                HandoffOutcome.OpenEditor(first.second.versionId, total == 1, total > 1, notFound, issue)

            else -> HandoffOutcome.OpenLibrary(
                stored.map {
                    it.second.photoId
                } + served.map { it.photoId },
                notFound,
                issue,
            )
        }
        return Received(outcome, settled)
    }

    /**
     * Runs [block] after the outcome: in [background] when there is one (its result completes the returned value),
     * else at once. A failure is logged and settles with nothing more done.
     */
    private suspend fun later(block: suspend () -> Settled): Deferred<Settled> {
        suspend fun guarded(): Settled = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "A hand-off's background work failed: ${e.javaClass.simpleName}")
            Settled()
        }
        val scope = background ?: return CompletableDeferred(guarded())
        return scope.async { guarded() }
    }

    /** [item] named after what its first bytes say when its name has no extension (a Downloads item, B9). */
    @Suppress("ReturnCount")
    private fun sniffed(item: Item): Item {
        if (item.name.substringAfterLast('.', "").isNotEmpty()) return item
        val head = runCatching {
            tree.openInput(item.uri.toString(), item.name).use { input ->
                java.io.FileInputStream(input.descriptor.fileDescriptor).use { stream ->
                    val bytes = ByteArray(LocalOriginals.HEAD_BYTES)
                    bytes.copyOf(stream.read(bytes).coerceAtLeast(0))
                }
            }
        }.getOrNull() ?: return item
        val type = ImageFormats.classify(item.name, head)
        val raw = type.raw
        val extension = when {
            raw != null -> raw.extensions.first()
            type.kind == ImageKind.JPEG -> "jpg"
            type.kind == ImageKind.HEIF -> "heic"
            type.kind == ImageKind.PNG -> "png"
            type.kind == ImageKind.WEBP -> "webp"
            else -> return item
        }
        return Item(item.uri, item.entry.copy(name = "${item.name}.$extension"))
    }

    /**
     * Copies the files the sender's temporary grant covers only (MediaStore Downloads, W4) into [originals] while the
     * grant is valid, and files them under the copy; [parallel] at a time, each open through the tree's [FileOpens]
     * slot (`busy:` is retried). Only files filed under the hand-off source: a shot that is a folder's or a Server
     * photo (`foreign`) keeps its own files and gets no copy (B3). A copy already kept (same fingerprint and size) is
     * reused without opening the URI again (B4). A failure keeps the URI, and the photo falls back to its preview as
     * for any unreadable hand-off. Returns how many were not kept for lack of space.
     */
    @Suppress("ReturnCount")
    private suspend fun keepOriginals(sourceId: Long, stored: List<Pair<Shot, StoredShot>>, parallel: Int): Int {
        val store = originals ?: return 0
        val wanted = stored.filterNot { it.second.foreign }.flatMap { (shot, ids) ->
            shot.files.map { it.first }
                .filter { it.fingerprint != null && LocalOriginals.isDownloads(it.uri) }
                .map { ids.photoId to it }
        }
        if (wanted.isEmpty()) return 0
        val gate = Semaphore(parallel)
        val noSpace = AtomicInteger()
        coroutineScope {
            wanted.map { (photoId, item) ->
                async {
                    gate.withPermit { if (!keepOriginal(store, sourceId, photoId, item)) noSpace.incrementAndGet() }
                }
            }.awaitAll()
        }
        return noSpace.get()
    }

    /** One [keepOriginals] file; false only when it was refused for lack of space. */
    @Suppress("ReturnCount")
    private suspend fun keepOriginal(store: LocalOriginals, sourceId: Long, photoId: Long, item: Item): Boolean {
        val uri = item.uri.toString()
        if (photos.files(photoId).none { it.sourceId == sourceId && it.documentId == uri }) return true
        val print = checkNotNull(item.fingerprint)
        val copy = store.existing(print, item.name, item.entry.size) ?: try {
            store.copyIn(tree.openInput(uri, item.name), print, item.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalOriginals.NoSpaceException) {
            Log.w(TAG, "Not enough space to keep the original of a Downloads hand-off", e)
            return false
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Could not keep the original of a Downloads hand-off: ${e.javaClass.simpleName}")
            null
        }
        if (copy != null) photos.repointFile(sourceId, uri, store.documentId(copy))
        return true
    }

    /**
     * [foreign]: the photo of an added folder that holds the same file (left untouched by the hand-off).
     * [previousHandoff]: when the photo was handed over before this request (null: a new photo).
     */
    private class StoredShot(
        val photoId: Long,
        val versionId: Long,
        val foreign: Boolean = false,
        val previousHandoff: Long? = null,
    )

    /**
     * Fingerprints [items] (ADR 0006 "Identity"; at most 2 MiB read per file, [FINGERPRINT_PARALLELISM] at a time).
     * A URI already filed with the same size and mtime reuses its stored fingerprint instead of reading again.
     */
    private suspend fun fingerprint(sourceId: Long, items: List<Item>) {
        val known = items.map { it.uri.toString() }.chunked(SQL_ARGS)
            .flatMap { photos.filesByPath(sourceId, it) }.associateBy { it.relativePath }
        val gate = Semaphore(FINGERPRINT_PARALLELISM)
        coroutineScope {
            items.map { item ->
                async {
                    val stored = known[item.uri.toString()]?.takeIf {
                        it.size == item.entry.size && it.modified == item.entry.modified && item.entry.modified != 0L
                    }?.fingerprint
                    item.fingerprint = stored ?: gate.withPermit { tree.fingerprint(item.uri.toString(), item.name) }
                }
            }.awaitAll()
        }
    }

    /** Groups [images] by parent and case-insensitive stem (`ShotPairing`), and attaches each shot's sidecar. */
    private fun pair(images: List<Item>, sidecars: List<Item>): List<Shot> {
        val out = ArrayList<Shot>()
        for ((parent, group) in images.groupBy { it.parent }) {
            // The same name twice in one parent (a provider that flattens folders): the later one stands alone.
            val byName = LinkedHashMap<String, Item>()
            val extras = ArrayList<Item>()
            for (item in group) if (byName.putIfAbsent(item.name, item) != null) extras += item
            val listed = byName.values.map { ListedFile(it.name, it.entry.size, it.entry.modified) }
            for (shot in ShotPairing.pair(listed)) {
                val files = buildList {
                    shot.raw?.let { add(byName.getValue(it.name) to FileRole.RAW) }
                    shot.rendered?.let { add(byName.getValue(it.name) to FileRole.RENDERED) }
                    shot.others.forEach { add(byName.getValue(it.name) to FileRole.OTHER) }
                }
                out += Shot(parent, shot.stem.lowercase(), files.first().first, files)
                    .also { it.sidecar = sidecarFor(it, sidecars) }
            }
            for (item in extras) {
                out += Shot(
                    parent,
                    ImageFormats.stem(item.name).lowercase(),
                    item,
                    listOf(item to if (ImageFormats.classify(item.name).isRaw) FileRole.RAW else FileRole.RENDERED),
                )
            }
        }
        return out
    }

    /**
     * The shot's sidecar among the items ([SidecarNames]): `<stem>.xmp` (Lightroom, Hoard), else `<file name>.xmp`
     * (darktable), then the hidden `.<stem>.xmp` / `.<file name>.xmp`; names compare case-insensitively.
     */
    private fun sidecarFor(shot: Shot, sidecars: List<Item>): Item? {
        if (sidecars.isEmpty()) return null
        val candidates = SidecarNames.candidates(shot.stem, shot.files.map { it.first.name })
        val sameParent = sidecars.filter { it.parent == shot.folder }.ifEmpty { sidecars }
        return candidates.firstNotNullOfOrNull { c -> sameParent.firstOrNull { it.name.lowercase() == c } }
    }

    /** An earlier file that is the same as one of the shot's: its row and where it lives. */
    private class Match(val file: PhotoFileEntity, val handoff: Boolean)

    /**
     * The stored file that is the same file as [item] (ADR 0006 "Identity"): one with the same content fingerprint,
     * a hand-off one first, else an added folder's (available, not removed from the library). A row without a
     * fingerprint yet (filed before fingerprints, or unreadable) is the same file when the URI, size and mtime agree.
     */
    private fun matchFor(
        item: Item,
        sourceId: Long,
        byPath: Map<String, PhotoFileEntity>,
        byPrint: List<FingerprintMatch>,
    ): Match? {
        val print = item.fingerprint
        val samePrint = if (print == null) emptyList() else byPrint.filter { it.file.fingerprint == print }
        val handoff = samePrint.firstOrNull { it.file.sourceId == sourceId }?.file
            ?: byPath[item.uri.toString()]?.takeIf {
                (it.fingerprint == null || print == null) &&
                    sameFile(it, item.entry)
            }
        return handoff?.let { Match(it, handoff = true) }
            ?: samePrint.filter(::reusableFolderFile).let { reusable ->
                // A NAS-feed Server photo of the same file must not win over the adopted or edited one (lost edits).
                reusable.firstOrNull { it.photoKept } ?: reusable.firstOrNull()
            }?.let { Match(it.file, handoff = false) }
    }

    /**
     * A photo a hand-off may open as it is: a folder photo whose folder is there, or a Server photo (the server holds
     * the file: a hand-off adopted by the Server source, ADR 0018 "W3/C", or a NAS photo), not removed from the
     * library. The sender's URI is then never read to show or edit it.
     */
    private fun reusableFolderFile(match: FingerprintMatch) = !match.photoHidden && (
        (match.sourceKind == SourceKind.FOLDER && match.sourceState == SourceState.AVAILABLE) ||
            match.sourceKind == SourceKind.SERVER
        )

    /**
     * The earlier file that decides [shot]'s photo, or null for a new one. The main file's photo wins (a RAW over its
     * rendering), else the first file already known. The shot's files filed under another hand-off photo (same URI: a
     * pair completed later or a replaced file; same content: another copy) leave it; its edits stay behind with it.
     */
    private suspend fun findPhoto(sourceId: Long, shot: Shot): Match? {
        val byPath = photos.filesByPath(sourceId, shot.files.map { it.first.uri.toString() })
            .associateBy { it.relativePath }
        val prints = shot.files.mapNotNull { it.first.fingerprint }.distinct()
        val byPrint = if (prints.isEmpty()) emptyList() else photos.filesByFingerprint(prints)
        val matches = shot.files.mapNotNull { (item, _) -> matchFor(item, sourceId, byPath, byPrint) }
        val mainPrint = shot.main.fingerprint
        val match = matches.firstOrNull { mainPrint != null && it.file.fingerprint == mainPrint }
            ?: matches.firstOrNull { it.file.relativePath == shot.main.uri.toString() }
            ?: matches.firstOrNull()
        if (match == null || match.handoff) {
            val keep = match?.file?.photoId
            val moving = byPath.values + byPrint.filter { it.file.sourceId == sourceId }.map { it.file }
            val ids = moving.filter { it.photoId != keep }.map { it.id }.distinct()
            if (ids.isNotEmpty()) photos.deleteFiles(ids)
        }
        return match
    }

    /**
     * Files [shot] as a photo, recognised by **content fingerprint** (ADR 0006 "Identity"), never by name or URI
     * alone: the photo that already holds the main file (else another file of the shot) is reused with its edits and
     * history, its file set replaced by the shot's (the same photo through another URI or app; a pair completed later
     * when the other file matches). No match is a new photo with an empty history: `DSC00001.JPG` twice (two cards,
     * a counter reset) is two photos, and a file replaced under the same URI (other fingerprint) leaves its old photo
     * and does not inherit its recipe. A match in an added folder opens that photo as it is (its files stay there).
     */
    @Suppress("LongMethod", "ReturnCount")
    private suspend fun store(sourceId: Long, shot: Shot, now: Long, title: String?, joinBatch: Boolean): StoredShot {
        val format = shotFormat(shot)
        val hasRaw = shot.files.any { it.second == FileRole.RAW }
        val sidecarId = shot.sidecar?.uri?.toString()
        val match = findPhoto(sourceId, shot)
        if (match == null && joinBatch) joinEarlierChunk(sourceId, shot, now, title)?.let { return it }
        if (match != null && !match.handoff) {
            // The folder photo is the photo (ADR 0011 "One photo across sources"): its files stay where they are; it
            // shows in "Opened from other apps" with this batch.
            val photoId = match.file.photoId
            val previous = photos.get(photoId)?.handoffAt
            photos.setHandoffAt(photoId, now, title)
            return StoredShot(
                photoId,
                checkNotNull(photos.firstVersion(photoId)),
                foreign = true,
                previousHandoff = previous,
            )
        }
        val existing = match?.file?.photoId
        val photoId: Long
        val versionId: Long
        var previous: Long? = null
        if (existing == null) {
            photoId = photos.insertPhoto(
                PhotoEntity(
                    sourceId = sourceId,
                    folder = photoKey(sourceId, shot),
                    folderDocumentId = shot.folder,
                    stemKey = shot.stem,
                    name = shot.main.name,
                    format = format,
                    hasRaw = hasRaw,
                    sortTime = LibraryIndexer.localWallClock(shot.main.entry.modified.takeIf { it > 0 } ?: now),
                    addedAt = now,
                    handoffAt = now,
                    handoffTitle = title,
                    xmpDocumentId = sidecarId,
                ),
            )
            versionId = photos.insertVersion(VersionEntity(photoId = photoId, createdAt = now))
        } else {
            val photo = checkNotNull(photos.get(existing))
            previous = photo.handoffAt
            photoId = existing
            versionId = checkNotNull(photos.firstVersion(photoId))
            photos.deleteFilesOf(photoId)
            photos.updateHandoff(
                photoId, shot.main.name, format, hasRaw, shot.folder, now, title, sidecarId,
                photo.xmpModified.takeIf { sidecarId == photo.xmpDocumentId },
            )
        }
        photos.insertFiles(
            shot.files.map { (item, role) ->
                PhotoFileEntity(
                    photoId = photoId,
                    sourceId = sourceId,
                    relativePath = item.uri.toString(),
                    documentId = item.uri.toString(),
                    name = item.name,
                    size = item.entry.size,
                    modified = item.entry.modified,
                    role = role,
                    fingerprint = item.fingerprint,
                )
            },
        )
        return StoredShot(photoId, versionId, previousHandoff = previous)
    }

    /**
     * A safety net for a batch split over several intents (ADR 0011 "Amendment W4"; the sender keeps a shot's members
     * in one chunk): a single file whose shot an earlier chunk of the same batch (same `handoff_at` stamp) already
     * filed, under the same parent key and stem, joins that photo when the photo lacks its role (a RAW meets its JPEG).
     */
    @Suppress("ReturnCount")
    private suspend fun joinEarlierChunk(sourceId: Long, shot: Shot, now: Long, title: String?): StoredShot? {
        val (item, role) = shot.files.singleOrNull() ?: return null
        if (role == FileRole.OTHER) return null
        val photo = photos.batchShot(sourceId, now, shot.folder, shot.stem) ?: return null
        val files = photos.files(photo.id)
        if (files.any { it.role == role }) return null
        photos.insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = photo.id,
                    sourceId = sourceId,
                    relativePath = item.uri.toString(),
                    documentId = item.uri.toString(),
                    name = item.name,
                    size = item.entry.size,
                    modified = item.entry.modified,
                    role = role,
                    fingerprint = item.fingerprint,
                ),
            ),
        )
        val raw = if (role == FileRole.RAW) item.name else files.firstOrNull { it.role == FileRole.RAW }?.name
        val rendered = if (role ==
            FileRole.RENDERED
        ) {
            item.name
        } else {
            files.firstOrNull { it.role == FileRole.RENDERED }?.name
        }
        val format = LibraryIndexer.format(
            dev.hfps.image.format.Shot(
                stem = shot.stem,
                raw = raw?.let(::ListedFile),
                rendered = rendered?.let(::ListedFile),
            ),
        )
        // The RAW is the shot's main file from now on.
        photos.updateHandoff(
            photo.id, raw ?: photo.name, format, raw != null, shot.folder, now, title, photo.xmpDocumentId,
            photo.xmpModified,
        )
        return StoredShot(photo.id, checkNotNull(photos.firstVersion(photo.id)))
    }

    /**
     * The `(folder, stem_key)` row key of a new hand-off photo. Hand-offs are matched by file identity, not by this
     * key, so it only has to be unique: the parent, the main file's URI and its size/mtime (a replaced file under the
     * same URI gets a new key), plus a counter in the unlikely case that a row still holds it.
     */
    private suspend fun photoKey(sourceId: Long, shot: Shot): String {
        val entry = shot.main.entry
        val base = "${shot.folder}\u0000${shot.main.uri}\u0000${entry.size}:${entry.modified}"
        var key = base
        var n = 1
        while (photos.byStem(sourceId, key, shot.stem) != null) key = "$base\u0000${n++}"
        return key
    }

    /**
     * Sets each shot's rating, flag and label (ADR 0011 "Culling"). Without culling values from the sender, the
     * sidecar's apply, as the folder indexer does (only the fields it changed since it was last read, ADR 0027). With
     * values, they win unless the shot's sidecar was modified after the photo's previous hand-off (someone rated it
     * since the sender last said what it holds): then the sidecar wins. Fields the sender left out keep the sidecar's
     * (or the photo's) value. Nothing is written back (no sidecar is written automatically, W4): the values live in
     * Reveal's database, and the sender's files stay as they are. A sender's value that the sync server already holds
     * for the shot is not pushed back to it (Hoard sends its cached server values, ADR 0027 "Hand-offs").
     */
    private suspend fun applyRatings(stored: List<Pair<Shot, StoredShot>>, culling: Map<Uri, HandoffCulling>) {
        val valued = ArrayList<Long>()
        for ((shot, ids) in stored) {
            // Per field, a dated value (CULLING_VERSION 2) wins only as the last writer; undated ones keep version 1's
            // precedence. The values that were judged and applied come back (null: none).
            val sent = cullingFor(shot, culling)
            val values = if (!ids.foreign) {
                applyRatings(shot, ids, sent)
            } else {
                sent?.let { byLastWriter(ids.photoId, it) }?.takeUnless { it.isEmpty }?.also {
                    applyFolderCulling(ids, it)
                }
            }
            if (values != null) {
                valued += ids.photoId
                dateValues(ids.photoId, values)
            }
        }
        settle(valued)
    }

    /** [values] with each dated field kept only when it beats what Reveal holds for it (per-field last writer). */
    private suspend fun byLastWriter(photoId: Long, values: HandoffCulling): HandoffCulling {
        suspend fun wins(field: CullingField, at: Long?, device: String?): Boolean {
            val mine = if (at == null) null else held(photoId, field)
            return at == null || mine == null || CullingStamp(at, device).beats(mine)
        }
        val rating = wins(CullingField.RATING, values.ratingAt, values.ratingDevice)
        val flag = wins(CullingField.FLAG, values.flagAt, values.flagDevice)
        val label = wins(CullingField.LABEL, values.labelAt, values.labelDevice)
        return values.copy(
            rating = values.rating.takeIf { rating },
            ratingAt = values.ratingAt.takeIf { rating },
            ratingDevice = values.ratingDevice.takeIf { rating },
            flag = values.flag.takeIf { flag },
            flagAt = values.flagAt.takeIf { flag },
            flagDevice = values.flagDevice.takeIf { flag },
            label = values.label.takeIf { label },
            labelAt = values.labelAt.takeIf { label },
            labelDevice = values.labelDevice.takeIf { label },
        )
    }

    /**
     * Who last decided [field] of [photoId], as far as Reveal knows: its own change not pushed yet (this device), the
     * server's record as last seen (its time; the author is not kept, so an equal-time tie goes to the sender and
     * the server's own rule settles it at the push), or the sidecar's mtime. The newest of them; null = never decided.
     */
    private suspend fun held(photoId: Long, field: CullingField): CullingStamp? {
        val dao = db.cullingDao()
        val pending = dao.pendingOf(photoId).firstOrNull { it.field == field.key }
            ?.let { CullingStamp(it.modifiedAt, server.pairing()?.deviceId) }
        val record = dao.recordOf(photoId)?.let {
            when (field) {
                CullingField.RATING -> it.ratingAt
                CullingField.FLAG -> it.flagAt
                CullingField.LABEL -> it.labelAt
            }
        }?.takeIf { it > 0 }?.let { CullingStamp(it) }
        val photo = photos.get(photoId)
        val sidecar = photo?.xmpModified?.takeIf { photo.xmpDocumentId != null }?.let { CullingStamp(it) }
        return listOfNotNull(pending, record, sidecar).maxWithOrNull(CullingStamp::compare)
    }

    private fun HandoffCulling.datedOnly() = copy(
        rating = rating.takeIf { ratingAt != null },
        flag = flag.takeIf { flagAt != null },
        label = label.takeIf { labelAt != null },
    )

    /** The sender's decision times (when it sent them) date the queued changes instead of now. */
    private suspend fun dateValues(photoId: Long, values: HandoffCulling) {
        val dao = db.cullingDao()
        values.ratingAt?.let { dao.date(photoId, listOf(CullingField.RATING.key), it) }
        values.flagAt?.let { dao.date(photoId, listOf(CullingField.FLAG.key), it) }
        values.labelAt?.let { dao.date(photoId, listOf(CullingField.LABEL.key), it) }
    }

    /** Queued changes equal to the server's cached value are dropped: nothing to tell the server (ADR 0027). */
    private suspend fun settle(photoIds: List<Long>) {
        val dao = db.cullingDao()
        for (chunk in photoIds.chunked(SQL_ARGS)) {
            dao.settleRatings(chunk)
            dao.settleFlags(chunk)
            dao.settleLabels(chunk)
        }
    }

    /**
     * The sender's culling [values] on a photo of an added folder (ADR 0011 "One photo across sources"): they win,
     * field by field, unless the folder's sidecar was written after the photo's previous hand-off (someone rated it
     * there since the sender last said what it holds). Returns the fields that changed.
     */
    @Suppress("ReturnCount")
    private suspend fun applyFolderCulling(ids: StoredShot, values: HandoffCulling): Set<CullingField> {
        val photo = photos.get(ids.photoId) ?: return emptySet()
        val current = XmpFields(photo.rating.coerceIn(0, XmpFields.MAX_RATING), photo.label, photo.flag)
        val previous = ids.previousHandoff
        val sidecarTime = photo.xmpModified.takeIf { photo.xmpDocumentId != null }
        val sidecarNewer = previous != null && sidecarTime != null && sidecarTime > previous
        val applied = if (sidecarNewer) values.datedOnly() else values
        if (applied.isEmpty) return emptySet()
        val result = merge(applied, current)
        if (result == current) return emptySet()
        photos.setCulling(photo.id, result.rating, result.label, result.flag)
        return buildSet {
            if (result.rating != current.rating) add(CullingField.RATING)
            if (result.flag != current.flag) add(CullingField.FLAG)
            if (result.label != current.label) add(CullingField.LABEL)
        }
    }

    private suspend fun applyRatings(shot: Shot, ids: StoredShot, sent: HandoffCulling?): HandoffCulling? {
        val sidecar = shot.sidecar
        if (sidecar == null && sent == null) return null
        val read = sidecar?.let(::readSidecar)
        val sidecarId = sidecar?.uri?.toString()
        val sidecarTime = sidecar?.entry?.modified
        val previous = ids.previousHandoff
        val sidecarNewer = read != null && previous != null && sidecarTime != null && sidecarTime > previous
        when {
            // The sidecar first (what changed in it), then the sender's values over it unless the sidecar is newer.
            read != null -> SidecarImport.apply(db, ids.photoId, read, sidecarId, sidecarTime)

            // Unreadable sidecar: remember it so it is not read again until it changes.
            sidecar != null -> photos.setSidecar(ids.photoId, sidecarId, sidecarTime)
        }
        // The dated values are judged against what the sidecar just set (its mtime counts), as the last writer; a newer
        // sidecar beats the undated ones (version 1).
        val values = sent?.let { byLastWriter(ids.photoId, it) }?.takeUnless { it.isEmpty }
        val applied = if (sidecarNewer) values?.datedOnly() else values
        if (applied != null && !applied.isEmpty) {
            val base = storedFields(ids.photoId)
            val result = merge(applied, base)
            if (result != base) photos.setCulling(ids.photoId, result.rating, result.label, result.flag)
        }
        return values
    }

    private fun readSidecar(item: Item): SidecarCulling? = runCatching {
        XmpReader.readCulling(tree.readBytes(item.uri.toString(), LibraryIndexer.MAX_SIDECAR_BYTES))
    }.getOrNull()

    private suspend fun storedFields(photoId: Long): XmpFields = photos.get(photoId)?.let {
        XmpFields(it.rating.coerceIn(0, XmpFields.MAX_RATING), it.label, it.flag)
    } ?: XmpFields()

    /** The sender's values for [shot]: per field, the first file (main first) that has one; null if none. */
    private fun cullingFor(shot: Shot, culling: Map<Uri, HandoffCulling>): HandoffCulling? {
        val all = shot.files.mapNotNull { culling[it.first.uri] }
        return if (all.isEmpty()) {
            null
        } else {
            val rating = all.firstOrNull { it.rating != null }
            val flag = all.firstOrNull { it.flag != null }
            val label = all.firstOrNull { it.label != null }
            HandoffCulling(
                rating = rating?.rating,
                flag = flag?.flag,
                label = label?.label,
                ratingAt = rating?.ratingAt,
                flagAt = flag?.flagAt,
                labelAt = label?.labelAt,
                ratingDevice = rating?.ratingDevice,
                flagDevice = flag?.flagDevice,
                labelDevice = label?.labelDevice,
            )
        }
    }

    private fun shotFormat(shot: Shot) = LibraryIndexer.format(
        dev.hfps.image.format.Shot(
            stem = shot.stem,
            raw = shot.files.firstOrNull { it.second == FileRole.RAW }?.first?.let { ListedFile(it.name) },
            rendered = shot.files.firstOrNull { it.second == FileRole.RENDERED }?.first?.let { ListedFile(it.name) },
        ),
    )

    private suspend fun ensureSource(): SourceEntity = db.withTransaction {
        db.sourceDao().handoff() ?: SourceEntity(
            treeUri = HANDOFF_TREE,
            rootDocumentId = "",
            displayName = "Opened from other apps",
            volumeId = null,
            volumeName = null,
            state = SourceState.AVAILABLE,
            writable = true,
            addedAt = clock(),
            kind = SourceKind.HANDOFF,
            // "Photos opened from other apps" in Settings -> Sync: only edited photos unless "Everything" (FIX/W).
            backup = HandoffBackup.everything(db),
        ).let { it.copy(id = db.sourceDao().insert(it)) }
    }

    /**
     * Keeps the newest [MAX_PHOTOS] unedited hand-offs; older ones are forgotten with their persisted grants and local
     * previews. Folder photos beyond them only leave "Opened from other apps".
     */
    private suspend fun prune(sourceId: Long) {
        // The newest batch is never cut, however big (Hoard copies whole shoots, W4).
        val keep = maxOf(MAX_PHOTOS, photos.newestBatchSize())
        photos.clearStaleFolderHandoffs(keep)
        val stale = photos.staleHandoffs(sourceId, keep)
        if (stale.isEmpty()) return
        val files = stale.chunked(SQL_ARGS).flatMap { photos.filesOf(it) }
        stale.chunked(SQL_ARGS).forEach { photos.deletePhotos(it) }
        files.forEach { release(it.documentId.toUri()) }
        previewer?.previews?.delete(files.mapNotNull { it.fingerprint })
        // By fingerprint, whatever the rows pointed at (a copy whose row was never repointed too), unless still used.
        originals?.let { deleteCopies(photos, it, files.mapNotNull { file -> file.fingerprint }) }
    }

    /**
     * Takes a persistable grant (READ only: Reveal never asks for write on a hand-off, W4) when the sender offered
     * one; never throws. True when Reveal keeps access after this activity (a persisted grant, or a `file:` URI).
     */
    @Suppress("ReturnCount")
    private fun persist(uri: Uri, persistable: Boolean): Boolean {
        if (uri.scheme == "file") return true
        // A MediaStore URI is never persisted: the photo permission (or, for Downloads, a local copy) covers it.
        if (!persistable || uri.scheme != "content" || uri.authority == "media") return false
        return runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.i(TAG, "No persistable grant for a hand-off: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    /**
     * Local previews of the hand-off photos (ADR 0011 "Unreadable hand-offs"): schedules those of [stored] (their files
     * as filed now: a kept copy is read instead of the sender's URI). Returns the job of the display previews of the
     * shots whose documents only the sender's grant covers (the holder waits for it); the others' display previews and
     * every smart preview go on in the background, a smart preview of such a document only while [holderAlive] (else
     * it comes from the display preview or the server).
     */
    private suspend fun schedulePreviews(
        sourceId: Long,
        stored: List<Pair<Shot, StoredShot>>,
        persisted: Set<Uri>,
        holderAlive: () -> Boolean,
    ): Job {
        val previewer = previewer ?: return Job().apply { complete() }
        val shots = stored.filterNot { it.second.foreign }.mapNotNull { (shot, ids) ->
            val files = photos.files(ids.photoId).filter { it.sourceId == sourceId }
            val main =
                files.firstOrNull { it.role == FileRole.RAW } ?: files.firstOrNull { it.role == FileRole.RENDERED }
            val fingerprint = main?.fingerprint ?: shot.main.fingerprint ?: return@mapNotNull null
            fun document(role: FileRole) = files.firstOrNull { it.role == role }?.let {
                HandoffPreviewer.Document(it.documentId, it.name)
            }
            val documents = files.map { it.documentId }
            val lasting = documents.all { readableLater(it, persisted) }
            HandoffPreviewer.Shot(
                fingerprint = fingerprint,
                main = HandoffPreviewer.Document(
                    main?.documentId ?: shot.main.uri.toString(),
                    main?.name ?: shot.main.name,
                ),
                raw = document(FileRole.RAW),
                rendered = document(FileRole.RENDERED),
                smart = shot.main.uri !in persisted,
                readable = { lasting || holderAlive() },
            ) to lasting
        }
        // The shots only the grant covers first, in their own call: the holder waits for exactly these.
        val (lasting, granted) = shots.partition { it.second }
        val wait = previewer.schedule(granted.map { it.first })
        previewer.schedule(lasting.map { it.first })
        return wait
    }

    /**
     * Whether [documentId] stays readable after the sender's grant ends: a kept copy (`file:`), a persisted grant, or a
     * `MediaStore.Images` document while the photo permission is FULL.
     */
    private fun readableLater(documentId: String, persisted: Set<Uri>): Boolean =
        documentId.startsWith("file:") || documentId.toUri() in persisted ||
            (GalleryAccess.isMediaImages(documentId) && mediaReadable())

    private fun release(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    companion object {
        private const val TAG = "RevealHandoff"
        const val HANDOFF_TREE = "reveal-handoff:"

        /** Hand-off photos kept (unedited ones beyond this are forgotten, with their grants). */
        const val MAX_PHOTOS = 400

        /** Downloads originals copied at a time after the initial photo's (B8). */
        const val COPY_PARALLELISM = 2
        private const val SQL_ARGS = 900
        private const val FINGERPRINT_PARALLELISM = 4

        /** [values] over [base]: a field the sender left out keeps [base]'s; a label of `""` clears it. */
        internal fun merge(values: HandoffCulling, base: XmpFields) = XmpFields(
            rating = values.rating ?: base.rating,
            label = when (values.label) {
                null -> base.label
                "" -> null
                else -> values.label
            },
            flag = values.flag ?: base.flag,
        )

        /**
         * Whether a stored file row and a document under the same URI are the same file (ADR 0006 identity: path +
         * size + mtime). A value the provider did not report (0) is unknown and does not count against it.
         */
        internal fun sameFile(stored: PhotoFileEntity, now: DocumentEntry): Boolean =
            agree(stored.size, now.size) && agree(stored.modified, now.modified)

        private fun agree(a: Long, b: Long) = a == 0L || b == 0L || a == b

        /**
         * The "folder" two files must share to pair. A MediaStore document ([relativePath] = its `RELATIVE_PATH`) uses
         * that path without its top-level folder (`Pictures/`, `DCIM/`, `Download/`); a document URI whose decoded last
         * segment is a path (SAF `primary:DCIM/Camera/DSC1.ARW`) uses that path's directory; otherwise the URI minus
         * its last segment (FileProvider paths).
         */
        fun parentKey(uri: Uri, relativePath: String? = null): String {
            val segment = uri.lastPathSegment.orEmpty()
            val text = uri.toString()
            return when {
                // MediaStore (W4): the file's folder without its top-level collection folder, so Hoard's
                // `Pictures/Hoard/<batch>/` and `Download/Hoard/<batch>/` pair (a CR3 in Downloads, its JPEG in
                // Images) while a camera's `DCIM/IMG_0001.JPG` does not pair with a Hoard `IMG_0001.CR3`.
                uri.authority == MediaStore.AUTHORITY && relativePath != null ->
                    "media:" + relativePath.trim('/').substringAfter('/', "").lowercase()

                '/' in segment -> "${uri.authority}:${segment.substringBeforeLast('/')}"

                // A MediaStore URI without its path (not readable): its collection, nothing else.
                else -> text.substring(0, text.lastIndexOf('/').coerceAtLeast(0))
            }
        }
    }
}

/**
 * Deletes the kept originals of [fingerprints] (their photos were forgotten or merged) except those a file row still
 * files under its copy (ADR 0011 "Amendment W4").
 */
internal suspend fun deleteCopies(photos: PhotoDao, originals: LocalOriginals, fingerprints: List<String>) {
    val prints = fingerprints.distinct()
    if (prints.isEmpty()) return
    val used = prints.chunked(LOCAL_COPY_ARGS).flatMap { photos.localCopyPrints(it) }.toSet()
    originals.delete(prints - used)
}

private const val LOCAL_COPY_ARGS = 500
