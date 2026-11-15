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
import dev.hfps.reveal.data.xmp.XmpException
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What an XMP write did: [written] sidecars, [databaseOnly] photos whose sidecar could not be written (read-only
 * folder, hand-off or Server photo, a provider that refused), [malformed] photos whose existing sidecar is not
 * well-formed XMP and was left exactly as it was.
 */
data class SidecarOutcome(val written: Int, val databaseOnly: Int, val malformed: Int = 0) {
    operator fun plus(other: SidecarOutcome) =
        SidecarOutcome(written + other.written, databaseOnly + other.databaseOnly, malformed + other.malformed)

    companion object {
        val NONE = SidecarOutcome(0, 0)
    }
}

/**
 * Writes the library fields (rating, label, pick flag) of photos to XMP sidecars (ADR 0006, "XMP") and, while
 * [writeEdits] is on, the master version's edit (ADR 0026: the `reveal:` recipe, the Camera Raw overlap and the
 * orientation, [XmpDevelop]): the existing sidecar is merged ([XmpWriter]), else `<stem>.xmp` is created next to the
 * RAW (one sidecar per pair, as Lightroom and Hoard expect).
 *
 * Since 0.9.0 nothing calls this on its own (ADR 0026 "Automatic writes removed"): the only caller is Export XMP
 * (`XmpExport`), an explicit user action. [write] goes next to the photos: only for folder photos whose source is
 * available and has a write grant (or the one-off tree passed for it); hand-off and Server photos never, a malformed
 * sidecar is left alone. [writeInto] puts flat `<stem>.xmp` files into a folder the user chose.
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
     * Writes the sidecars of [photoIds] next to the photos. [editsOnly]: nothing is written for photos without an
     * edit to write. [fields]: the culling fields to write; ignored when [editsOnly]. [oneOff]: trees to use for
     * sources without a write grant of their own (source id -> a tree the user just picked, never persisted).
     */
    suspend fun write(
        photoIds: List<Long>,
        editsOnly: Boolean = false,
        fields: Set<CullingField> = CullingField.ALL,
        oneOff: Map<Long, DocumentTree> = emptyMap(),
    ): SidecarOutcome {
        if (editsOnly && !writeEdits()) return SidecarOutcome.NONE
        var outcome = SidecarOutcome.NONE
        for ((sourceId, all) in photos.getAll(photoIds).groupBy { it.sourceId }) {
            val source = db.sourceDao().get(sourceId)
            val tree = source?.takeIf { canWrite(it, sourceId in oneOff) }?.let { oneOff[sourceId] ?: trees.open(it) }
            if (tree == null) {
                if (!editsOnly) outcome += SidecarOutcome(0, all.size)
            } else {
                outcome += writeGroup(tree, all, fields.takeUnless { editsOnly })
            }
        }
        return outcome
    }

    /** Available folders with a write grant (or a one-off tree); never a hand-off or the Server source. */
    private fun canWrite(source: SourceEntity, oneOff: Boolean): Boolean =
        (source.writable || oneOff) && source.state == SourceState.AVAILABLE && source.kind == SourceKind.FOLDER

    /**
     * Writes one flat `<stem>.xmp` per photo of [photoIds] into [folderId] of [destination] (a folder the user picked):
     * the photo's own sidecar, when it can be read, is the base that is merged (it stays as it was; a malformed one is
     * never touched and the chosen folder gets a fresh sidecar instead); the provider de-duplicates a name that is
     * taken, as the image export's. Works for every kind of photo.
     */
    suspend fun writeInto(photoIds: List<Long>, destination: DocumentTree, folderId: String): SidecarOutcome {
        var outcome = SidecarOutcome.NONE
        for (photo in photos.getAll(photoIds)) outcome += writeOneInto(photo.id, destination, folderId).outcome
        return outcome
    }

    /** What one photo's write into a chosen folder did, and the document it wrote (null when nothing was written). */
    data class FolderWrite(val outcome: SidecarOutcome, val documentId: String? = null)

    /**
     * [writeInto] for one photo. [ownDocument]: the document this same export already created for the photo (a job
     * resumed after a kill): it is overwritten instead of creating a `(1)` copy. [onCreated] gets a new document's id
     * before any byte is written, so the caller can record it first.
     */
    suspend fun writeOneInto(
        photoId: Long,
        destination: DocumentTree,
        folderId: String,
        ownDocument: String? = null,
        onCreated: suspend (String) -> Unit = {},
    ): FolderWrite {
        val photo = photos.getAll(listOf(photoId)).firstOrNull() ?: return FolderWrite(SidecarOutcome(0, 1))
        return try {
            val own = readOwn(photo)
            val bytes = try {
                merged(photo, own, CullingField.ALL)
            } catch (e: XmpException) {
                // The photo's own sidecar is malformed: left exactly as it is, the chosen folder gets a fresh one.
                Log.w(TAG, "Sidecar of photo ${photo.id} is malformed, a fresh one is exported", e)
                merged(photo, null, CullingField.ALL)
            }
            val reused = ownDocument?.takeIf { runCatching { destination.stat(it) }.getOrNull() != null }
            val documentId = reused
                ?: destination.create(folderId, "${stemOf(photo)}${SidecarNames.SUFFIX}", SIDECAR_MIME).documentId
            if (reused == null) {
                // A document created here and not filled (a Cancel, a provider failure) is deleted again.
                fillingCreated(destination, documentId) {
                    onCreated(documentId)
                    destination.writeBytes(documentId, bytes)
                }
            } else {
                destination.writeBytes(documentId, bytes)
            }
            FolderWrite(SidecarOutcome(1, 0), documentId)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "No sidecar exported for photo ${photo.id}", e)
            FolderWrite(SidecarOutcome(0, 1))
        }
    }

    /** The photo's own sidecar's bytes (read only), or null when it has none or cannot be reached. */
    private suspend fun readOwn(photo: PhotoEntity): ByteArray? {
        val id = photo.xmpDocumentId
        val source = db.sourceDao().get(photo.sourceId)?.takeIf { it.state == SourceState.AVAILABLE }
        if (id == null || source == null) return null
        return runCatching {
            val tree = trees.open(source)
            tree.stat(id)?.let { tree.readBytes(it.documentId, LibraryIndexer.MAX_SIDECAR_BYTES) }
        }.getOrNull()
    }

    private suspend fun stemOf(photo: PhotoEntity): String {
        val files = photos.files(photo.id)
        val main = files.firstOrNull { it.role == FileRole.RAW } ?: files.firstOrNull { it.role == FileRole.RENDERED }
        return ImageFormats.stem(main?.name ?: photo.name)
    }

    /** [existing] merged with the photo's fields and edit. Throws [XmpException] when [existing] is malformed. */
    private suspend fun merged(photo: PhotoEntity, existing: ByteArray?, changed: Set<CullingField>): ByteArray {
        val fields =
            XmpFields(rating = photo.rating.coerceIn(0, XmpFields.MAX_RATING), label = photo.label, flag = photo.flag)
        val date = now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val develop = if (writeEdits()) developState(photo) else null
        return XmpWriter.update(existing, fields, creatorTool, date, listOf(XmpDevelop.extension(develop)), changed)
    }

    /** [fields] null = an edit-only write. */
    private suspend fun writeGroup(
        tree: DocumentTree,
        group: List<PhotoEntity>,
        fields: Set<CullingField>?,
    ): SidecarOutcome {
        var outcome = SidecarOutcome.NONE
        for (photo in group) {
            outcome += try {
                when (writeOne(tree, photo, fields)) {
                    true -> SidecarOutcome(1, 0)
                    false -> SidecarOutcome(0, 1)
                    null -> SidecarOutcome.NONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XmpException) {
                // A sidecar that is not well-formed XMP is never rewritten.
                Log.w(TAG, "Sidecar of photo ${photo.id} is malformed, left as it is", e)
                SidecarOutcome(0, 0, 1)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Any provider failure (read-only document, vanished folder): not written.
                Log.w(TAG, "No sidecar for photo ${photo.id}", e)
                SidecarOutcome(0, 1)
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
        // An empty document is one this write created and was interrupted before filling: no base to merge.
        val existing = current?.let { tree.readBytes(it.documentId, LibraryIndexer.MAX_SIDECAR_BYTES) }
            ?.takeIf { it.isNotEmpty() }
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
            if (created === hidden) {
                tree.writeBytes(created.documentId, bytes)
            } else {
                // A write cancelled or refused after the create leaves no empty `<stem>.xmp`: the document is deleted
                // and the photo's record put back as it was.
                val restore: suspend () -> Unit = {
                    photos.setSidecar(photo.id, photo.xmpDocumentId, photo.xmpModified)
                }
                fillingCreated(tree, created.documentId, restore) {
                    // Recorded before any byte is written: a write killed here and run again overwrites this
                    // document instead of creating a `(1)` copy next to it.
                    photos.setSidecar(photo.id, created.documentId, null)
                    tree.writeBytes(created.documentId, bytes)
                }
            }
            created.documentId
        }
        return documentId
    }

    /**
     * Runs [fill] on [documentId], a document just created in [tree]; when it fails or is cancelled the document is
     * deleted (never left empty) and [restore] puts back what [fill] recorded, then the failure goes on. A document the
     * provider would not delete keeps the record, so the next write fills it instead of creating a `(1)` copy.
     */
    private suspend fun fillingCreated(
        tree: DocumentTree,
        documentId: String,
        restore: suspend () -> Unit = {},
        fill: suspend () -> Unit,
    ) {
        try {
            fill()
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            withContext(NonCancellable) {
                if (runCatching { tree.delete(documentId) }.getOrDefault(false)) {
                    runCatching { restore() }
                } else {
                    Log.w(TAG, "Unfinished sidecar $documentId could not be deleted")
                }
            }
            throw e
        }
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
