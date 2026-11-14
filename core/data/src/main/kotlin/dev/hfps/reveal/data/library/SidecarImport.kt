package dev.hfps.reveal.data.library

import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SidecarCullingEntity
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.xmp.CullingChanges
import dev.hfps.reveal.data.xmp.SidecarCulling

/**
 * Applies what a changed XMP sidecar says about a photo's culling (ADR 0027 "Sidecars", Hoard ADR 0016 "When a sidecar
 * is imported"), the same way for a folder walk and a hand-off: only the fields that differ from what the same sidecar
 * held when last read are applied (the first read of a sidecar: every field it holds), a reject keeps the photo's
 * stars,
 * and the change is a local decision dated by the sidecar's mtime (the sync triggers queue it; this dates it). Call it
 * inside the caller's transaction.
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
        val changes = read.changesSince(last)
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

    private fun fromEntity(entity: SidecarCullingEntity) = SidecarCulling(
        rating = entity.rating,
        flag = entity.flag?.let { name -> PickFlag.entries.firstOrNull { it.name == name } },
        label = entity.label,
    )
}
