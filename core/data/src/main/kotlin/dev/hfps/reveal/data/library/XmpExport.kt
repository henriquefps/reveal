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
 * sheet may offer for a selection; [run] writes, in chunks, through [SidecarSync] (culling fields + the master
 * version's edit, the merge rules of ADR 0026).
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

    /** Writes the sidecars of [photoIds]; [onProgress] gets the number of photos done after every chunk. */
    suspend fun run(
        photoIds: List<Long>,
        destination: XmpDestination,
        startAt: Int = 0,
        onProgress: suspend (done: Int, outcome: SidecarOutcome) -> Unit = { _, _ -> },
    ): SidecarOutcome {
        var outcome = SidecarOutcome.NONE
        val target = (destination as? XmpDestination.Folder)?.let { openTree(it.treeUri) }
        val oneOff = (destination as? XmpDestination.NextToPhotos)?.oneOff.orEmpty()
            .mapValues { openTree(it.value) }
        var done = startAt
        for (chunk in photoIds.drop(startAt).chunked(CHUNK)) {
            outcome += if (target != null) {
                sidecars.writeInto(chunk, target, target.rootDocumentId)
            } else {
                sidecars.write(chunk, oneOff = oneOff)
            }
            done += chunk.size
            onProgress(done, outcome)
        }
        return outcome
    }

    companion object {
        const val CHUNK = 25
        private const val SQL_CHUNK = 500
    }
}
