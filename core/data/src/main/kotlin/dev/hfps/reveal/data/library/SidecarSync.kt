package dev.hfps.reveal.data.library

import android.util.Log
import dev.hfps.image.format.ImageFormats
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.xmp.SidecarNames
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpWriter
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException

/** How many photos got their change into an XMP sidecar, and how many kept it in the database only. */
data class SidecarOutcome(val written: Int, val databaseOnly: Int) {
    operator fun plus(other: SidecarOutcome) =
        SidecarOutcome(written + other.written, databaseOnly + other.databaseOnly)

    companion object {
        val NONE = SidecarOutcome(0, 0)
    }
}

/**
 * Writes the library fields (rating, label, pick flag) of photos to their XMP sidecars (ADR 0006, "XMP"): the
 * existing sidecar is merged ([XmpWriter]), else `<stem>.xmp` is created next to the RAW (one sidecar per pair, as
 * Lightroom and Hoard expect). Only for sources that are available and writable; otherwise, or when the provider
 * refuses, the change stays in the database ("database only", the UI says so).
 */
class SidecarSync(
    private val db: RevealDatabase,
    private val trees: DocumentTreeFactory,
    private val creatorTool: String,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    private val photos = db.photoDao()

    suspend fun write(photoIds: List<Long>): SidecarOutcome {
        var outcome = SidecarOutcome.NONE
        val bySource = photos.getAll(photoIds).groupBy { it.sourceId }
        for ((sourceId, group) in bySource) {
            val source = db.sourceDao().get(sourceId)
            if (source == null || !source.writable || source.state != SourceState.AVAILABLE) {
                outcome += SidecarOutcome(0, group.size)
                continue
            }
            val tree = trees.open(source)
            for (photo in group) {
                val ok = try {
                    writeOne(tree, photo)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // Any provider failure (read-only document, vanished folder, malformed sidecar): database only.
                    Log.w(TAG, "No sidecar for photo ${photo.id}", e)
                    false
                }
                outcome += if (ok) SidecarOutcome(1, 0) else SidecarOutcome(0, 1)
            }
        }
        return outcome
    }

    private suspend fun writeOne(tree: DocumentTree, photo: PhotoEntity) {
        val fields =
            XmpFields(rating = photo.rating.coerceIn(0, XmpFields.MAX_RATING), label = photo.label, flag = photo.flag)
        val date = now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val current = photo.xmpDocumentId?.let { tree.stat(it) }
        val existing = current?.let { tree.readBytes(it.documentId, LibraryIndexer.MAX_SIDECAR_BYTES) }
        val bytes = XmpWriter.update(existing, fields, creatorTool, date)
        // A hidden sidecar (`.<stem>.xmp`) is read and merged, but the write goes to the normal `<stem>.xmp`, which
        // wins over it from then on; the hidden file is left as it was. Only where Reveal cannot create a document (a
        // hand-off item) does the hidden sidecar itself take the write.
        val hidden = current?.takeIf { SidecarNames.isHidden(it.name) }
        val documentId = if (current != null && hidden == null) {
            tree.writeBytes(current.documentId, bytes)
            current.documentId
        } else {
            val main = photos.files(photo.id).let { files ->
                files.firstOrNull { it.role == FileRole.RAW } ?: files.firstOrNull { it.role == FileRole.RENDERED }
            }
            val stem = ImageFormats.stem(main?.name ?: photo.name)
            val created = try {
                tree.create(photo.folderDocumentId, "$stem${SidecarNames.SUFFIX}", SIDECAR_MIME)
            } catch (e: IOException) {
                hidden ?: throw e
            }
            tree.writeBytes(created.documentId, bytes)
            created.documentId
        }
        // Remember the written state, so the next walk does not read our own sidecar back as a change.
        photos.setSidecar(photo.id, documentId, tree.stat(documentId)?.modified)
    }

    companion object {
        /** `application/octet-stream` keeps the `.xmp` name as given on every provider tried (no extension added). */
        const val SIDECAR_MIME = "application/octet-stream"
        private const val TAG = "RevealSidecars"
    }
}
