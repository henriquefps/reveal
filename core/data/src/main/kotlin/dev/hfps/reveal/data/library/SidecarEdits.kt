package dev.hfps.reveal.data.library

import androidx.room.withTransaction
import dev.hfps.reveal.data.db.HistoryCursorEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.xmp.CameraRaw
import dev.hfps.reveal.data.xmp.CameraRawGeometry
import dev.hfps.reveal.data.xmp.PhotoFrame
import dev.hfps.reveal.data.xmp.SidecarDevelop
import dev.hfps.reveal.data.xmp.SidecarRecipe
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.pipeline.recipe.RecipeDiff

/** What reading a sidecar's edit did to a photo ([SidecarEdits.reconcile]). */
enum class SidecarEditResult {
    /** Nothing to do: no edit in the sidecar, the same edit, or a newer app's edit left alone. */
    NONE,

    /** The sidecar's edit became one "Imported from XMP" step of the master version. */
    IMPORTED,

    /** Both changed since they last agreed: the newer is the master version, the other a conflict copy. */
    CONFLICT,

    /** The sidecar holds an older state of this photo's edit: the photo keeps its edit, the sidecar is rewritten. */
    SIDECAR_BEHIND,
}

/** [SidecarEditResult] and the versions whose edit changed (an open editor reloads them, `RemoteEdits`). */
data class SidecarEditOutcome(val result: SidecarEditResult, val versionIds: List<Long> = emptyList()) {
    companion object {
        val NONE = SidecarEditOutcome(SidecarEditResult.NONE)
    }
}

/**
 * Applies the edit found in a photo's sidecar to its master version (ADR 0026 "Reading"), by the rules culling and
 * sync follow: the newer edit wins and nothing is lost.
 *
 * 1. The incoming recipe: the sidecar's `reveal:` recipe with any `crs:` value another app changed since Reveal wrote
 *    it (a field differing from what Reveal writes for that recipe); without a `reveal:` block, the photo's recipe
 *    with the `crs:` values that differ both from what Reveal would write and from Lightroom's own defaults
 *    (Lightroom writes every field; rating a photo there must not import "sharpening 40").
 * 2. Same recipe: nothing. The photo never edited, or its recipe is one the sidecar's came from
 *    (`reveal:Ancestors`), or the sidecar has only Camera Raw values: the incoming recipe becomes one
 *    "Imported from XMP" step (undo restores the previous one).
 * 3. The sidecar's recipe is one this photo's history already passed: the photo is ahead; the sidecar is rewritten.
 * 4. Otherwise both changed: the newer one (the sidecar's `reveal:Edited` against the recipe's last change) becomes
 *    the master version, the other a conflict copy named after the sidecar's device (sync's chooser resolves it).
 */
class SidecarEdits(
    private val db: RevealDatabase,
    private val edits: EditRepository = EditRepository(db),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val photos = db.photoDao()
    private val editDao = db.editDao()

    /** Reconciles [photoId]'s master version with [develop]; [sidecarName] names a conflict copy without a device. */
    suspend fun reconcile(photoId: Long, develop: SidecarDevelop, sidecarName: String): SidecarEditOutcome =
        if (!develop.hasEdits || develop.reveal?.newer == true) {
            SidecarEditOutcome.NONE
        } else {
            db.withTransaction { reconcileInTransaction(photoId, develop, sidecarName) }
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private suspend fun reconcileInTransaction(
        photoId: Long,
        develop: SidecarDevelop,
        sidecarName: String,
    ): SidecarEditOutcome {
        val photo = photos.get(photoId) ?: return SidecarEditOutcome.NONE
        val main = photos.versions(photoId).firstOrNull() ?: return SidecarEditOutcome.NONE
        val stored = editDao.recipe(main.id)
        val local = if (stored == null) {
            Recipe()
        } else {
            runCatching { RecipeCodec.Default.decode(stored.json) }.getOrNull() ?: return SidecarEditOutcome.NONE
        }
        val frame = PhotoFrame(photo.hasRaw, photo.orientation, photo.width, photo.height)
        val block = develop.reveal
        val base = block?.recipe
        if (block != null && base == null) return SidecarEditOutcome.NONE
        val incoming = if (base != null) {
            foreignChanges(base, develop, frame, againstDefaults = false)
        } else {
            foreignChanges(local, develop, frame, againstDefaults = true)
        }
        if (RecipeDiff.between(local, incoming).isEmpty()) return SidecarEditOutcome.NONE

        val localDigest = SidecarRecipe.digest(local)
        if (block == null || base == null || fastForward(local, localDigest, block.ancestors, base)) {
            importStep(main, incoming, block?.device, block?.edited)
            return SidecarEditOutcome(SidecarEditResult.IMPORTED, listOf(main.id))
        }
        val history = editDao.history(main.id).mapNotNull { step ->
            runCatching { SidecarRecipe.digest(RecipeCodec.Default.decode(step.recipeJson)) }.getOrNull()
        }.toSet() + localDigest
        if (SidecarRecipe.digest(base) in history) {
            // The sidecar is an older state of ours; another app's Camera Raw changes since still come in on top.
            val changed = foreignChanges(local, develop, frame, againstDefaults = false, written = base)
            if (RecipeDiff.between(local, changed).isNotEmpty()) {
                importStep(main, changed, device = null, edited = null)
                return SidecarEditOutcome(SidecarEditResult.IMPORTED, listOf(main.id))
            }
            return SidecarEditOutcome(SidecarEditResult.SIDECAR_BEHIND)
        }
        // Diverged: the newer edit is the master version, the other a conflict copy.
        val device = block.device ?: sidecarName
        val sidecarNewer = (block.edited ?: 0L) > (stored?.updatedAt ?: 0L)
        val copyId = if (sidecarNewer) {
            val copy = cloneAsCopy(main, photoId, device = EditRepository.THIS_DEVICE)
            importStep(main, incoming, block.device, block.edited)
            copy
        } else {
            val copy = newCopy(main, photoId, device)
            edits.applyEach(listOf(copy), HistoryAction.XMP_IMPORT) { incoming }
            editDao.markEdited(copy, device, block.edited)
            copy
        }
        photos.setHasEdit(photoId, editDao.photoHasEdit(photoId))
        return SidecarEditOutcome(SidecarEditResult.CONFLICT, listOf(main.id, copyId))
    }

    /** The photo never edited, or its recipe is the sidecar's or one the sidecar's came from. */
    private fun fastForward(local: Recipe, localDigest: String, ancestors: List<String>, base: Recipe): Boolean =
        local.isUnedited() || localDigest in ancestors || localDigest == SidecarRecipe.digest(base)

    /**
     * [base] with the `crs:` fields of [develop] another app changed: fields that differ from what Reveal writes for
     * [written] (and, [againstDefaults], from Lightroom's defaults too), whole groups at a time, plus the crop and
     * orientation when they differ.
     */
    private fun foreignChanges(
        base: Recipe,
        develop: SidecarDevelop,
        frame: PhotoFrame,
        againstDefaults: Boolean,
        written: Recipe = base,
    ): Recipe {
        val crs = develop.crs
        val expected = CameraRaw.values(written, frame)
        val groups = CameraRaw.fields.filter { field ->
            val value = crs[field.name]
            value != null && field.importable && !CameraRaw.same(field.name, value, expected[field.name]) &&
                !(againstDefaults && CameraRaw.same(field.name, value, CameraRaw.lightroomDefault(field.name, frame)))
        }.map { it.group }.toSet()
        val values = if (groups.isEmpty()) base else CameraRaw.apply(base, crs, groups, frame)
        val cropped = withCrop(values, crs, written, frame, againstDefaults)
        return withOrientation(cropped, develop, written, frame, againstDefaults)
    }

    /** A crop another app made (or removed) since: only when it can be read back in Reveal's terms. */
    private fun withCrop(
        recipe: Recipe,
        crs: Map<String, String>,
        written: Recipe,
        frame: PhotoFrame,
        againstDefaults: Boolean,
    ): Recipe {
        val has = crs["HasCrop"] ?: return recipe
        val crop = CameraRawGeometry.crop(written, frame)
        val changed = crop.isNotEmpty() && !CameraRawGeometry.sameCrop(crs, crop)
        val lightroomDefault = has.trim().equals("False", ignoreCase = true)
        return if (changed && !(againstDefaults && lightroomDefault)) {
            CameraRawGeometry.applyCrop(recipe, crs, frame)
        } else {
            recipe
        }
    }

    /** Quarter turns and flips another app made since (`tiff:Orientation`). */
    private fun withOrientation(
        recipe: Recipe,
        develop: SidecarDevelop,
        written: Recipe,
        frame: PhotoFrame,
        againstDefaults: Boolean,
    ): Recipe {
        val orientation = develop.orientation ?: return recipe
        val changed = orientation != CameraRawGeometry.orientation(written, frame)
        val photoDefault = orientation == frame.orientation
        return if (changed && !(againstDefaults && photoDefault)) {
            CameraRawGeometry.applyOrientation(recipe, orientation, frame)
        } else {
            recipe
        }
    }

    private suspend fun importStep(main: VersionEntity, recipe: Recipe, device: String?, edited: Long?) {
        edits.applyEach(listOf(main.id), HistoryAction.XMP_IMPORT) { recipe }
        if (device != null) editDao.markEdited(main.id, device, edited ?: clock())
    }

    private suspend fun newCopy(main: VersionEntity, photoId: Long, device: String): Long {
        val position = (photos.versions(photoId).maxOfOrNull { it.position } ?: 0) + 1
        return photos.insertVersion(
            VersionEntity(
                photoId = photoId,
                position = position,
                createdAt = clock(),
                copyOf = main.uuid,
                copyDevice = device,
            ),
        )
    }

    /** A conflict copy holding [main]'s current edit: its recipe, history and cursor. */
    private suspend fun cloneAsCopy(main: VersionEntity, photoId: Long, device: String): Long {
        val copy = newCopy(main, photoId, device)
        val steps = editDao.history(main.id)
        for (step in steps) editDao.insertHistory(step.copy(id = 0, versionId = copy))
        val cursor = editDao.cursor(main.id) ?: steps.lastIndex
        editDao.setCursor(HistoryCursorEntity(copy, cursor.coerceIn(-1, steps.lastIndex)))
        editDao.recipe(main.id)?.let { editDao.putRecipe(it.copy(versionId = copy)) }
        val editedAt = main.editedAt ?: editDao.recipe(main.id)?.updatedAt
        editDao.markEdited(copy, main.editDevice ?: EditRepository.THIS_DEVICE, editedAt)
        return copy
    }
}
