package dev.hfps.reveal.feature.export.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
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
import dev.hfps.reveal.data.export.ExportPreferences
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.feature.export.R
import dev.hfps.reveal.feature.export.destination.Destinations
import dev.hfps.reveal.feature.export.engine.ExportEngine
import dev.hfps.reveal.feature.export.engine.ExportException
import dev.hfps.reveal.feature.export.engine.ExportFailure
import dev.hfps.reveal.feature.export.engine.ExportItem
import dev.hfps.reveal.pipeline.gl.GlesRequirementException
import dev.hfps.reveal.pipeline.gl.RenderCancelledException
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** What the worker needs from the app graph (a Hilt entry point: no `HiltWorkerFactory` to configure). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExportEntryPoint {
    fun library(): LibraryRepository

    fun edits(): EditRepository

    fun exportPreferences(): ExportPreferences
}

/**
 * Background export of a job (docs/adr/0009, "Batch"): a foreground worker with a progress notification and a cancel
 * action, its own GL context ([TiledRenderer]), one photo at a time. Idempotent per photo: every item's state is
 * persisted ([ExportJobStore]); after the process is killed WorkManager runs the job again, done items are skipped and
 * a half-written destination file is deleted before the item is exported again. A failed item (source gone, SD card
 * removed, low storage) is recorded with its reason and the run goes on.
 */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobs = ExportJobStore(context)
    private val notifications = ExportNotifications(context)

    /** Everything one run needs while it walks the items. */
    private class Run(
        val jobId: String,
        val job: ExportJob,
        val graph: ExportEntryPoint,
        val engine: ExportEngine,
        val destinations: Destinations,
        val renderer: TiledRenderer,
    ) {
        val total: Int get() = job.items.size
    }

    @Suppress("ReturnCount")
    override suspend fun doWork(): Result {
        // The job id is the work's own tag: in the APPEND chain WorkManager merges the previous work's output into
        // this one's input, so the input alone is not trusted.
        val jobId = tags.firstOrNull { it.startsWith(JOB_TAG) }?.removePrefix(JOB_TAG) ?: inputData.getString(KEY_JOB)
        val job = jobId?.let(jobs::job) ?: return Result.failure()
        val total = job.items.size
        Log.i(TAG, "job $jobId: $total items, states ${jobs.states(jobId).map { it.status }}")
        report(jobs.states(jobId), total)
        val renderer = try {
            withContext(Dispatchers.Default) { TiledRenderer.create() }
        } catch (e: GlesRequirementException) {
            Log.w(TAG, "no GPU for the export", e)
            failAll(jobId, ExportFailure.GPU_UNSUPPORTED)
            return finish(jobId, total)
        }
        val graph = EntryPointAccessors.fromApplication(applicationContext, ExportEntryPoint::class.java)
        val run = Run(jobId, job, graph, ExportEngine(applicationContext), Destinations(applicationContext), renderer)
        try {
            for (index in job.items.indices) {
                if (isStopped || !exportItem(run, index)) break
                report(jobs.states(jobId), total)
            }
        } finally {
            renderer.close()
        }
        if (isStopped) {
            notifications.cancelled()
            return Result.failure()
        }
        return finish(jobId, total)
    }

    /** Exports item [index] unless it is already settled; false when the run was cancelled. */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun exportItem(run: Run, index: Int): Boolean {
        val state = jobs.states(run.jobId)[index]
        if (state.status != ItemStatus.PENDING) return true
        // A previous run was killed while writing this item: remove its partial file first.
        state.partialUri?.let { run.destinations.deletePartial(it.toUri()) }
        val item = resolve(run.graph, run.job.items[index], run.job.firstSequence + index)
        try {
            if (item == null) throw ExportException(ExportFailure.SOURCE_MISSING)
            val result = runInterruptible(Dispatchers.IO) {
                run.engine.export(
                    item,
                    run.job.options,
                    run.graph.exportPreferences().authorship.value,
                    run.renderer,
                    cancel = { isStopped },
                    progress = { fraction -> reportAsync(run.jobId, run.total, index, fraction) },
                    onPartial = { uri -> jobs.update(run.jobId, index) { it.copy(partialUri = uri.toString()) } },
                )
            }
            jobs.update(run.jobId, index) { ItemState(ItemStatus.DONE, result.uri.toString(), result.fileName) }
            Log.i(TAG, "item ${index + 1}/${run.total}: $result")
            return true
        } catch (e: RenderCancelledException) {
            Log.i(TAG, "item ${index + 1}/${run.total} cancelled", e)
            cleanPartial(run.jobId, index, run.destinations)
            return false
        } catch (e: CancellationException) {
            cleanPartial(run.jobId, index, run.destinations)
            throw e
        } catch (e: OutOfMemoryError) {
            fail(run, index, ExportFailure.OUT_OF_MEMORY, e)
        } catch (e: Exception) {
            fail(run, index, (e as? ExportException)?.failure ?: ExportFailure.FAILED, e)
        }
        return true
    }

    private fun fail(run: Run, index: Int, failure: ExportFailure, cause: Throwable) {
        cleanPartial(run.jobId, index, run.destinations)
        jobs.update(run.jobId, index) { it.copy(status = ItemStatus.FAILED, failure = failure.name) }
        Log.w(TAG, "item ${index + 1}/${run.total} failed: $failure", cause)
    }

    private fun finish(jobId: String, total: Int): Result {
        val states = jobs.states(jobId)
        val done = states.count { it.status == ItemStatus.DONE }
        val failed = states.count { it.status == ItemStatus.FAILED }
        notifications.finished(done, failed, total)
        return Result.success()
    }

    private fun failAll(jobId: String, failure: ExportFailure) {
        jobs.states(jobId).forEachIndexed { i, s ->
            val pending = s.status == ItemStatus.PENDING
            if (pending) jobs.update(jobId, i) { it.copy(status = ItemStatus.FAILED, failure = failure.name) }
        }
    }

    private fun cleanPartial(jobId: String, index: Int, destinations: Destinations) {
        jobs.states(jobId)[index].partialUri?.let { destinations.deletePartial(it.toUri()) }
        jobs.update(jobId, index) { it.copy(partialUri = null) }
    }

    /** The file and recipe of a job item; null when its version or URI is gone. */
    private suspend fun resolve(graph: ExportEntryPoint, item: ExportJobItem, sequence: Int): ExportItem? {
        val recipe = item.recipeJson?.let { runCatching { RecipeCodec.Default.decode(it) }.getOrNull() }
        val versionId = item.versionId
        val target = versionId?.let { graph.library().exportTarget(it) }
        return when {
            target != null -> ExportItem(
                uri = target.uri,
                name = target.name,
                recipe = recipe ?: graph.edits().recipe(checkNotNull(versionId)),
                rating = target.rating,
                renderedUri = target.renderedUri,
                sequence = sequence,
            )

            item.uri != null && recipe != null -> item.uri.toUri().let { uri ->
                ExportItem(uri, item.name ?: uri.lastPathSegment.orEmpty(), recipe, sequence = sequence)
            }

            else -> null
        }
    }

    private var lastReport = 0L

    private fun reportAsync(jobId: String, total: Int, index: Int, fraction: Float) {
        val now = System.currentTimeMillis()
        if (now - lastReport < REPORT_INTERVAL_MS) return
        lastReport = now
        val states = jobs.states(jobId)
        val done = states.count { it.status != ItemStatus.PENDING }
        setProgressAsync(progressData(done, total, (index + fraction) / total))
        notifications.progress(id, done, total, (index + fraction) / total)
    }

    private suspend fun report(states: List<ItemState>, total: Int, current: Float = 0f) {
        val done = states.count { it.status != ItemStatus.PENDING }
        val fraction = (done + current) / total
        setProgress(progressData(done, total, fraction))
        runCatching { setForeground(foregroundInfo(done, total, fraction)) }
            .onFailure { Log.w(TAG, "no foreground service (started from the background?)", it) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 1, 0f)

    private fun foregroundInfo(done: Int, total: Int, fraction: Float) = ForegroundInfo(
        ExportNotifications.PROGRESS_ID,
        notifications.progressNotification(id, done, total, fraction),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        const val KEY_JOB = "job"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FRACTION = "fraction"
        const val UNIQUE_NAME = "reveal-export"
        private const val JOB_TAG = "reveal-export-job:"
        private const val TAG = "RevealExport"
        private const val REPORT_INTERVAL_MS = 250L

        private fun progressData(done: Int, total: Int, fraction: Float) =
            workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_FRACTION to fraction)

        /**
         * Stores [items] as a job and queues it. Jobs run one after another (a unique chain), each in the background
         * until done, surviving the app being closed. Returns the job id (the work's tag).
         */
        fun enqueue(
            context: Context,
            items: List<ExportJobItem>,
            options: dev.hfps.reveal.feature.export.model.ExportOptions,
            firstSequence: Int,
        ): String {
            val id = UUID.randomUUID().toString()
            ExportJobStore(context).save(ExportJob(id, items, options, firstSequence, System.currentTimeMillis()))
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf(KEY_JOB to id))
                .addTag(id)
                .addTag(JOB_TAG + id)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(
                context,
            ).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return id
        }
    }
}

/** The export notifications: one ongoing progress notification with Cancel, one summary when a run ends. */
class ExportNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.export_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun progressNotification(workId: UUID, done: Int, total: Int, fraction: Float) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.export_notification_progress, minOf(done + 1, total), total))
            .setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                context.getString(R.string.export_cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
            .build()

    fun progress(workId: UUID, done: Int, total: Int, fraction: Float) {
        runCatching { manager.notify(PROGRESS_ID, progressNotification(workId, done, total, fraction)) }
    }

    fun finished(done: Int, failed: Int, total: Int) {
        val title = if (failed == 0) {
            context.resources.getQuantityString(R.plurals.export_notification_done, done, done)
        } else {
            context.getString(R.string.export_notification_failed, failed, total)
        }
        notifyEnd(title)
    }

    fun cancelled() = notifyEnd(context.getString(R.string.export_notification_cancelled))

    private fun notifyEnd(title: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(DONE_ID, notification) }
    }

    companion object {
        const val CHANNEL = "export"
        const val PROGRESS_ID = 4101
        const val DONE_ID = 4102
        private const val PROGRESS_MAX = 1000
    }
}
