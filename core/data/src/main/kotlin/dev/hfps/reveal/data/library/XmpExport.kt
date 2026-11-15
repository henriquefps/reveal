package dev.hfps.reveal.data.library

import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentTree

/** Where Export XMP puts the sidecars. */
sealed interface XmpDestination {
    /**
     * `<stem>.xmp` next to each photo. [oneOff]: source id -> tree URI the user picked for a folder Reveal holds no
     * write grant for (used for this run only, never persisted).
     */
    data class NextToPhotos(val oneOff: Map<Long, String> = emptyMap()) : XmpDestination

    /** Flat `<stem>.xmp` files in a folder the user chose ([treeUri], its root document). */
    data class Folder(val treeUri: String) : XmpDestination
}

/** What a selection allows (ADR 0026 "Export XMP"): which destinations the sheet offers. */
data class XmpExportScope(
    val photos: Int,
    /** Every photo is a folder file under a tree with a write grant: "Next to the photos" works as it is. */
    val nextToPhotos: Boolean,
    /** Folder photos only, and these folders (source id, name) lack a write grant: pick one for a one-off write. */
    val needsGrant: List<Pair<Long, String>>,
) {
    /** "Next to the photos" is offered at all (possibly after picking the one folder that lacks a grant). */
    val canOfferNext: Boolean get() = nextToPhotos || needsGrant.size == 1
}

/**
 * Export XMP (ADR 0026 "Export XMP"): the only thing that writes XMP sidecars since 0.9.0. [analyse] says what the
 * sheet may offer for a selection; [exportOne] writes one photo (the job records each), [run] a list, through
 * [SidecarSync] (culling fields + the master version's edit, the merge rules of ADR 0026).
 */
class XmpExport(
    private val db: RevealDatabase,
    private val sidecars: SidecarSync,
    /** Opens a tree URI the user picked (SAF in the app, a file tree in tests). */
    private val openTree: (String) -> DocumentTree,
) {
    private val photos = db.photoDao()

    suspend fun analyse(photoIds: List<Long>): XmpExportScope {
        var folderOk = true
        var otherKind = false
        val lacking = LinkedHashMap<Long, String>()
        val sourceIds = photoIds.chunked(SQL_CHUNK).flatMap { photos.getAll(it) }.mapTo(LinkedHashSet()) { it.sourceId }
        for (sourceId in sourceIds) {
            val source = db.sourceDao().get(sourceId)
            if (source == null || source.kind != SourceKind.FOLDER || source.state != SourceState.AVAILABLE) {
                otherKind = true
            } else if (!source.writable) {
                folderOk = false
                lacking[sourceId] = source.displayName
            }
        }
        return XmpExportScope(
            photos = photoIds.size,
            nextToPhotos = !otherKind && folderOk,
            needsGrant = if (otherKind) emptyList() else lacking.map { it.key to it.value },
        )
    }

    /** The trees of one run of [destination], opened once. */
    class Session internal constructor(
        val destination: XmpDestination,
        internal val target: DocumentTree?,
        internal val oneOff: Map<Long, DocumentTree>,
    )

    /** What one photo's export did, and the document written into a chosen folder (null otherwise). */
    data class PhotoResult(val outcome: SidecarOutcome, val documentId: String? = null)

    fun open(destination: XmpDestination): Session = Session(
        destination,
        (destination as? XmpDestination.Folder)?.let { openTree(it.treeUri) },
        (destination as? XmpDestination.NextToPhotos)?.oneOff.orEmpty().mapValues { openTree(it.value) },
    )

    /**
     * Exports one photo. Idempotent, so a job run again after a kill writes no `(1)` copies: next to the photos it
     * merges into the sidecar it wrote (the photo records it before writing); in a chosen folder [ownDocument] (what
     * [onCreated] reported the last time) is overwritten instead of creating another file.
     */
    suspend fun exportOne(
        session: Session,
        photoId: Long,
        ownDocument: String? = null,
        onCreated: suspend (String) -> Unit = {},
    ): PhotoResult {
        val target = session.target
        return if (target != null) {
            val write = sidecars.writeOneInto(photoId, target, target.rootDocumentId, ownDocument, onCreated)
            PhotoResult(write.outcome, write.documentId)
        } else {
            PhotoResult(sidecars.write(listOf(photoId), oneOff = session.oneOff))
        }
    }

    /**
     * Whether the tree the user picked for "next to the photos" ([pickedTree]) is or contains the folder of source
     * [sourceId] ([XmpGrants.covers]); false: the export is not started and the user is told.
     */
    suspend fun covers(
        sourceId: Long,
        pickedTree: String,
        isChild: (android.net.Uri, String) -> Boolean = { _, _ -> false },
    ): Boolean {
        val source = db.sourceDao().get(sourceId) ?: return false
        return XmpGrants.covers(pickedTree, source, isChild)
    }

    /** Writes the sidecars of [photoIds] (tests, small runs); [onProgress] gets the photos done so far. */
    suspend fun run(
        photoIds: List<Long>,
        destination: XmpDestination,
        startAt: Int = 0,
        onProgress: suspend (done: Int, outcome: SidecarOutcome) -> Unit = { _, _ -> },
    ): SidecarOutcome {
        val session = open(destination)
        var outcome = SidecarOutcome.NONE
        var done = startAt
        for (photoId in photoIds.drop(startAt)) {
            outcome += exportOne(session, photoId).outcome
            done++
            onProgress(done, outcome)
        }
        return outcome
    }

    companion object {
        private const val SQL_CHUNK = 500
    }
}
