package dev.hfps.reveal.data.library

import android.util.Log
import dev.hfps.image.format.ImageFormats
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SidecarCullingEntity
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.CullingField
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.xmp.DevelopState
import dev.hfps.reveal.data.xmp.PhotoFrame
import dev.hfps.reveal.data.xmp.SidecarNames
import dev.hfps.reveal.data.xmp.SidecarRecipe
import dev.hfps.reveal.data.xmp.XmpDevelop
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import dev.hfps.reveal.data.xmp.XmpWriter
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
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
 * Writes the library fields (rating, label, pick flag) of photos to their XMP sidecars (ADR 0006, "XMP") and, while
 * [writeEdits] is on, the master version's edit (ADR 0026: the `reveal:` recipe, the Camera Raw overlap and the
 * orientation, [XmpDevelop]): the existing sidecar is merged ([XmpWriter]), else `<stem>.xmp` is created next to the
 * RAW (one sidecar per pair, as Lightroom and Hoard expect). Only for sources that are available and writable;
 * otherwise, or when the provider refuses, the change stays in the database ("database only", the UI says so). A
 * hand-off photo is written only through a sidecar its sender granted; Server photos never.
 *
 * Only the culling fields the user changed are written (ADR 0027): an edit-only write leaves `xmp:Rating`,
 * `digiKam:PickLabel` and `xmp:Label` as the sidecar has them, so a value another device or Lightroom put there is not
 * overwritten with one Reveal pulled from the server. What the sidecar holds after the write is remembered
 * (`sidecar_culling`), so the next import of the file re-asserts only what changes in it afterwards.
 */
class SidecarSync(
    private val db: RevealDatabase,
    private val trees: DocumentTreeFactory,
    private val creatorTool: String,
    /** Settings → "Write edits to XMP sidecars" (ADR 0026); off = only the library fields. */
    private val writeEdits: () -> Boolean = { false },
    /** This device's model, `reveal:Device`. */
    private val device: String = "",
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    private val photos = db.photoDao()
    private val edits = db.editDao()
    private val culling = db.cullingDao()

    /**
     * Writes the sidecars of [photoIds]. [editsOnly]: called because an edit changed (`EditSidecars`), not a rating:
     * photos that cannot take a sidecar are skipped silently, and nothing is written when there is no edit to write.
     * [fields]: the culling fields that changed (a rating tap: [CullingField.RATING]); ignored when [editsOnly].
     */
    suspend fun write(
        photoIds: List<Long>,
        editsOnly: Boolean = false,
        fields: Set<CullingField> = CullingField.ALL,
    ): SidecarOutcome {
        if (editsOnly && !writeEdits()) return SidecarOutcome.NONE
        var outcome = SidecarOutcome.NONE
        for ((sourceId, all) in photos.getAll(photoIds).groupBy { it.sourceId }) {
            val source = db.sourceDao().get(sourceId)?.let { sidecarSource(it, all) }?.takeIf(::canWrite)
            // A hand-off photo has a sidecar only when its sender granted one (Reveal cannot create documents there).
            val group = when {
                source == null -> emptyList()
                editsOnly && source.kind == SourceKind.HANDOFF -> all.filter { it.xmpDocumentId != null }
                else -> all
            }
            if (source == null && !editsOnly) outcome += SidecarOutcome(0, all.size)
            if (source != null && group.isNotEmpty()) {
                outcome += writeGroup(trees.open(source), group, fields.takeUnless { editsOnly })
            }
        }
        return outcome
    }

    /**
     * Where [photos] of [source] keep their sidecar: a Server photo adopted from a hand-off (ADR 0018 "W3/C") still has
     * the sidecar its sender granted (`xmp_document_id`, a document of the hand-off source): it is written there, one
     * open per change, through the hand-off source's tree (its opens are throttled and retried while busy).
     */
    private suspend fun sidecarSource(source: SourceEntity, photos: List<PhotoEntity>): SourceEntity? =
        if (source.kind == SourceKind.SERVER && photos.any { it.xmpDocumentId != null }) {
            db.sourceDao().handoff()
        } else {
            source
        }

    /** Available and writable folders and hand-offs; never the Server source. */
    private fun canWrite(source: SourceEntity): Boolean =
        source.writable && source.state == SourceState.AVAILABLE && source.kind != SourceKind.SERVER

    /** [fields] null = an edit-only write. */
    private suspend fun writeGroup(
        tree: DocumentTree,
        group: List<PhotoEntity>,
        fields: Set<CullingField>?,
    ): SidecarOutcome {
        var outcome = SidecarOutcome.NONE
        for (photo in group) {
            val ok = try {
                writeOne(tree, photo, fields)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Any provider failure (read-only document, vanished folder, malformed sidecar): database only.
                Log.w(TAG, "No sidecar for photo ${photo.id}", e)
                false
            }
            outcome += when (ok) {
                true -> SidecarOutcome(1, 0)
                false -> SidecarOutcome(0, 1)
                null -> SidecarOutcome.NONE
            }
        }
        return outcome
    }

    /** True = written, false = refused (database only), null = nothing to write (an edits-only call with no edit). */
    private suspend fun writeOne(tree: DocumentTree, photo: PhotoEntity, changed: Set<CullingField>?): Boolean? {
        val editsOnly = changed == null
        val fields =
            XmpFields(rating = photo.rating.coerceIn(0, XmpFields.MAX_RATING), label = photo.label, flag = photo.flag)
        val date = now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val develop = if (writeEdits()) developState(photo) else null
        val current = photo.xmpDocumentId?.let { tree.stat(it) }
        // Edits only: nothing without an edit to write, and an unedited photo without a sidecar does not get one just
        // because an edit was reset.
        val nothing = develop == null || (current == null && develop.onlyIfPresent)
        if (editsOnly && nothing) return null
        val existing = current?.let { tree.readBytes(it.documentId, LibraryIndexer.MAX_SIDECAR_BYTES) }
        val bytes = XmpWriter.update(
            existing,
            fields,
            creatorTool,
            date,
            listOf(XmpDevelop.extension(develop)),
            write = changed ?: emptySet(),
        )
        val documentId = store(tree, photo, current, bytes)
        // Remember the written state, so the next walk does not read our own sidecar back as a change, and what the
        // file now says about each culling field, so a later change of the file re-asserts only what changed.
        photos.setSidecar(photo.id, documentId, tree.stat(documentId)?.modified)
        runCatching { XmpReader.readCulling(bytes) }.getOrNull()?.let { held ->
            culling.putSidecar(SidecarCullingEntity(photo.id, held.rating, held.flag?.name, held.label))
        }
        return true
    }

    /** Writes [bytes] to the photo's sidecar document, creating `<stem>.xmp` when needed; returns its document id. */
    private suspend fun store(
        tree: DocumentTree,
        photo: PhotoEntity,
        current: DocumentEntry?,
        bytes: ByteArray,
    ): String {
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
        return documentId
    }

    /**
     * The sidecar of an original-file export (docs/adr/0009 "Original copy"): the photo's own sidecar when it can be
     * read (merged, so another app's fields stay), its culling fields and version [versionId]'s edit ([recipe] when the
     * editor passes the one on screen). Null when the version is gone or its recipe is a newer app's.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    suspend fun exportSidecar(versionId: Long, recipe: Recipe? = null): ByteArray? {
        val photoId = photos.photoIdOfVersion(versionId) ?: return null
        val photo = photos.getAll(listOf(photoId)).firstOrNull() ?: return null
        val develop = developState(photo, versionId, recipe)?.copy(onlyIfPresent = false) ?: return null
        val existing = try {
            val source = db.sourceDao().get(photo.sourceId)?.takeIf { it.state == SourceState.AVAILABLE }
            val id = photo.xmpDocumentId
            if (source == null || id == null) {
                null
            } else {
                val tree = trees.open(source)
                tree.stat(id)?.let { tree.readBytes(it.documentId, LibraryIndexer.MAX_SIDECAR_BYTES) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sidecar of photo $photoId not read for the export", e)
            null
        }
        val fields =
            XmpFields(rating = photo.rating.coerceIn(0, XmpFields.MAX_RATING), label = photo.label, flag = photo.flag)
        val date = now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val extension = listOf(XmpDevelop.extension(develop))
        // A malformed sidecar is never rewritten in place; the export gets a fresh one instead.
        return runCatching { XmpWriter.update(existing, fields, creatorTool, date, extension) }.getOrNull()
            ?: XmpWriter.update(null, fields, creatorTool, date, extension)
    }

    /**
     * The master version's edit (or [versionId]'s, with [override] for its recipe) as the sidecar carries it (ADR
     * 0026): its recipe, the digests of the history steps before it, when it was made. Null when the recipe cannot be
     * read (a newer app's: never written back older).
     */
    @Suppress("ReturnCount")
    private suspend fun developState(
        photo: PhotoEntity,
        version: Long? = null,
        override: Recipe? = null,
    ): DevelopState? {
        val versionId = version ?: photos.firstVersion(photo.id) ?: return null
        val entity = edits.recipe(versionId)
        val recipe = override ?: if (entity == null) {
            Recipe()
        } else {
            runCatching { RecipeCodec.Default.decode(entity.json) }.getOrNull() ?: return null
        }
        val history = edits.history(versionId)
        val cursor = edits.cursor(versionId) ?: history.lastIndex
        val ancestors = history.filter { it.position < cursor }.takeLast(XmpDevelop.MAX_ANCESTORS).mapNotNull { step ->
            runCatching { SidecarRecipe.digest(RecipeCodec.Default.decode(step.recipeJson)) }.getOrNull()
        }
        return DevelopState(
            recipe = recipe,
            frame = PhotoFrame(photo.hasRaw, photo.orientation, photo.width, photo.height),
            ancestors = ancestors,
            edited = entity?.updatedAt ?: now().toInstant().toEpochMilli(),
            device = device,
            onlyIfPresent = recipe.isUnedited(),
        )
    }

    companion object {
        /** `application/octet-stream` keeps the `.xmp` name as given on every provider tried (no extension added). */
        const val SIDECAR_MIME = "application/octet-stream"
        private const val TAG = "RevealSidecars"
    }
}
