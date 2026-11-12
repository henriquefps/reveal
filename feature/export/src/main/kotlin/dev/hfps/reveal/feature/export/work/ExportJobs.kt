package dev.hfps.reveal.feature.export.work

import android.content.Context
import dev.hfps.reveal.feature.export.model.ExportOptions
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One photo of a job: a library version (recipe from the database) or a URI with the editor's recipe. */
@Serializable
data class ExportJobItem(
    val versionId: Long? = null,
    val uri: String? = null,
    val name: String? = null,
    /** The recipe to develop (JSON, `RecipeCodec`); null = the version's committed recipe. */
    val recipeJson: String? = null,
) {
    init {
        require((versionId == null) != (uri == null)) { "an item is a version or a URI" }
    }
}

/** What a run exports: the items in order, the options, and the first number of the "sequence" template. */
@Serializable
data class ExportJob(
    val id: String,
    val items: List<ExportJobItem>,
    val options: ExportOptions,
    val firstSequence: Int = 1,
    val createdAt: Long = 0,
)

/** [WAITING]: the original is only on another device; the job runs again later (ADR 0018 "Export"). */
@Serializable
enum class ItemStatus { PENDING, DONE, FAILED, WAITING }

/**
 * Progress of one item, persisted after every change so a run killed half-way resumes where it stopped (done items
 * are skipped) and removes what it was writing ([partialUri]).
 */
@Serializable
data class ItemState(
    val status: ItemStatus = ItemStatus.PENDING,
    val resultUri: String? = null,
    val fileName: String? = null,
    /** `ExportFailure` name. */
    val failure: String? = null,
    /** The destination document being written; set before the first byte, cleared when done. */
    val partialUri: String? = null,
    /** When the item started waiting for its original (Unix ms), kept across attempts. */
    val waitingSince: Long? = null,
    /** Exported from a local smart preview at its size: the original could not be read (ADR 0011). */
    val previewSize: Boolean = false,
)

/**
 * Jobs and their item states as small JSON files in `files/export-jobs/` (docs/adr/0009, "Batch"). WorkManager's input
 * data is capped at 10 KB, so the work request carries only the job id. Writes are atomic (temp file + rename).
 */
class ExportJobStore(context: Context) {
    private val dir = File(context.filesDir, "export-jobs").apply { mkdirs() }

    fun save(job: ExportJob) {
        write(File(dir, "${job.id}.json"), json.encodeToString(ExportJob.serializer(), job))
        if (!stateFile(job.id).exists()) saveStates(job.id, List(job.items.size) { ItemState() })
    }

    fun job(id: String): ExportJob? = runCatching {
        json.decodeFromString(ExportJob.serializer(), File(dir, "$id.json").readText())
    }.getOrNull()

    fun states(id: String): List<ItemState> = runCatching {
        json.decodeFromString(STATES, stateFile(id).readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun update(id: String, index: Int, change: (ItemState) -> ItemState): List<ItemState> {
        val states = states(id).toMutableList()
        states[index] = change(states[index])
        saveStates(id, states)
        return states
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        stateFile(id).delete()
    }

    /** Ids of every stored job (the worker removes finished ones after a while). */
    fun ids(): List<String> = dir.listFiles().orEmpty().map { it.name }.filter {
        it.endsWith(".json") &&
            !it.contains(".state")
    }
        .map { it.removeSuffix(".json") }

    private fun saveStates(id: String, states: List<ItemState>) =
        write(stateFile(id), json.encodeToString(STATES, states))

    private fun stateFile(id: String) = File(dir, "$id.state.json")

    private fun write(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val STATES = kotlinx.serialization.builtins.ListSerializer(ItemState.serializer())
    }
}
