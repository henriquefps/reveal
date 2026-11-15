package dev.hfps.reveal.data.library

import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SidecarCullingEntity
import dev.hfps.reveal.data.model.CullingField
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.xmp.CullingChanges
import dev.hfps.reveal.data.xmp.SidecarCulling

/**
 * Applies what a changed XMP sidecar says about a photo's culling (ADR 0027 "Sidecars", Hoard ADR 0016 "When a sidecar
 * is imported", ADR 0026 "First read"), the same way for a folder walk and a hand-off:
 *
 * - First read: the sidecar was never read at that path, or its record was dropped because the file disappeared. It
 *   fills only the fields that hold no value ([held]); fields that hold one are kept, whatever the sidecar's mtime
 *   (darktable creates its sidecars with `xmp:Rating=1`: a photo rated 5 in Reveal stays 5).
 * - Later reads of the same sidecar import only the fields that differ from the last read (newest by mtime, LWW).
 * - What was read is recorded (`photos.xmp_document_id`/`xmp_modified`, `sidecar_culling`); Export XMP records what it
 *   wrote the same way, so a file Reveal wrote is never a first read.
 *
 * A reject keeps the photo's stars, and the change is a local decision dated by the sidecar's mtime (the sync triggers
 * queue it; this dates it). Call it inside the caller's transaction.
 */
object SidecarImport {
    /** Applies [read] (the sidecar [documentId] as just read, mtime [modified]) to [photoId]; returns what changed. */
    suspend fun apply(
        db: RevealDatabase,
        photoId: Long,
        read: SidecarCulling,
        documentId: String?,
        modified: Long?,
    ): CullingChanges {
        val photos = db.photoDao()
        val culling = db.cullingDao()
        val photo = photos.get(photoId) ?: return CullingChanges()
        // What this same file held last time; another file (a new sidecar, a hidden one replaced) starts afresh.
        val last = culling.sidecar(photoId)?.takeIf { photo.xmpDocumentId == documentId }?.let(::fromEntity)
        val changes = read.changesSince(last).let { if (last == null) it.without(held(photo)) else it }
        photos.applySidecar(
            id = photoId,
            setRating = changes.rating != null,
            rating = changes.rating ?: photo.rating,
            setFlag = changes.flag != null,
            flag = changes.flag ?: photo.flag,
            setLabel = changes.label != null,
            label = changes.label?.takeIf { it.isNotEmpty() },
            xmpDocumentId = documentId,
            xmpModified = modified,
        )
        culling.putSidecar(SidecarCullingEntity(photoId, read.rating, read.flag?.name, read.label))
        if (modified != null && modified > 0 && !changes.isEmpty) {
            culling.date(photoId, changes.fields.map { it.key }, modified)
        }
        return changes
    }

    /**
     * The fields that hold a value (ADR 0026 "First read", shared with Hoard and hoard-server): non-default, whoever
     * set it: rating != 0, flag != none, a non-empty label. An explicit 0 stars counts as no value.
     */
    fun held(photo: PhotoEntity): Set<CullingField> = buildSet {
        if (photo.rating != 0) add(CullingField.RATING)
        if (photo.flag != PickFlag.NONE) add(CullingField.FLAG)
        if (!photo.label.isNullOrBlank()) add(CullingField.LABEL)
    }

    private fun CullingChanges.without(fields: Set<CullingField>) = CullingChanges(
        rating = rating.takeUnless { CullingField.RATING in fields },
        flag = flag.takeUnless { CullingField.FLAG in fields },
        label = label.takeUnless { CullingField.LABEL in fields },
    )

    private fun fromEntity(entity: SidecarCullingEntity) = SidecarCulling(
        rating = entity.rating,
        flag = entity.flag?.let { name -> PickFlag.entries.firstOrNull { it.name == name } },
        label = entity.label,
    )
}
