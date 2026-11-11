package dev.hfps.reveal.data.edit

import androidx.room.withTransaction
import dev.hfps.reveal.data.db.HistoryCursorEntity
import dev.hfps.reveal.data.db.HistoryEntity
import dev.hfps.reveal.data.db.RecipeEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One persisted history step: its position and its labels ("Exposure +0.70", ...). */
data class StoredStep(val position: Int, val labels: List<HistoryStep>, val createdAt: Long)

/** A version's history: steps in order and the cursor (-1 = before the first step, the version's base). */
data class HistoryState(val steps: List<StoredStep>, val cursor: Int) {
    val canUndo: Boolean get() = cursor >= 0
    val canRedo: Boolean get() = cursor < steps.lastIndex
}

/**
 * Recipes and history of versions (ADR 0003 "Committed steps", ADR 0006 "History"). The editor keeps the working
 * recipe in memory while a slider moves and calls [commit] once per committed change; undo/redo move the cursor
 * and return the recipe to show. Everything survives restarts. Writes keep `photos.has_edit` in step, which drives
 * the grid's "edited" badge and filters.
 */
class EditRepository(private val db: RevealDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val edits = db.editDao()
    private val photos = db.photoDao()

    /** The committed recipe of a version (the default recipe when it was never edited). */
    suspend fun recipe(versionId: Long): Recipe =
        edits.recipe(versionId)?.let { RecipeCodec.Default.decode(it.json) } ?: Recipe()

    suspend fun history(versionId: Long): HistoryState {
        val steps = edits.history(versionId).map { StoredStep(it.position, decodeLabels(it.labelsJson), it.createdAt) }
        return HistoryState(steps, edits.cursor(versionId) ?: (steps.size - 1))
    }

    /**
     * Commits [recipe] as a new step after the cursor (dropping the redo steps above it), stores it as the version's
     * recipe, and moves the cursor to it. [labels] usually come from `RecipeDiff.between(previous, recipe)`; an empty
     * list stores nothing.
     */
    suspend fun commit(versionId: Long, recipe: Recipe, labels: List<HistoryStep>): HistoryState {
        if (labels.isEmpty()) return history(versionId)
        db.withTransaction {
            val cursor = edits.cursor(versionId) ?: (edits.history(versionId).size - 1)
            edits.truncateHistoryAfter(versionId, cursor)
            val position = cursor + 1
            val json = RecipeCodec.Default.encode(recipe)
            edits.insertHistory(
                HistoryEntity(
                    versionId = versionId,
                    position = position,
                    recipeJson = json,
                    labelsJson = encodeLabels(labels),
                    createdAt = clock(),
                ),
            )
            edits.setCursor(HistoryCursorEntity(versionId, position))
            store(versionId, recipe, json)
        }
        return history(versionId)
    }

    suspend fun undo(versionId: Long): Recipe? = moveTo(versionId) { it - 1 }

    suspend fun redo(versionId: Long): Recipe? = moveTo(versionId) { it + 1 }

    /** Jumps to the step at [position] (-1 = the base); the steps after it stay available for redo. */
    suspend fun goTo(versionId: Long, position: Int): Recipe? = moveTo(versionId) { position }

    private suspend fun moveTo(versionId: Long, target: (Int) -> Int): Recipe? = db.withTransaction {
        val count = edits.history(versionId).size
        val cursor = edits.cursor(versionId) ?: (count - 1)
        val next = target(cursor)
        if (next == cursor || next < -1 || next >= count) return@withTransaction null
        val recipe = if (next ==
            -1
        ) {
            Recipe()
        } else {
            RecipeCodec.Default.decode(checkNotNull(edits.historyStep(versionId, next)).recipeJson)
        }
        edits.setCursor(HistoryCursorEntity(versionId, next))
        store(versionId, recipe, RecipeCodec.Default.encode(recipe))
        recipe
    }

    private suspend fun store(versionId: Long, recipe: Recipe, json: String) {
        edits.putRecipe(RecipeEntity(versionId, json, isDefault = recipe.isDefault(), updatedAt = clock()))
        val photoId = photos.photoIdOfVersion(versionId) ?: return
        photos.setHasEdit(photoId, edits.photoHasEdit(photoId))
    }

    @Serializable
    private data class Label(val field: String, val value: String? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val labelList = ListSerializer(Label.serializer())

    private fun encodeLabels(labels: List<HistoryStep>): String =
        json.encodeToString(labelList, labels.map { Label(it.field.name, it.value) })

    /** Labels of fields this app does not know (a newer app wrote them) are skipped, never a crash. */
    private fun decodeLabels(text: String): List<HistoryStep> = runCatching {
        json.decodeFromString(labelList, text).mapNotNull { label ->
            HistoryField.entries.firstOrNull { it.name == label.field }?.let { HistoryStep(it, label.value) }
        }
    }.getOrDefault(emptyList())
}
