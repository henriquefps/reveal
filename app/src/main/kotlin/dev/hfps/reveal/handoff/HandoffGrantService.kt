package dev.hfps.reveal.handoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.hfps.reveal.R
import dev.hfps.reveal.data.handoff.HandoffGrants
import dev.hfps.reveal.data.handoff.HandoffIngests
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.data.handoff.HandoffRepository
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds a hand-off's read grant while the work that needs it finishes (ADR 0011 "Amendment W4", "Grant service"):
 * the activity that received the intent starts it, only when some documents need the grant after the activity is gone
 * ([HandoffGrants.needing]), with the intent's URIs as [ClipData] and [Intent.FLAG_GRANT_READ_URI_PERMISSION], so the
 * system grants them to this start too, and keeps them until the start is stopped. Each start holds its ingest
 * ([HandoffIngests.Ingest.hold]) until it settles (the local copies and the display previews of grant-only documents),
 * then lets go; the activity can finish at once (back returns to the sender, a silent batch member leaves immediately).
 *
 * Starts are stopped in order ([GrantStarts]: `stopSelfResult(id)` drops every delivered start up to `id` with its
 * grants), so a later start that settles first waits for the earlier ones. A dataSync foreground service: a progress
 * notification, no Cancel (it only lasts while copies run). It is never a background service: a start that cannot go
 * to the foreground settles at once, and the system's time limit ([onTimeout], Android 15) settles every start and
 * stops it; the copies left then lose the grant and are reported as not copied. After the process died, a redelivered
 * start receives its request again from the intent it carries (with the grant): copies already made are reused by
 * fingerprint.
 */
@AndroidEntryPoint
class HandoffGrantService : Service() {
    @Inject
    lateinit var ingests: HandoffIngests

    private val scope = MainScope()

    private val starts = GrantStarts(stopUpTo = { stopSelfResult(it) }, stopAll = { stopSelf() })

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starts.begin(startId)
        val key = intent?.getStringExtra(EXTRA_KEY)
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(this, intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0, intent?.getStringExtra(EXTRA_SENDER)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
        if (foreground.isFailure) {
            Log.w(TAG, "hand-off grant service not in the foreground", foreground.exceptionOrNull())
            val ingest = key?.let(ingests::attach)
            ingest?.let(::report)
            starts.refused(startId, ingest?.takeHandOver())
            return START_NOT_STICKY
        }
        val ingest = key?.let { ingests.attach(it) ?: redelivered(intent, it, flags) }
        ingest?.let(::report)
        ingest?.let { starts.hold(startId, it.takeHandOver()) }
        if (ingest == null || ingest.settled) {
            starts.settle(startId)
        } else {
            scope.launch {
                runCatching { ingest.awaitSettled() }
                starts.settle(startId)
            }
        }
        // Redelivered after a kill: the grant of an undelivered start is kept with its intent.
        return START_REDELIVER_INTENT
    }

    /**
     * A redelivered start whose ingest died with the process: receives its request again from the activity's intent
     * this start carries, under the start's grant (the receiving activity, its first holder, is gone).
     */
    private fun redelivered(intent: Intent, key: String, flags: Int): HandoffIngests.Ingest? {
        val request = IntentCompat.getParcelableExtra(intent, EXTRA_REQUEST, Intent::class.java)
            ?.takeIf { flags and START_FLAG_REDELIVERY != 0 }
            ?.let(HandoffIntents::parse) ?: return null
        Log.i(TAG, "hand-off received again after the process died")
        val ingest = ingests.receive(
            key,
            request,
            intent.getStringExtra(EXTRA_BATCH),
            intent.getBooleanExtra(EXTRA_HOARD_MEDIA, false),
        )
        // This start holds the grant from now on; the activity that would have been the first holder is gone.
        ingest.handOver()
        ingest.holderGone()
        return ingest
    }

    /** What [ingest] settled with is shown once (unless the activity did), even after this service stopped. */
    private fun report(ingest: HandoffIngests.Ingest) {
        val app = applicationContext
        ingests.whenSettled(ingest) { settled ->
            if (ingest.claimSettledNotice()) {
                HandoffNotices.texts(app, settled).forEach { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
            }
            if (ingest.silent) ingests.done(ingest.key)
        }
    }

    /** Android 15's time limit for dataSync services: every start settles and the service stops now. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "hand-off grant service reached its time limit: ${starts.pending.size} starts let go")
        starts.timeout()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RevealHandoff"
        private const val EXTRA_KEY = "dev.hfps.reveal.handoff.INGEST"
        private const val EXTRA_COUNT = "dev.hfps.reveal.handoff.COUNT"
        private const val EXTRA_SENDER = "dev.hfps.reveal.handoff.SENDER"
        private const val EXTRA_REQUEST = "dev.hfps.reveal.handoff.REQUEST"
        private const val EXTRA_BATCH = "dev.hfps.reveal.handoff.BATCH"
        private const val EXTRA_HOARD_MEDIA = "dev.hfps.reveal.handoff.HOARD_MEDIA"
        private const val CHANNEL = "handoff"
        private const val NOTIFICATION_ID = 4401

        /**
         * Hands [request]'s documents (the intent's URIs, which the caller holds a read grant on) and [ingest] to the
         * service, which holds the grant until the ingest settles. [needed]: the documents that need the grant after
         * the activity is gone ([HandoffGrants.needing]); none = no service. [sender]: the sending app's name for the
         * notification, or null (neutral). False when not started (nothing needed, or the system refused the start:
         * background-start restrictions); the caller then keeps the grant itself as long as it can.
         */
        @Suppress("LongParameterList")
        fun start(
            context: Context,
            ingest: HandoffIngests.Ingest,
            request: Intent,
            uris: List<Uri>,
            needed: Int,
            sender: String?,
        ): Boolean {
            if (uris.isEmpty() || needed == 0) return false
            val handed = ingest.handOver()
            val clip = ClipData.newRawUri(null, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            val intent = Intent(context, HandoffGrantService::class.java)
                .putExtra(EXTRA_KEY, ingest.key)
                .putExtra(EXTRA_COUNT, needed)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_REQUEST, request)
                .putExtra(EXTRA_BATCH, ingest.batchKey)
                .putExtra(EXTRA_HOARD_MEDIA, ingest.hoardMedia)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = clip
            return runCatching { context.startForegroundService(intent) }
                .onFailure {
                    Log.w(TAG, "hand-off grant service refused: ${it.javaClass.simpleName}")
                    handed()
                }
                .isSuccess
        }

        private fun notification(context: Context, count: Int, sender: String?): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.handoff_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val title = if (sender != null) {
                context.resources.getQuantityString(R.plurals.handoff_copying_from, count, count, sender)
            } else {
                context.resources.getQuantityString(R.plurals.handoff_copying, count, count)
            }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}

/** The texts of what a hand-off settled with: originals not kept for lack of space, copies the grant refused. */
object HandoffNotices {
    fun texts(context: Context, settled: HandoffRepository.Settled): List<String> = buildList {
        if (settled.noSpace > 0) {
            add(context.resources.getQuantityString(R.plurals.handoff_no_space, settled.noSpace, settled.noSpace))
        }
        if (settled.notCopied > 0) {
            add(
                context.resources.getQuantityString(
                    R.plurals.handoff_not_copied,
                    settled.notCopied,
                    settled.notCopied,
                ),
            )
        }
    }
}
