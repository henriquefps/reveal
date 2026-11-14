@file:Suppress("ReturnCount", "LoopWithTooManyJumpStatements")

package dev.hfps.reveal.sync.engine

import android.util.Log
import dev.hfps.reveal.data.db.EditCandidate
import dev.hfps.reveal.data.db.EditUploadEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.pipeline.PIPELINE_VERSION
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.sync.api.EditLimits
import dev.hfps.reveal.sync.api.EditMeta
import dev.hfps.reveal.sync.api.ServerEdit
import dev.hfps.reveal.sync.api.ServerInfo
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import java.security.MessageDigest

/** One rendered edited preview: an sRGB, upright JPEG and its size. */
class RenderedEdit(val jpeg: ByteArray, val width: Int, val height: Int)

/** Renders a version's edit for the server (the app: the develop pipeline; tests: a stub). */
fun interface EditRenderer {
    /** [versionId]'s edit of [photoId], long edge at most [maxEdge], at most [maxBytes]; null when it cannot. */
    suspend fun render(photoId: Long, versionId: Long, maxEdge: Int, maxBytes: Long): RenderedEdit?
}

/** What one edited-preview pass did (logs, tests). */
data class EditReport(val uploaded: Int = 0, val updated: Int = 0, val deleted: Int = 0, val refused: Int = 0) {
    val progress: Int get() = uploaded + updated + deleted + refused
}

/**
 * Reveal's edited previews on hoard-server (Hoard ADR 0020 "Client rules (Reveal)", Reveal ADR 0027 "Edited previews"):
 * every edited version (the main one and virtual copies, never a sync conflict copy) is rendered through the develop
 * pipeline at the server's `maxEdge` and uploaded to its item (`PUT /v1/items/{itemId}/edits/{versionId}`), named by
 * the recipe digest so an unchanged edit costs nothing; a change of only the label, the primary flag or the time is a
 * header-only PUT; a deleted or reset version is deleted there. A version is uploaded once its edit settled
 * ([SETTLE_MS] after the last change, its record pushed). State per version in `edit_uploads`; everything a pass did
 * not finish is found again by the next one (resumable). Runs in the blob job (network and battery rules of uploads).
 */
class EditPreviews(
    private val db: RevealDatabase,
    private val renderer: EditRenderer?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.editUploadDao()

    /** One pass with at most [maxRenders] images rendered; nothing when the server does not list `edits`. */
    suspend fun run(api: SyncApi, maxRenders: Int, info: ServerInfo = api.server()): EditReport {
        if (!info.has(FEATURE)) return EditReport()
        val limits = info.edits ?: EditLimits()
        val candidates = dao.candidates()
        val wanted = wanted(candidates)
        val rows = dao.all().associateBy { it.versionUuid }
        var report = deletions(api, candidates.map { it.uuid }.toSet(), rows.values)
        // Versions whose server state is not what this device wants: the lookup names their item and what it holds.
        val stale = wanted.values.filter { want -> rows[want.uuid].let { it == null || !want.matches(it) } }
            .filter { it.refusedKey != rows[it.uuid]?.refused }
        if (stale.isEmpty()) return report
        val items = lookup(api, stale.mapNotNull { it.fingerprint }.distinct())
        var renders = 0
        for (want in stale.sortedWith(compareByDescending<Wanted> { it.primary }.thenByDescending { it.editedAt })) {
            val (itemId, held) = items[want.fingerprint] ?: continue
            val current = held.firstOrNull { it.versionId == want.uuid }
            val needsImage = current == null || current.recipeDigest != want.digest
            if (needsImage && renders >= maxRenders) continue
            if (needsImage) renders++
            report += put(api, want, itemId, needsImage, limits)
        }
        return report
    }

    /** One edited version as the server should hold it. */
    private data class Wanted(
        val uuid: String,
        val versionId: Long,
        val photoId: Long,
        val fingerprint: String?,
        val digest: String,
        val label: String,
        val primary: Boolean,
        val virtualCopy: Boolean,
        val editedAt: Long,
    ) {
        val refusedKey: String get() = "$digest|$editedAt"

        fun matches(row: EditUploadEntity) = row.digest == digest && row.label == label && row.primary == primary &&
            row.virtualCopy == virtualCopy && row.editedAt == editedAt && row.itemId != null

        fun meta() = EditMeta(digest, editedAt, label, virtualCopy, primary)
    }

    /** The settled, pushed, edited versions, with the photo's primary (Hoard ADR 0020 client rules 2 and 5). */
    private fun wanted(candidates: List<EditCandidate>): Map<String, Wanted> {
        val now = clock()
        val byPhoto = candidates.groupBy { it.photoId }
        val out = LinkedHashMap<String, Wanted>()
        for ((_, versions) in byPhoto) {
            // The grid shows the main version first; without an edit there, the first edited copy.
            val primary = versions.minByOrNull { it.position }?.uuid
            for (version in versions) {
                if (!settled(version, now)) continue
                out[version.uuid] = Wanted(
                    uuid = version.uuid,
                    versionId = version.versionId,
                    photoId = version.photoId,
                    fingerprint = version.fingerprint,
                    digest = digest(version.recipeJson),
                    label = if (version.position == 0) "" else version.name.orEmpty().take(MAX_LABEL),
                    primary = version.uuid == primary,
                    virtualCopy = version.position > 0,
                    editedAt = (version.recordAt ?: version.recipeAt).coerceAtLeast(1),
                )
            }
        }
        return out
    }

    /** The edit settled: no change for [SETTLE_MS], and its record is on the server (rule 5 "Debounce"). */
    private fun settled(version: EditCandidate, now: Long) =
        version.recipeAt <= now - SETTLE_MS && version.recordDirty == false

    /**
     * Server edits of versions this device deleted (a tombstone) or reset (still here, no longer edited): deleted
     * there (rule 8). A version gone without a tombstone (its folder removed) only leaves this device's state.
     */
    private suspend fun deletions(api: SyncApi, edited: Set<String>, rows: Collection<EditUploadEntity>): EditReport {
        var deleted = 0
        for (row in rows) {
            // Still edited (maybe not settled yet): it stays.
            if (row.versionUuid in edited) continue
            val exists = dao.versionExists(row.versionUuid) > 0
            val reset = exists
            val tombstone = dao.tombstone(row.versionUuid) > 0
            val itemId = row.itemId
            when {
                itemId == null || row.digest == null -> if (!exists) dao.forget(row.versionUuid)

                reset || tombstone -> {
                    api.deleteEdit(itemId, row.versionUuid)
                    dao.forget(row.versionUuid)
                    deleted++
                }

                !exists -> dao.forget(row.versionUuid)
            }
        }
        return EditReport(deleted = deleted)
    }

    /** Main fingerprint -> its item and the edits it holds (rule 3); a photo the server does not index is left out. */
    private fun lookup(api: SyncApi, fingerprints: List<String>): Map<String, Pair<String, List<ServerEdit>>> {
        val out = HashMap<String, Pair<String, List<ServerEdit>>>()
        for (part in fingerprints.chunked(MAX_LOOKUP)) {
            val answer = api.itemsLookup(part)
            val items = answer.items.associateBy { it.id }
            for (match in answer.matches) {
                if (match.fingerprint in out) continue
                out[match.fingerprint] = match.itemId to items[match.itemId]?.edits.orEmpty()
            }
        }
        return out
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun put(
        api: SyncApi,
        want: Wanted,
        itemId: String,
        image: Boolean,
        limits: EditLimits,
    ): EditReport {
        val bytes = if (image) {
            val rendered = renderer?.render(want.photoId, want.versionId, limits.maxEdge, limits.maxBytes)
                ?: return EditReport()
            rendered.jpeg
        } else {
            null
        }
        val answer = try {
            api.putEdit(itemId, want.uuid, want.meta(), bytes)
        } catch (e: SyncHttpException) {
            val reason = e.details?.get("reason")?.toString()?.trim('"')
            return when {
                e.transient || e.unauthorized -> throw e

                // The server lost the image (a re-created item): send it again with the image.
                reason == "image_required" && !image -> put(api, want, itemId, image = true, limits)

                // The item is gone: looked up again next pass.
                e.status == HTTP_NOT_FOUND -> {
                    dao.forget(want.uuid)
                    EditReport()
                }

                // Another device's later edit of this version, a bad render, too many edits, a video: not again.
                else -> {
                    Log.w(TAG, "The server refused an edited preview: ${e.code} $reason")
                    dao.put(row(want, itemId, digest = null, refused = want.refusedKey))
                    EditReport(refused = 1)
                }
            }
        }
        dao.put(row(want, answer.itemId, digest = answer.edit?.recipeDigest ?: want.digest, refused = null))
        return if (image) EditReport(uploaded = 1) else EditReport(updated = 1)
    }

    private fun row(want: Wanted, itemId: String, digest: String?, refused: String?) = EditUploadEntity(
        versionUuid = want.uuid,
        photoId = want.photoId,
        itemId = itemId,
        digest = digest,
        label = want.label,
        primary = want.primary,
        virtualCopy = want.virtualCopy,
        editedAt = want.editedAt,
        refused = refused,
        updatedAt = clock(),
    )

    /** Forgets what the server holds (unpair, another server). */
    suspend fun forget() = dao.clear()

    private operator fun EditReport.plus(other: EditReport) = EditReport(
        uploaded + other.uploaded,
        updated + other.updated,
        deleted + other.deleted,
        refused + other.refused,
    )

    companion object {
        private const val TAG = "RevealSync"
        const val FEATURE = "edits"

        /** An edit is uploaded this long after its last change (not on every slider step). */
        const val SETTLE_MS = 60_000L

        /** Bumped when this client's render of an edit changes (its pixels for the same recipe). */
        const val RENDER_VERSION = 1
        private const val MAX_LABEL = 64
        private const val MAX_LOOKUP = 1000
        private const val HTTP_NOT_FOUND = 404

        /**
         * The recipe digest (Hoard ADR 0020 rule 5): `r<pipeline>.<render>:` + SHA-256 of the recipe JSON as the
         * database stores it (Reveal's codec output); equal for the same edit on every device, different when the
         * recipe or the rendering changes.
         */
        fun digest(recipeJson: String): String {
            // Re-encoded through the codec: the same recipe gives the same bytes whichever device stored it.
            val canonical = runCatching { RecipeCodec.Default.encode(RecipeCodec.Default.decode(recipeJson)) }
                .getOrDefault(recipeJson)
            val sha = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return "r$PIPELINE_VERSION.$RENDER_VERSION:" + sha.joinToString("") { "%02x".format(it) }
        }
    }
}
