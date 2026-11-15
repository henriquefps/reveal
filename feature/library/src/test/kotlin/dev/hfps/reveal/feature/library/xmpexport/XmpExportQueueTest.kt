package dev.hfps.reveal.feature.library.xmpexport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.library.XmpDestination
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export XMP jobs are independent works (ADR 0026 "Export XMP"): cancelling or failing one never takes the queued
 * ones with it, and the files of jobs whose work is gone are swept with their grants.
 */
@RunWith(AndroidJUnit4::class)
class XmpExportQueueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Worker bodies wait here until [drain]: the works stay queued/running meanwhile. */
    private val pending = ArrayDeque<Runnable>()

    private fun drain() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
    }

    /** Stands in for the export: records the job it ran, unless its work was stopped first. */
    class RecordingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            if (!isStopped) ran += inputData.getString(XmpExportWorker.KEY_JOB)!!
            return Result.success()
        }

        companion object {
            val ran = mutableListOf<String>()
        }
    }

    private lateinit var work: WorkManager

    @Before
    fun setUp() {
        RecordingWorker.ran.clear()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = RecordingWorker(appContext, workerParameters)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(Executor { pending.addLast(it) })
                .setWorkerFactory(factory)
                .build(),
        )
        work = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "xmp-jobs").deleteRecursively()
    }

    private fun jobOf(id: java.util.UUID): String =
        work.getWorkInfoById(id).get()!!.tags.single { it.startsWith("xmp-job:") }.removePrefix("xmp-job:")

    @Test
    fun `cancelling the running job leaves the queued one to run`() = runTest {
        val taken = mutableListOf<String>()
        val first = XmpExportWorker.enqueue(context, listOf(1L), XmpDestination.Folder("content://t/tree/a")) {
            taken += it
        }
        val second = XmpExportWorker.enqueue(context, listOf(2L), XmpDestination.Folder("content://t/tree/b")) {
            taken += it
        }
        assertThat(taken).containsExactly("content://t/tree/a", "content://t/tree/b").inOrder()
        assertThat(work.getWorkInfoById(second).get()!!.state).isNotEqualTo(WorkInfo.State.BLOCKED)

        work.cancelWorkById(first).result.get()
        assertThat(work.getWorkInfoById(first).get()!!.state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(work.getWorkInfoById(second).get()!!.state.isFinished).isFalse()

        drain()
        assertThat(RecordingWorker.ran).containsExactly(jobOf(second))
        assertThat(work.getWorkInfoById(second).get()!!.state).isEqualTo(WorkInfo.State.SUCCEEDED)
    }

    @Test
    fun `orphan job files are swept and their grants released unless a live job uses them`() = runTest {
        val live = XmpExportWorker.enqueue(context, listOf(1L), XmpDestination.Folder("content://t/tree/shared")) {}
        val liveJob = jobOf(live)
        val jobs = XmpExportJobs(context)
        // A job cancelled while queued (its work finished without running) and one killed before its work existed.
        jobs.write("cancelled", XmpExportJob(listOf(2L), folderUri = "content://t/tree/out"))
        jobs.created("cancelled", 2L, "out/DSC00002.xmp")
        jobs.write("killed", XmpExportJob(listOf(3L), oneOff = mapOf(7L to "content://t/tree/shared")))

        val released = mutableListOf<String>()
        XmpExportWorker.sweep(context) { tree, inUse -> if (tree !in inUse) released += tree }

        assertThat(released).containsExactly("content://t/tree/out")
        assertThat(jobs.ids()).containsExactly(liveJob)
        assertThat(jobs.read(liveJob)).isNotNull()
    }

    @Test
    fun `a job that ends releases its grants and sweeps the orphans with it`() = runTest {
        val jobs = XmpExportJobs(context)
        val ended = XmpExportJob(listOf(1L), folderUri = "content://t/tree/a")
        jobs.write("ended", ended)
        jobs.write("orphan", XmpExportJob(listOf(2L), folderUri = "content://t/tree/b"))

        val released = mutableListOf<String>()
        XmpExportWorker.sweep(context, ended = "ended" to ended) { tree, inUse -> if (tree !in inUse) released += tree }

        assertThat(released).containsExactly("content://t/tree/a", "content://t/tree/b")
        assertThat(jobs.ids()).isEmpty()
    }
}
