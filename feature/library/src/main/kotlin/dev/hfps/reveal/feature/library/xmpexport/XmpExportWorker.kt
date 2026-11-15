package dev.hfps.reveal.feature.library.xmpexport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
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
import dev.hfps.reveal.data.library.XmpGrants
import dev.hfps.reveal.feature.library.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the job needs from the app graph (a Hilt entry point, as the preset batch). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface XmpExportEntryPoint {
    fun xmpExport(): XmpExport

    fun xmpGrants(): XmpGrants
}

/**
 * One Export XMP run: the photos and where to. What it did so far is the job's log ([XmpExportJobs.log]), one line
 * per photo, so a run stopped by the system (or killed) resumes at the next photo and writes no duplicates.
 */
@Serializable
data class XmpExportJob(
    val photoIds: List<Long>,
    /** The chosen folder's tree URI, or null for "next to the photos". */
    val folderUri: String? = null,
    /** Source id -> tree URI picked for a folder without a write grant (next to the photos), granted for the job. */
    val oneOff: Map<Long, String> = emptyMap(),
) {
    fun destination(): XmpDestination =
        folderUri?.let { XmpDestination.Folder(it) } ?: XmpDestination.NextToPhotos(oneOff)

    /** The trees this job holds a grant on until it ends. */
    val trees: Set<String> get() = setOfNotNull(folderUri) + oneOff.values
}

/** What a job's log says: the document created per photo (a chosen folder), the photos finished and their outcome. */
data class XmpExportLog(
    val created: Map<Long, String> = emptyMap(),
    val done: Set<Long> = emptySet(),
    val outcome: SidecarOutcome = SidecarOutcome.NONE,
)

/**
 * The jobs on disk (`files/xmp-jobs/<id>.json` + `<id>.log`): the photo list does not fit WorkManager's 10 KB data.
 * The log is append-only (`c <photo> <document>` before a document's bytes are written, `d <photo> <w|u|m>` when the
 * photo is done): one short line per photo, never the whole job rewritten.
 */
class XmpExportJobs(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "xmp-jobs"))

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

    /** Every job on disk but [except]: their trees stay granted while they wait. */
    fun others(except: String): List<XmpExportJob> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .filter { it.nameWithoutExtension != except }
        .mapNotNull { read(it.nameWithoutExtension) }

    fun created(id: String, photoId: Long, documentId: String) = append(id, "c $photoId $documentId")

    fun done(id: String, photoId: Long, outcome: SidecarOutcome) {
        val kind = when {
            outcome.written > 0 -> "w"
            outcome.malformed > 0 -> "m"
            else -> "u"
        }
        append(id, "d $photoId $kind")
    }

    fun log(id: String): XmpExportLog {
        val file = File(dir, "$id.log")
        if (!file.exists()) return XmpExportLog()
        val created = HashMap<Long, String>()
        val done = HashSet<Long>()
        var outcome = SidecarOutcome.NONE
        file.forEachLine { line ->
            val parts = line.split(' ', limit = 3)
            val photo = parts.getOrNull(1)?.toLongOrNull() ?: return@forEachLine
            when (parts[0]) {
                "c" -> parts.getOrNull(2)?.let { created[photo] = it }

                "d" -> if (done.add(photo)) {
                    outcome += when (parts.getOrNull(2)) {
                        "w" -> SidecarOutcome(1, 0)
                        "m" -> SidecarOutcome(0, 0, 1)
                        else -> SidecarOutcome(0, 1)
                    }
                }
            }
        }
        return XmpExportLog(created, done, outcome)
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.log").delete()
    }

    private fun append(id: String, line: String) {
        dir.mkdirs()
        File(dir, "$id.log").appendText("$line\n")
    }
}

/**
 * Export XMP (ADR 0026 "Export XMP") in the background: [XmpExport.exportOne] over the photos, each recorded in the
 * job's log, progress in a notification with Cancel, then a summary naming what was written and what was skipped.
 *
 * Only the user's Cancel ends a job early (its files deleted, its grants released). A stop by the system (quota,
 * constraints, the process going away) keeps the job: WorkManager runs it again and it resumes at the next photo.
 */
class XmpExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobs = XmpExportJobs(context)
    private val notifications = XmpExportNotifications(context)

    @Suppress("ReturnCount")
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val job = jobs.read(jobId) ?: return Result.failure()
        val graph = EntryPointAccessors.fromApplication(applicationContext, XmpExportEntryPoint::class.java)
        val export = graph.xmpExport()
        val total = job.photoIds.size
        val log = jobs.log(jobId)
        var done = log.done.size
        var outcome = log.outcome
        runCatching { setForeground(foreground(done, total)) }
        try {
            val session = export.open(job.destination())
            for (photoId in job.photoIds.filterNot { it in log.done }) {
                if (isStopped) break
                val result = export.exportOne(session, photoId, log.created[photoId]) { documentId ->
                    jobs.created(jobId, photoId, documentId)
                }
                jobs.done(jobId, photoId, result.outcome)
                outcome += result.outcome
                done++
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                notifications.progress(id, done, total)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stopped(jobId, job, graph, done, outcome) }
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "xmp export $jobId failed after $done/$total photos", e)
            end(jobId, job, graph, done, outcome, cancelled = true)
            return Result.failure()
        }
        if (isStopped) {
            stopped(jobId, job, graph, done, outcome)
            return Result.retry()
        }
        end(jobId, job, graph, done, outcome, cancelled = false)
        return Result.success(
            workDataOf(
                KEY_WRITTEN to outcome.written,
                KEY_UNWRITABLE to outcome.databaseOnly,
                KEY_MALFORMED to outcome.malformed,
            ),
        )
    }

    /** Stopped: the user's Cancel ends the job; anything else keeps it for WorkManager's next run. */
    private suspend fun stopped(
        jobId: String,
        job: XmpExportJob,
        graph: XmpExportEntryPoint,
        done: Int,
        outcome: SidecarOutcome,
    ) {
        if (cancelledByUser()) {
            end(jobId, job, graph, done, outcome, cancelled = true)
        } else {
            Log.i(TAG, "xmp export $jobId stopped by the system at $done/${job.photoIds.size}: kept")
        }
    }

    /** The job's end (done, cancelled or failed): its files go, its grants are released unless another job uses one. */
    private suspend fun end(
        jobId: String,
        job: XmpExportJob,
        graph: XmpExportEntryPoint,
        done: Int,
        outcome: SidecarOutcome,
        cancelled: Boolean,
    ) {
        jobs.delete(jobId)
        val inUse = jobs.others(jobId).flatMapTo(HashSet()) { it.trees }
        for (tree in job.trees) graph.xmpGrants().release(tree, inUse)
        Log.i(TAG, "xmp export $jobId: $done/${job.photoIds.size} photos, $outcome")
        notifications.finished(outcome, cancelled)
    }

    /**
     * Whether the stop was the user's Cancel (or the app's): the stop reason on API 31+, below it the work's state
     * (WorkManager marks it CANCELLED before stopping it; a stop by the system leaves it to be run again).
     */
    private suspend fun cancelledByUser(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
    } else {
        // Asked after the coroutine was cancelled too.
        withContext(NonCancellable) {
            runCatching {
                WorkManager.getInstance(applicationContext).getWorkInfoByIdFlow(id).first()?.state ==
                    WorkInfo.State.CANCELLED
            }.getOrDefault(false)
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

        /**
         * Enqueues the export of [photoIds] to [destination]; runs one after another. The trees it names must already
         * be granted ([XmpGrants.take]); the job releases them when it ends. Returns the work id.
         */
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

    fun finished(outcome: SidecarOutcome, cancelled: Boolean) {
        val title = context.getString(
            if (cancelled) R.string.xmp_export_cancelled else R.string.xmp_export_done,
            outcome.written,
        )
        val detail = context.getString(R.string.xmp_export_skipped, outcome.malformed, outcome.databaseOnly)
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
