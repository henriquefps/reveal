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
import dagger.hilt.android.AndroidEntryPoint
import dev.hfps.reveal.R
import dev.hfps.reveal.data.handoff.HandoffIngests
import dev.hfps.reveal.data.handoff.HandoffRepository
import java.util.TreeMap
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds a hand-off's read grant while the work that needs it finishes (ADR 0011 "Amendment W4", "Grant service"):
 * the activity that received the intent starts it with the intent's URIs as [ClipData] and
 * [Intent.FLAG_GRANT_READ_URI_PERMISSION], so the system grants them to this start too, and keeps them until the start
 * is stopped. Each start holds its ingest ([HandoffIngests.Ingest.hold]) until it settles (the local copies and the
 * display previews of grant-only documents), then lets go; the activity can finish at once (back returns to the
 * sender, a silent batch member leaves immediately).
 *
 * Starts are stopped in order (`stopSelfResult(id)` drops every delivered start up to `id` with its grants), so a
 * later start that settles first waits for the earlier ones. A dataSync foreground service: a progress notification,
 * no Cancel (it only lasts while copies run).
 */
@AndroidEntryPoint
class HandoffGrantService : Service() {
    @Inject
    lateinit var ingests: HandoffIngests

    private val scope = MainScope()

    /** Start id -> settled; the starts not yet stopped. */
    private val starts = TreeMap<Int, Boolean>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(this, count),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
        foreground.exceptionOrNull()?.let { Log.w(TAG, "hand-off grant service not in the foreground", it) }
        starts[startId] = false
        val ingest = intent?.getStringExtra(EXTRA_KEY)?.let(ingests::attach)
        if (ingest == null || ingest.settled) {
            // Nothing to wait for (a redelivery after the process died: its ingest is gone).
            settle(startId)
            return START_REDELIVER_INTENT
        }
        val release = ingest.takeHandOver()
        scope.launch {
            val settled = runCatching { ingest.awaitSettled() }.getOrNull()
            release()
            if (settled != null && ingest.claimSettledNotice()) {
                HandoffNotices.texts(this@HandoffGrantService, settled).forEach {
                    Toast.makeText(this@HandoffGrantService, it, Toast.LENGTH_LONG).show()
                }
            }
            if (ingest.silent) ingests.done(ingest.key)
            settle(startId)
        }
        // Redelivered after a kill: the grant of an undelivered start is kept with its intent; settled at once then.
        return START_REDELIVER_INTENT
    }

    /** [startId] settled: stops every start up to the last one whose earlier starts all settled. */
    private fun settle(startId: Int) {
        starts[startId] = true
        var last: Int? = null
        for ((id, done) in starts) {
            if (!done) break
            last = id
        }
        val upTo = last ?: return
        starts.headMap(upTo, true).clear()
        stopSelfResult(upTo)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RevealHandoff"
        private const val EXTRA_KEY = "dev.hfps.reveal.handoff.INGEST"
        private const val EXTRA_COUNT = "dev.hfps.reveal.handoff.COUNT"
        private const val CHANNEL = "handoff"
        private const val NOTIFICATION_ID = 4401

        /**
         * Hands [uris] (the intent's documents, which the caller holds a read grant on) and [ingest] to the
         * service, which holds the grant until the ingest settles. False when the system refused the start
         * (background-start restrictions): the caller then keeps the grant itself as long as it can.
         */
        fun start(context: Context, ingest: HandoffIngests.Ingest, uris: List<Uri>): Boolean {
            if (uris.isEmpty()) return false
            val key = ingest.key
            val handed = ingest.handOver()
            val clip = ClipData.newRawUri(null, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            val intent = Intent(context, HandoffGrantService::class.java)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_COUNT, uris.size)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = clip
            return runCatching { context.startForegroundService(intent) }
                .onFailure {
                    Log.w(TAG, "hand-off grant service refused: ${it.javaClass.simpleName}")
                    handed()
                }
                .isSuccess
        }

        private fun notification(context: Context, count: Int): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.handoff_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.resources.getQuantityString(R.plurals.handoff_copying, count, count))
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
