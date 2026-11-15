package dev.hfps.reveal.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.sync.work.SyncScheduler
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The jobs' constraints (ADR 0018 "Jobs"): metadata on any network; previews and originals spare the battery. */
@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var work: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        work = WorkManager.getInstance(context)
    }

    private fun info(name: String): WorkInfo = work.getWorkInfosForUniqueWork(name).get().single()

    @Test
    fun `metadata needs a network, blobs also a battery and storage that are not low`() {
        SyncScheduler.now(context)
        SyncScheduler.periodic(context)
        SyncScheduler.blobs(context)
        val now = info("reveal-sync").constraints
        assertThat(now.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(now.requiresBatteryNotLow()).isFalse()
        assertThat(info("reveal-sync-periodic").periodicityInfo!!.repeatIntervalMillis).isEqualTo(15 * 60 * 1000L)
        val blobs = info("reveal-sync-blobs").constraints
        assertThat(blobs.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(blobs.requiresBatteryNotLow()).isTrue()
        assertThat(blobs.requiresStorageNotLow()).isTrue()
    }

    @Test
    fun `a second request while one is queued adds nothing, and unpairing cancels everything`() {
        SyncScheduler.now(context, delaySeconds = 60)
        SyncScheduler.now(context, delaySeconds = 60)
        assertThat(work.getWorkInfosForUniqueWork("reveal-sync").get()).hasSize(1)
        SyncScheduler.cancel(context)
        assertThat(info("reveal-sync").state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `the library thumbnails prefetch waits for an unmetered network and charging or idle`() {
        SyncScheduler.libraryThumbs(context)
        val charging = info("reveal-thumbs-charging").constraints
        assertThat(charging.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(charging.requiresCharging()).isTrue()
        assertThat(charging.requiresDeviceIdle()).isFalse()
        val idle = info("reveal-thumbs-idle").constraints
        assertThat(idle.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(idle.requiresDeviceIdle()).isTrue()
        assertThat(idle.requiresCharging()).isFalse()
        assertThat(info("reveal-thumbs-periodic").constraints.requiresCharging()).isTrue()
        // Asked again while queued (and again with more left): one of each.
        SyncScheduler.libraryThumbs(context)
        SyncScheduler.libraryThumbs(context, again = true)
        assertThat(work.getWorkInfosForUniqueWork("reveal-thumbs-periodic").get()).hasSize(1)
        SyncScheduler.cancelLibraryThumbs(context)
        assertThat(info("reveal-thumbs-periodic").state).isEqualTo(WorkInfo.State.CANCELLED)
    }
}
