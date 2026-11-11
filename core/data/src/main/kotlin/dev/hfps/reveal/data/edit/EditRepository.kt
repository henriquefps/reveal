package dev.hfps.reveal.data.edit

import androidx.room.withTransaction
import dev.hfps.reveal.data.db.HistoryCursorEntity
import dev.hfps.reveal.data.db.HistoryEntity
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.RecipeEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.pipeline.recipe.EditHistory
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.HistoryEntry
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.RecipePaste
import dev.hfps.reveal.pipeline.recipe.StepLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One persisted history step: its position and its label ("Exposure +0.70", "Paste edits", ...). */
data class StoredStep(val position: Int, val label: StepLabel, val createdAt: Long) {
    /** The field changes of the step. */
    val labels: List<HistoryStep> get() = label.changes
}

/** A version's history: steps in order and the cursor (-1 = before the first step, the version's base). */
data class HistoryState(val steps: List<StoredStep>, val cursor: Int) {
    val canUndo: Boolean get() = cursor >= 0
    val canRedo: Boolean get() = cursor < steps.lastIndex
}

/** A change to a version's persisted history, in the order the editor made it ([HistoryWriter]). */
sealed interface HistoryOp {
    val versionId: Long

    /** A committed step after the cursor (the redo steps above it are dropped). */
    data class Commit(override val versionId: Long, val recipe: Recipe, val label: StepLabel) : HistoryOp

    /** Undo, redo or a jump: the cursor moves to [position] (-1 = the base). */
    data class Move(override val versionId: Long, val position: Int) : HistoryOp
}

/** Copied edits: the whole recipe of the source photo; the paste chooser picks the groups. */
data class EditClipboard(val recipe: Recipe) {
    /** [target] with [groups] taken from the copied recipe. */
    fun pasteOnto(target: Recipe, groups: Set<RecipeGroup>): Recipe = RecipePaste.paste(recipe, target, groups)
}

/**
 * Recipes and history of versions (ADR 0003 "Committed steps", ADR 0006 "History"). The editor keeps the working
 * recipe and an in-memory [EditHistory] and sends each committed change through a [HistoryWriter], which lands here
 * in [apply]; [load] rebuilds the history when the photo is opened again. Everything survives restarts. Writes keep
 * `photos.has_edit` and `recipes.updated_at` in step, which drive the grid's "edited" badge, filters and the edited
 * thumbnail. Also the copy/paste clipboard (in `meta`, so it survives restarts) and "Previous".
 */
class EditRepository(private val db: RevealDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val edits = db.editDao()
    private val photos = db.photoDao()
    private val meta = db.metaDao()

    /** The committed recipe of a version (the default recipe when it was never edited). */
    suspend fun recipe(versionId: Long): Recipe =
        edits.recipe(versionId)?.let { RecipeCodec.Default.decode(it.json) } ?: Recipe()

    suspend fun history(versionId: Long): HistoryState {
        val steps = edits.history(versionId).map { StoredStep(it.position, decodeLabel(it.labelsJson), it.createdAt) }
        return HistoryState(steps, edits.cursor(versionId) ?: (steps.size - 1))
    }

    /**
     * The full history of a version (every step's snapshot, labels and the cursor), as the editor shows it. Steps a
     * newer app wrote that this one cannot decode end the history there (never a crash, never a wrong recipe).
     */
    suspend fun load(versionId: Long): EditHistory = db.withTransaction {
        val rows = edits.history(versionId)
        val entries = ArrayList<HistoryEntry>(rows.size)
        for (row in rows) {
            if (row.position != entries.size) break
            val recipe = runCatching { RecipeCodec.Default.decode(row.recipeJson) }.getOrNull() ?: break
            entries += HistoryEntry(recipe, decodeLabel(row.labelsJson))
        }
        val cursor = (edits.cursor(versionId) ?: entries.lastIndex).coerceIn(-1, entries.lastIndex)
        EditHistory(entries, cursor)
    }

    /**
     * Commits [recipe] as a new step after the cursor (dropping the redo steps above it), stores it as the version's
     * recipe, and moves the cursor to it. [labels] usually come from `RecipeDiff.between(previous, recipe)`; an empty
     * list stores nothing.
     */
    suspend fun commit(versionId: Long, recipe: Recipe, labels: List<HistoryStep>): HistoryState =
        commit(versionId, recipe, StepLabel(labels))

    suspend fun commit(versionId: Long, recipe: Recipe, label: StepLabel): HistoryState {
        if (label.changes.isEmpty()) return history(versionId)
        db.withTransaction { commitStep(versionId, recipe, label) }
        return history(versionId)
    }

    /** Applies the editor's committed changes in order, in one transaction (a crash leaves all or none). */
    suspend fun apply(ops: List<HistoryOp>) {
        if (ops.isEmpty()) return
        db.withTransaction {
            for (op in ops) {
                when (op) {
                    is HistoryOp.Commit -> if (op.label.changes.isNotEmpty()) commitStep(op.versionId, op.recipe, op.label)
                    is HistoryOp.Move -> moveStep(op.versionId) { op.position }
                }
            }
        }
    }

    suspend fun undo(versionId: Long): Recipe? = db.withTransaction { moveStep(versionId) { it - 1 } }

    suspend fun redo(versionId: Long): Recipe? = db.withTransaction { moveStep(versionId) { it + 1 } }

    /** Jumps to the step at [position] (-1 = the base); the steps after it stay available for redo. */
    suspend fun goTo(versionId: Long, position: Int): Recipe? = db.withTransaction { moveStep(versionId) { position } }

    /**
     * Pastes [groups] of [clipboard] onto every version of [versionIds] (the library's selection): one history step
     * per target ([action], [HistoryAction.PASTE] by default) on top of its current recipe, in one transaction.
     * Targets the paste does not change get no step. Returns how many versions changed.
     */
    suspend fun paste(
        versionIds: Collection<Long>,
        clipboard: EditClipboard,
        groups: Set<RecipeGroup>,
        action: HistoryAction = HistoryAction.PASTE,
    ): Int = db.withTransaction {
        var changed = 0
        for (versionId in versionIds) {
            val current = recipe(versionId)
            val pasted = clipboard.pasteOnto(current, groups)
            val changes = RecipeDiff.between(current, pasted)
            if (changes.isNotEmpty()) {
                commitStep(versionId, pasted, StepLabel(changes, action, groupsInOrder(groups)))
                changed++
            }
        }
        changed
    }

    /**
     * "Previous": the recipe of the most recently edited version other than [excludeVersionId] (null when no other
     * photo has an edit). [excludeVersionId] -1 excludes nothing (a photo opened from another app).
     */
    suspend fun previous(excludeVersionId: Long): Recipe? =
        edits.lastEdited(excludeVersionId)?.let { runCatching { RecipeCodec.Default.decode(it.json) }.getOrNull() }

    // ------------------------------------------------------------------ clipboard

    /** The copied edits, or null; survives restarts. */
    fun clipboard(): Flow<EditClipboard?> =
        meta.observe(KEY_CLIPBOARD).map { it?.let(::decodeClipboard) }.distinctUntilChanged()

    suspend fun currentClipboard(): EditClipboard? = meta.get(KEY_CLIPBOARD)?.let(::decodeClipboard)

    /** Copies [recipe] (the whole edit; the paste chooser picks the groups). */
    suspend fun copy(recipe: Recipe) {
        val clip = StoredClipboard(RecipeCodec.Default.encode(recipe))
        meta.put(MetaEntity(KEY_CLIPBOARD, json.encodeToString(StoredClipboard.serializer(), clip)))
    }

    /** The groups the paste chooser starts with: the last choice, else everything but geometry. */
    suspend fun pasteGroups(): Set<RecipeGroup> = meta.get(KEY_PASTE_GROUPS)?.let(::decodeGroups)
        ?: RecipePaste.DEFAULT_GROUPS

    /** Remembers the paste chooser's choice for next time. */
    suspend fun setPasteGroups(groups: Set<RecipeGroup>) {
        meta.put(MetaEntity(KEY_PASTE_GROUPS, groupsInOrder(groups).joinToString(",") { it.name }))
    }

    /** The version ids of [photoIds] (M1: one version per photo), in the same order; unknown photos are skipped. */
    suspend fun versionsOf(photoIds: Collection<Long>): List<Long> = photoIds.mapNotNull { photos.firstVersion(it) }

    // ------------------------------------------------------------------ internals (inside a transaction)

    private suspend fun commitStep(versionId: Long, recipe: Recipe, label: StepLabel) {
        val cursor = edits.cursor(versionId) ?: (edits.history(versionId).size - 1)
        edits.truncateHistoryAfter(versionId, cursor)
        val position = cursor + 1
        val json = RecipeCodec.Default.encode(recipe)
        edits.insertHistory(
            HistoryEntity(
                versionId = versionId,
                position = position,
                recipeJson = json,
                labelsJson = encodeLabel(label),
                createdAt = clock(),
            ),
        )
        edits.setCursor(HistoryCursorEntity(versionId, position))
        store(versionId, recipe, json)
    }

    private suspend fun moveStep(versionId: Long, target: (Int) -> Int): Recipe? {
        val count = edits.history(versionId).size
        val cursor = edits.cursor(versionId) ?: (count - 1)
        val next = target(cursor)
        if (next == cursor || next < -1 || next >= count) return null
        val recipe = if (next == -1) {
            Recipe()
        } else {
            RecipeCodec.Default.decode(checkNotNull(edits.historyStep(versionId, next)).recipeJson)
        }
        edits.setCursor(HistoryCursorEntity(versionId, next))
        store(versionId, recipe, RecipeCodec.Default.encode(recipe))
        return recipe
    }

    private suspend fun store(versionId: Long, recipe: Recipe, json: String) {
        edits.putRecipe(RecipeEntity(versionId, json, isDefault = recipe.isDefault(), updatedAt = clock()))
        val photoId = photos.photoIdOfVersion(versionId) ?: return
        photos.setHasEdit(photoId, edits.photoHasEdit(photoId))
    }

    // ------------------------------------------------------------------ JSON

    /**
     * One element of `history.labels_json`: a field change (`{"field":"EXPOSURE","value":"+0.70"}`) or the step's
     * action (`{"action":"PASTE","groups":["LIGHT","COLOUR"]}`, first when present).
     */
    @Serializable
    private data class Label(
        val field: String? = null,
        val value: String? = null,
        val action: String? = null,
        val groups: List<String>? = null,
    )

    @Serializable
    private data class StoredClipboard(val recipe: String)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val labelList = ListSerializer(Label.serializer())

    private fun encodeLabel(label: StepLabel): String {
        val action = label.action?.let { Label(action = it.name, groups = label.groups.map(RecipeGroup::name)) }
        return json.encodeToString(labelList, listOfNotNull(action) + label.changes.map { Label(it.field.name, it.value) })
    }

    /** Fields and actions this app does not know (a newer app wrote them) are skipped, never a crash. */
    private fun decodeLabel(text: String): StepLabel = runCatching {
        val items = json.decodeFromString(labelList, text)
        val actionItem = items.firstOrNull { it.action != null }
        val changes = items.mapNotNull { label ->
            HistoryField.entries.firstOrNull { it.name == label.field }?.let { HistoryStep(it, label.value) }
        }
        StepLabel(
            changes = changes,
            action = actionItem?.action?.let { name -> HistoryAction.entries.firstOrNull { it.name == name } },
            groups = actionItem?.groups.orEmpty().mapNotNull { name -> RecipeGroup.entries.firstOrNull { it.name == name } },
        )
    }.getOrDefault(StepLabel(emptyList()))

    private fun decodeClipboard(text: String): EditClipboard? = runCatching {
        val stored = json.decodeFromString(StoredClipboard.serializer(), text)
        EditClipboard(RecipeCodec.Default.decode(stored.recipe))
    }.getOrNull()

    private fun decodeGroups(text: String): Set<RecipeGroup> =
        text.split(',').mapNotNullTo(LinkedHashSet()) { name -> RecipeGroup.entries.firstOrNull { it.name == name } }

    private fun groupsInOrder(groups: Set<RecipeGroup>): List<RecipeGroup> = RecipeGroup.entries.filter { it in groups }

    private companion object {
        const val KEY_CLIPBOARD = "edits.clipboard"
        const val KEY_PASTE_GROUPS = "edits.pasteGroups"
    }
}
