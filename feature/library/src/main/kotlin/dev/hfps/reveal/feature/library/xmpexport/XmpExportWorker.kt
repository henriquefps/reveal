package dev.hfps.reveal.feature.library.xmpexport

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
import dev.hfps.reveal.data.library.SidecarOutcome
import dev.hfps.reveal.data.library.XmpDestination
import dev.hfps.reveal.data.library.XmpExport
import dev.hfps.reveal.feature.library.R
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the job needs from the app graph (a Hilt entry point, as the preset batch). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface XmpExportEntryPoint {
    fun xmpExport(): XmpExport
}

/**
 * One Export XMP run: the photos, where to, how far it got and what it did so far (resumable after a kill: a folder
 * the user picked for this run only is then no longer writable, which the result counts as such).
 */
@Serializable
data class XmpExportJob(
    val photoIds: List<Long>,
    /** The chosen folder's tree URI, or null for "next to the photos". */
    val folderUri: String? = null,
    /** Source id -> tree URI picked for a folder without a write grant (next to the photos). */
    val oneOff: Map<Long, String> = emptyMap(),
    val done: Int = 0,
    val written: Int = 0,
    val unwritable: Int = 0,
    val malformed: Int = 0,
) {
    fun destination(): XmpDestination =
        folderUri?.let { XmpDestination.Folder(it) } ?: XmpDestination.NextToPhotos(oneOff)

    val outcome: SidecarOutcome get() = SidecarOutcome(written, unwritable, malformed)
}

/** The jobs on disk (`files/xmp-jobs/<id>.json`): the photo list does not fit WorkManager's 10 KB data. */
class XmpExportJobs(context: Context) {
    private val dir = File(context.filesDir, "xmp-jobs")
    private val json = Json { ignoreUnknownKeys = true }

    fun write(id: String, job: XmpExportJob) {
        dir.mkdirs()
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(json.encodeToString(XmpExportJob.serializer(), job))
        tmp.renameTo(File(dir, "$id.json"))
    }

    fun read(id: String): XmpExportJob? = runCatching {
        json.decodeFromString(XmpExportJob.serializer(), File(dir, "$id.json").readText())
    }.getOrNull()

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

/**
 * Export XMP (ADR 0026 "Export XMP") in the background: [XmpExport.run] over the photos, 25 per step, progress in a
 * notification with Cancel, then a summary naming what was written and what was skipped.
 */
class XmpExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobs = XmpExportJobs(context)
    private val notifications = XmpExportNotifications(context)

    @Suppress("ReturnCount")
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        var job = jobs.read(jobId) ?: return Result.failure()
        val export = EntryPointAccessors.fromApplication(
            applicationContext,
            XmpExportEntryPoint::class.java,
        ).xmpExport()
        val total = job.photoIds.size
        runCatching { setForeground(foreground(job.done, total)) }
        // One chunk at a time, so a cancel (or a kill) keeps what is done and the job file always says where it is.
        while (job.done < total && !isStopped) {
            val chunk = job.photoIds.subList(job.done, minOf(total, job.done + XmpExport.CHUNK))
            val step = export.run(chunk, job.destination())
            job = job.copy(
                done = job.done + chunk.size,
                written = job.written + step.written,
                unwritable = job.unwritable + step.databaseOnly,
                malformed = job.malformed + step.malformed,
            )
            jobs.write(jobId, job)
            setProgress(workDataOf(KEY_DONE to job.done, KEY_TOTAL to total))
            notifications.progress(id, job.done, total)
        }
        jobs.delete(jobId)
        Log.i(TAG, "xmp export $jobId: ${job.done}/$total photos, ${job.outcome}")
        notifications.finished(job, cancelled = isStopped)
        return if (isStopped) {
            Result.failure()
        } else {
            Result.success(
                workDataOf(
                    KEY_WRITTEN to job.written,
                    KEY_UNWRITABLE to job.unwritable,
                    KEY_MALFORMED to job.malformed,
                ),
            )
        }
    }

    private fun foreground(done: Int, total: Int): ForegroundInfo = ForegroundInfo(
        XmpExportNotifications.PROGRESS_ID,
        notifications.progressNotification(id, done, total),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        const val TAG: String = "RevealXmpExport"
        const val KEY_JOB: String = "job"
        const val KEY_DONE: String = "done"
        const val KEY_TOTAL: String = "total"
        const val KEY_WRITTEN: String = "written"
        const val KEY_UNWRITABLE: String = "unwritable"
        const val KEY_MALFORMED: String = "malformed"
        const val UNIQUE_NAME: String = "xmp-export"

        /** Enqueues the export of [photoIds] to [destination]; runs one after another. Returns the work id. */
        fun enqueue(context: Context, photoIds: List<Long>, destination: XmpDestination): UUID {
            val jobId = UUID.randomUUID().toString()
            val job = when (destination) {
                is XmpDestination.Folder -> XmpExportJob(photoIds, folderUri = destination.treeUri)
                is XmpDestination.NextToPhotos -> XmpExportJob(photoIds, oneOff = destination.oneOff)
            }
            XmpExportJobs(context).write(jobId, job)
            val request = OneTimeWorkRequestBuilder<XmpExportWorker>()
                .setInputData(workDataOf(KEY_JOB to jobId))
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return request.id
        }
    }
}

/** The export's notifications: progress with Cancel, then the result. */
class XmpExportNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.xmp_export_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun progressNotification(workId: UUID, done: Int, total: Int) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_save)
        .setContentTitle(context.resources.getQuantityString(R.plurals.xmp_export_progress, total, done, total))
        .setProgress(total, done, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(
            0,
            context.getString(R.string.xmp_export_cancel),
            WorkManager.getInstance(context).createCancelPendingIntent(workId),
        )
        .build()

    fun progress(workId: UUID, done: Int, total: Int) {
        runCatching { manager.notify(PROGRESS_ID, progressNotification(workId, done, total)) }
    }

    fun finished(job: XmpExportJob, cancelled: Boolean) {
        val title = context.getString(
            if (cancelled) R.string.xmp_export_cancelled else R.string.xmp_export_done,
            job.written,
        )
        val detail = context.getString(R.string.xmp_export_skipped, job.malformed, job.unwritable)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .build()
        runCatching {
            manager.cancel(PROGRESS_ID)
            manager.notify(DONE_ID, notification)
        }
    }

    companion object {
        const val CHANNEL = "xmp-export"
        const val PROGRESS_ID = 4301
        const val DONE_ID = 4302
    }
}
