package dev.hfps.reveal.data.handoff

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ListedFile
import dev.hfps.image.format.ShotPairing
import dev.hfps.reveal.data.db.FingerprintMatch
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.storage.fingerprint
import dev.hfps.reveal.data.xmp.SidecarNames
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    data class OpenEditor(val versionId: Long, val closeOnBack: Boolean, val libraryBehind: Boolean) : HandoffOutcome

    /** Several shots: show them in "Opened from other apps". */
    data class OpenLibrary(val photoIds: List<Long>) : HandoffOutcome

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
class HandoffRepository(
    private val resolver: ContentResolver,
    private val db: RevealDatabase,
    private val indexer: LibraryIndexer,
    private val tree: DocumentTree = HandoffTree(resolver),
    /** Writes a folder photo's sidecar when a hand-off's culling values change it (null in tests without one). */
    private val sidecars: SidecarSync? = null,
    /** Keeps a local preview of each hand-off while its grant is alive (ADR 0011 "Unreadable hand-offs"). */
    private val previewer: HandoffPreviewer? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val photos = db.photoDao()

    /** One resolved document of the request; [fingerprint] is its content fingerprint (null: unreadable). */
    private class Item(val uri: Uri, val entry: DocumentEntry) {
        val name: String get() = entry.name
        val parent: String get() = parentKey(uri)
        var fingerprint: String? = null
    }

    /** A paired shot ready to store: files with roles, and the sidecar item if the sender gave one. */
    private class Shot(val folder: String, val stem: String, val main: Item, val files: List<Pair<Item, FileRole>>) {
        var sidecar: Item? = null
    }

    suspend fun receive(request: HandoffRequest): HandoffOutcome = withContext(Dispatchers.IO) {
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
        val (sidecarItems, imageItems) = items.partition { SidecarNames.isSidecar(it.name) }
        val displayable = imageItems.filter { ImageFormats.classify(it.name).isDisplayable }
        if (displayable.isEmpty()) {
            // Keep M0's behaviour for a single unrecognised name: the editor opens it by URI and explains.
            val single = imageItems.singleOrNull()
            return@withContext if (single != null && request.uris.size == 1) {
                HandoffOutcome.OpenUri(single.uri)
            } else {
                HandoffOutcome.Nothing
            }
        }
        val shots = pair(displayable, sidecarItems)
        val source = ensureSource()
        fingerprint(source.id, displayable)
        val now = clock()
        val stored = ArrayList<Pair<Shot, StoredShot>>()
        db.withTransaction {
            for (shot in shots) stored += shot to store(source.id, shot, now, request.title)
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
        schedulePreviews(stored, initial, persisted)
        val first = initial ?: stored.firstOrNull()
        when {
            first == null -> HandoffOutcome.Nothing

            initial != null || stored.size == 1 ->
                HandoffOutcome.OpenEditor(
                    first.second.versionId,
                    closeOnBack = stored.size == 1,
                    libraryBehind = stored.size > 1,
                )

            else -> HandoffOutcome.OpenLibrary(stored.map { it.second.photoId })
        }
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
            ?: samePrint.firstOrNull(::reusableFolderFile)?.let { Match(it.file, handoff = false) }
    }

    /** A folder photo a hand-off may open: its folder is there and the photo was not removed from the library. */
    private fun reusableFolderFile(match: FingerprintMatch) = match.sourceKind == SourceKind.FOLDER &&
        match.sourceState == SourceState.AVAILABLE && !match.photoHidden

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
    @Suppress("LongMethod")
    private suspend fun store(sourceId: Long, shot: Shot, now: Long, title: String?): StoredShot {
        val format = shotFormat(shot)
        val hasRaw = shot.files.any { it.second == FileRole.RAW }
        val sidecarId = shot.sidecar?.uri?.toString()
        val match = findPhoto(sourceId, shot)
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
     * sidecar's apply, as the folder indexer does. With values, they win unless the shot's sidecar was modified after
     * the photo's previous hand-off (someone rated it since the sender last said what it holds): then the sidecar
     * wins. Fields the sender left out keep the sidecar's (or the photo's) value. Nothing is written back: the values
     * live in Reveal's database, and the sender's files stay as they are.
     */
    private suspend fun applyRatings(stored: List<Pair<Shot, StoredShot>>, culling: Map<Uri, HandoffCulling>) {
        val folderChanged = ArrayList<Long>()
        for ((shot, ids) in stored) {
            val values = cullingFor(shot, culling)
            if (!ids.foreign) {
                applyRatings(shot, ids, values)
            } else if (values != null && applyFolderCulling(ids, values)) {
                folderChanged += ids.photoId
            }
        }
        // A folder photo's culling lives in its folder's sidecar: written like any rating change made in Reveal.
        if (folderChanged.isNotEmpty()) sidecars?.write(folderChanged)
    }

    /**
     * The sender's culling [values] on a photo of an added folder (ADR 0011 "One photo across sources"): they win,
     * field by field, unless the folder's sidecar was written after the photo's previous hand-off (someone rated it
     * there since the sender last said what it holds). True when the photo changed.
     */
    @Suppress("ReturnCount")
    private suspend fun applyFolderCulling(ids: StoredShot, values: HandoffCulling): Boolean {
        val photo = photos.get(ids.photoId) ?: return false
        val current = XmpFields(photo.rating.coerceIn(0, XmpFields.MAX_RATING), photo.label, photo.flag)
        val previous = ids.previousHandoff
        val sidecarTime = photo.xmpModified.takeIf { photo.xmpDocumentId != null }
        if (previous != null && sidecarTime != null && sidecarTime > previous) return false
        val result = merge(values, current)
        if (result == current) return false
        photos.setCulling(photo.id, result.rating, result.label, result.flag)
        return true
    }

    private suspend fun applyRatings(shot: Shot, ids: StoredShot, values: HandoffCulling?) {
        val sidecar = shot.sidecar
        if (sidecar == null && values == null) return
        val fields = sidecar?.let(::readSidecar)
        val sidecarId = sidecar?.uri?.toString()
        val sidecarTime = sidecar?.entry?.modified
        val previous = ids.previousHandoff
        val sidecarNewer = fields != null && previous != null && sidecarTime != null && sidecarTime > previous
        val result = when {
            values != null && !sidecarNewer -> merge(values, fields ?: storedFields(ids.photoId))
            else -> fields
        }
        if (result == null) {
            // Unreadable sidecar, no values: remember it so it is not read again until it changes.
            photos.setSidecar(ids.photoId, sidecarId, sidecarTime)
        } else {
            photos.applyXmp(ids.photoId, result.rating, result.label, result.flag, sidecarId, sidecarTime)
        }
    }

    private fun readSidecar(item: Item): XmpFields? = runCatching {
        XmpReader.read(tree.readBytes(item.uri.toString(), LibraryIndexer.MAX_SIDECAR_BYTES))
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
            HandoffCulling(
                rating = all.firstNotNullOfOrNull { it.rating },
                flag = all.firstNotNullOfOrNull { it.flag },
                label = all.firstNotNullOfOrNull { it.label },
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
        photos.clearStaleFolderHandoffs(MAX_PHOTOS)
        val stale = photos.staleHandoffs(sourceId, MAX_PHOTOS)
        if (stale.isEmpty()) return
        val files = stale.chunked(SQL_ARGS).flatMap { photos.filesOf(it) }
        stale.chunked(SQL_ARGS).forEach { photos.deletePhotos(it) }
        files.forEach { release(it.documentId.toUri()) }
        previewer?.previews?.delete(files.mapNotNull { it.fingerprint })
    }

    /**
     * Takes a persistable grant (read + write, else read) when the sender offered one; never throws. True when Reveal
     * keeps access after this activity (a persisted grant, or a `file:` URI).
     */
    @Suppress("ReturnCount")
    private fun persist(uri: Uri, persistable: Boolean): Boolean {
        if (uri.scheme == "file") return true
        if (!persistable || uri.scheme != "content") return false
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { resolver.takePersistableUriPermission(uri, rw) }.isSuccess) return true
        return runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.i(TAG, "No persistable grant for a hand-off: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    /**
     * Local previews of the hand-off photos (ADR 0011 "Unreadable hand-offs"), [initial] first: the display image for
     * each, the smart preview for those whose grant is temporary. Folder photos need none (their folder stays
     * readable).
     */
    private fun schedulePreviews(
        stored: List<Pair<Shot, StoredShot>>,
        initial: Pair<Shot, StoredShot>?,
        persisted: Set<Uri>,
    ) {
        val previewer = previewer ?: return
        // The editor opens after `receive` returns (one photo, or the initial one): its decode comes first.
        if (initial != null || stored.size == 1) previewer.expectEditor()
        val first = initial ?: stored.firstOrNull()
        val ordered = listOfNotNull(first) + stored.filter { it !== first }
        val shots = ordered.filterNot { it.second.foreign }.mapNotNull { (shot, _) ->
            val fingerprint = shot.main.fingerprint ?: return@mapNotNull null
            fun document(role: FileRole) = shot.files.firstOrNull { it.second == role }?.first?.let {
                HandoffPreviewer.Document(it.uri.toString(), it.name)
            }
            HandoffPreviewer.Shot(
                fingerprint = fingerprint,
                main = HandoffPreviewer.Document(shot.main.uri.toString(), shot.main.name),
                raw = document(FileRole.RAW),
                rendered = document(FileRole.RENDERED),
                smart = shot.main.uri !in persisted,
            )
        }
        previewer.schedule(shots)
    }

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
         * The "folder" two files must share to pair. A document URI whose decoded last segment is a path (SAF
         * `primary:DCIM/Camera/DSC1.ARW`) uses that path's directory; otherwise the URI minus its last segment
         * (FileProvider paths, a MediaStore collection: a batch from one collection pairs by stem).
         */
        fun parentKey(uri: Uri): String {
            val segment = uri.lastPathSegment.orEmpty()
            if ('/' in segment) return "${uri.authority}:${segment.substringBeforeLast('/')}"
            val text = uri.toString()
            return text.substring(0, text.lastIndexOf('/').coerceAtLeast(0))
        }
    }
}
