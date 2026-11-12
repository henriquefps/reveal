package dev.hfps.reveal.feature.library.presets

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.feature.library.R
import dev.hfps.reveal.pipeline.auto.PhotoFeatures
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.SmartCorrection
import dev.hfps.reveal.pipeline.preset.SmartCorrector
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the batch needs from the app graph (a Hilt entry point, as the export worker). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PresetBatchEntryPoint {
    fun presets(): PresetRepository

    fun edits(): EditRepository

    fun smartCorrection(): SmartCorrection

    fun thumbnails(): ThumbnailRepository
}

/** One batch: the preset, its strength and the versions, and how many are done (resumable after a kill). */
@Serializable
data class PresetBatchJob(val presetId: String, val strength: Float, val versionIds: List<Long>, val done: Int = 0)

/** The batch jobs on disk (`files/preset-jobs/<id>.json`): the version list does not fit WorkManager's 10 KB data. */
class PresetBatchJobs(context: Context) {
    private val dir = File(context.filesDir, "preset-jobs")
    private val json = Json { ignoreUnknownKeys = true }

    fun write(id: String, job: PresetBatchJob) {
        dir.mkdirs()
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(json.encodeToString(PresetBatchJob.serializer(), job))
        tmp.renameTo(File(dir, "$id.json"))
    }

    fun read(id: String): PresetBatchJob? = runCatching {
        json.decodeFromString(PresetBatchJob.serializer(), File(dir, "$id.json").readText())
    }.getOrNull()

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

/**
 * Applies a preset to the library selection in the background (docs/adr/0019 "Batch"): one history step per photo
 * ([HistoryAction.PRESET], named after the preset), [CHUNK] photos per transaction, progress in a notification with
 * Cancel. A cancel keeps the photos already done; a killed run resumes after the last finished chunk.
 */
class PresetBatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobs = PresetBatchJobs(context)
    private val notifications = PresetBatchNotifications(context)

    @Suppress("ReturnCount")
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        var job = jobs.read(jobId) ?: return Result.failure()
        val graph = EntryPointAccessors.fromApplication(applicationContext, PresetBatchEntryPoint::class.java)
        val preset = graph.presets().preset(job.presetId) ?: run {
            jobs.delete(jobId)
            return Result.failure()
        }
        val total = job.versionIds.size
        runCatching { setForeground(foreground(job.done, total, preset.name)) }
        var changed = 0
        while (job.done < total && !isStopped) {
            val chunk = job.versionIds.subList(job.done, minOf(total, job.done + CHUNK))
            changed += if (preset.smart == null) {
                apply(graph.edits(), preset, job.strength, chunk, graph.smartCorrection())
            } else {
                applySmart(graph, preset, job.strength, chunk)
            }
            job = job.copy(done = job.done + chunk.size)
            jobs.write(jobId, job)
            setProgress(workDataOf(KEY_DONE to job.done, KEY_TOTAL to total))
            notifications.progress(id, job.done, total, preset.name)
        }
        graph.presets().markUsed(preset.id)
        jobs.delete(jobId)
        Log.i(TAG, "batch $jobId: ${preset.name} on ${job.done}/$total photos, $changed changed")
        notifications.finished(job.done, preset.name, cancelled = isStopped)
        return if (isStopped) Result.failure() else Result.success(workDataOf(KEY_DONE to job.done))
    }

    private suspend fun apply(
        edits: EditRepository,
        preset: Preset,
        strength: Float,
        versions: List<Long>,
        smart: SmartCorrection,
    ): Int = edits.applyEach(versions, HistoryAction.PRESET, preset.name) { recipe ->
        preset.apply(recipe, strength, smartCorrection = smart)
    }

    /**
     * A smart preset (docs/adr/0020): every photo gets its own correction from its own features (decoded and analysed
     * here, off the database transaction), then the style. A photo whose file is offline gets the style only.
     */
    private suspend fun applySmart(
        graph: PresetBatchEntryPoint,
        preset: Preset,
        strength: Float,
        versions: List<Long>,
    ): Int {
        var changed = 0
        for (versionId in versions) {
            if (isStopped) break
            val corrector = withContext(Dispatchers.Default) {
                graph.thumbnails().analysisInput(versionId)?.let { input ->
                    runCatching { SmartCorrector(PhotoFeatures.of(input, findLines = true)) }.getOrNull()
                }
            } ?: graph.smartCorrection()
            changed += graph.edits().applyEach(listOf(versionId), HistoryAction.PRESET, preset.name) { recipe ->
                preset.apply(recipe, strength, smartCorrection = corrector)
            }
        }
        return changed
    }

    private fun foreground(done: Int, total: Int, name: String): ForegroundInfo = ForegroundInfo(
        PresetBatchNotifications.PROGRESS_ID,
        notifications.progressNotification(id, done, total, name),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        const val TAG: String = "RevealPresets"
        const val KEY_JOB: String = "job"
        const val KEY_DONE: String = "done"
        const val KEY_TOTAL: String = "total"
        const val UNIQUE_NAME: String = "preset-batch"
        const val CHUNK: Int = 25

        /** Enqueues [preset] at [strength] on [versionIds]; returns the work id. Batches run one after another. */
        fun enqueue(context: Context, presetId: String, strength: Float, versionIds: List<Long>): UUID {
            val jobId = UUID.randomUUID().toString()
            PresetBatchJobs(context).write(jobId, PresetBatchJob(presetId, strength, versionIds))
            val request = OneTimeWorkRequestBuilder<PresetBatchWorker>()
                .setInputData(workDataOf(KEY_JOB to jobId))
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return request.id
        }
    }
}

/** The batch's notifications: progress with Cancel, then a summary. */
class PresetBatchNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.presets_batch_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun progressNotification(workId: UUID, done: Int, total: Int, name: String) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.presets_batch_progress, total, name, done, total),
            )
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                context.getString(R.string.presets_batch_cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
            .build()

    fun progress(workId: UUID, done: Int, total: Int, name: String) {
        runCatching { manager.notify(PROGRESS_ID, progressNotification(workId, done, total, name)) }
    }

    fun finished(done: Int, name: String, cancelled: Boolean) {
        val title = if (cancelled) {
            context.resources.getQuantityString(R.plurals.presets_batch_cancelled, done, name, done)
        } else {
            context.resources.getQuantityString(R.plurals.presets_batch_done, done, name, done)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setAutoCancel(true)
            .build()
        runCatching {
            manager.cancel(PROGRESS_ID)
            manager.notify(DONE_ID, notification)
        }
    }

    companion object {
        const val CHANNEL = "presets"
        const val PROGRESS_ID = 4201
        const val DONE_ID = 4202
    }
}
