package dev.hfps.reveal.sync.work

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.hfps.reveal.data.storage.AppStart
import dev.hfps.reveal.data.storage.LibraryThumbsPreferences
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.thumbs.LibraryThumbs
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun sync(): SyncRepository
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryThumbsEntryPoint {
    fun libraryThumbs(): LibraryThumbs
}

private fun Context.sync(): SyncRepository =
    EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java).sync()

/**
 * The metadata sync (ADR 0018 "Jobs"): push, pull, NAS feed, lookups. Any network; retried with exponential backoff
 * when the server or the network fails, never when the key is gone.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.sync().syncMetadata()
        SyncScheduler.blobs(applicationContext)
        Result.success()
    } catch (e: IOException) {
        Log.i(TAG, "sync will retry: ${e.javaClass.simpleName}")
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}

/**
 * Smart previews and the originals backup (ADR 0018 "Jobs"): needs a network and a battery that is not low; originals
 * go only over an unmetered network unless the owner allowed mobile data. Re-enqueues itself while work is left.
 */
class BlobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val sync = applicationContext.sync()
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val unmetered = connectivity?.isActiveNetworkMetered == false
        val allowed = unmetered || !sync.options.value.originalsWifiOnly
        val more = sync.syncBlobs(originalsAllowed = allowed, unmetered = unmetered)
        if (more) SyncScheduler.blobs(applicationContext, again = true)
        Result.success()
    } catch (e: IOException) {
        Log.i(TAG, "uploads will retry: ${e.javaClass.simpleName}")
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}

/**
 * The library thumbnails' prefetch (ADR 0018 "W3/C"): the missing thumbnails of the whole library, newest first, while
 * the network is unmetered and the phone charges or is idle (two requests with those constraints; whichever runs first
 * does the work, the other finds nothing). Re-enqueues itself while thumbnails are missing; stopped by WorkManager when
 * a constraint ends (cancellation between batches).
 */
class LibraryThumbsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val thumbs = EntryPointAccessors.fromApplication(applicationContext, LibraryThumbsEntryPoint::class.java)
            .libraryThumbs()
        val report = thumbs.prefetch()
        if (report.left) SyncScheduler.libraryThumbs(applicationContext, again = true)
        Result.success()
    } catch (e: IOException) {
        Log.i(TAG, "library thumbnails will retry: ${e.javaClass.simpleName}")
        if (runAttemptCount < THUMB_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val THUMB_ATTEMPTS = 5
    }
}

private const val TAG = "RevealSync"

/** Enqueues the sync jobs; unique names keep one of each. */
object SyncScheduler {
    private const val NOW = "reveal-sync"
    private const val PERIODIC = "reveal-sync-periodic"
    private const val BLOBS = "reveal-sync-blobs"
    private const val PERIOD_MINUTES = 15L
    private const val BACKOFF_SECONDS = 30L

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A metadata sync soon ([delaySeconds] lets a burst of edits settle); nothing new while one is queued. */
    fun now(context: Context, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** The background sync every 15 minutes while paired. */
    fun periodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun blobs(context: Context, again: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<BlobWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BLOBS,
            if (again) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private const val THUMBS_CHARGING = "reveal-thumbs-charging"
    private const val THUMBS_IDLE = "reveal-thumbs-idle"
    private const val THUMBS_PERIODIC = "reveal-thumbs-periodic"
    private const val THUMBS_PERIOD_HOURS = 6L

    /**
     * The library thumbnails' prefetch (ADR 0018 "W3/C"): unmetered network and storage not low, plus charging (and a
     * battery not low) for one request, the device idle for the other; a periodic one resumes it every few hours.
     */
    fun libraryThumbs(context: Context, again: Boolean = false) {
        val work = WorkManager.getInstance(context)
        val policy = if (again) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        val charging = whileCharging()
        val idle = whileIdle()
        for ((name, constraints) in listOf(THUMBS_CHARGING to charging, THUMBS_IDLE to idle)) {
            val request = OneTimeWorkRequestBuilder<LibraryThumbsWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS).build()
            work.enqueueUniqueWork(name, policy, request)
        }
        val periodic = PeriodicWorkRequestBuilder<LibraryThumbsWorker>(THUMBS_PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(charging).build()
        work.enqueueUniquePeriodicWork(THUMBS_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /** Unmetered, storage not low, charging with a battery not low (one request of the prefetch). */
    private fun whileCharging(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true).setRequiresCharging(true).setRequiresBatteryNotLow(true).build()

    /** Unmetered, storage not low, the device idle (the other request: never charging and idle together). */
    private fun whileIdle(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true).setRequiresDeviceIdle(true).build()

    fun cancelLibraryThumbs(context: Context) {
        val work = WorkManager.getInstance(context)
        listOf(THUMBS_CHARGING, THUMBS_IDLE, THUMBS_PERIODIC).forEach(work::cancelUniqueWork)
    }

    fun cancel(context: Context) {
        val work = WorkManager.getInstance(context)
        listOf(NOW, PERIODIC, BLOBS).forEach(work::cancelUniqueWork)
        cancelLibraryThumbs(context)
    }
}

/**
 * Keeps sync going while the app runs (ADR 0018 "Jobs"): the periodic job while paired, a sync a few seconds after
 * local changes settle, and a pull every [FOREGROUND_SECONDS] while the app is in the foreground, so edits made on
 * another device show up without a restart.
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncController(
    private val context: Context,
    private val sync: SyncRepository,
    private val scope: CoroutineScope,
    /** "Keep thumbnails of the whole library" (ADR 0018 "W3/C"); null = never prefetch (tests). */
    private val thumbs: LibraryThumbsPreferences? = null,
) {
    private var foreground: Job? = null

    fun start() {
        scope.launch {
            // Nothing touches WorkManager until the device is paired (or was, to cancel its jobs), and nothing runs
            // before the first frame (ADR 0018 "W3/C": the first sync reads server headers and lookups).
            AppStart.awaitSettled()
            var wasPaired = false
            sync.account.map { it != null }.distinctUntilChanged().collect { paired ->
                if (paired) {
                    SyncScheduler.periodic(context)
                    SyncScheduler.now(context)
                    if (thumbs?.keepAll?.value == true) SyncScheduler.libraryThumbs(context)
                } else if (wasPaired) {
                    SyncScheduler.cancel(context)
                }
                wasPaired = paired
            }
        }
        scope.launch {
            sync.account.flatMapLatest { account ->
                if (account == null) flowOf(0) else sync.status.map { it.pending }.distinctUntilChanged()
            }.filter { it > 0 }.debounce(CHANGE_SETTLE_MS).collect { SyncScheduler.now(context) }
        }
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        foreground?.cancel()
                        foreground = scope.launch {
                            AppStart.awaitSettled()
                            while (isActive) {
                                if (sync.account.value != null) SyncScheduler.now(context)
                                delay(FOREGROUND_SECONDS * MILLIS)
                            }
                        }
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        foreground?.cancel()
                        foreground = null
                    }
                },
            )
        }
    }

    private companion object {
        const val CHANGE_SETTLE_MS = 3_000L
        const val FOREGROUND_SECONDS = 30L
        const val MILLIS = 1_000L
    }
}
