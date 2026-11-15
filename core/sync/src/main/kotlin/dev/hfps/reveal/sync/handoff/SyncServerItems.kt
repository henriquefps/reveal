package dev.hfps.reveal.sync.handoff

import android.util.Log
import dev.hfps.reveal.data.handoff.ServerFileInfo
import dev.hfps.reveal.data.handoff.ServerItemInfo
import dev.hfps.reveal.data.handoff.ServerItemResult
import dev.hfps.reveal.data.handoff.ServerItems
import dev.hfps.reveal.data.handoff.ServerPairing
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.api.ItemRecord
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ServerItems] over the paired server's API (ADR 0011 "Amendment W3/H"): how a hand-off's server references are read,
 * always with Reveal's own pairing and never through the sender.
 */
class SyncServerItems(
    private val pairingOf: () -> ServerPairing?,
    /** The client of the current pairing, null when not paired. */
    private val api: () -> SyncApi?,
    /** Pulls the culling records and applies them. */
    private val pull: suspend () -> Unit,
) : ServerItems {
    constructor(sync: SyncRepository) : this(
        pairingOf = { sync.account.value?.let { ServerPairing(it.serverId, it.serverName, it.deviceId) } },
        api = sync::api,
        pull = sync::pullCulling,
    )

    override fun pairing(): ServerPairing? = pairingOf()

    override suspend fun item(itemId: String): ServerItemResult = withContext(Dispatchers.IO) {
        val client = api() ?: return@withContext ServerItemResult.Unreachable
        try {
            client.item(itemId)?.let(::result) ?: ServerItemResult.Missing
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncHttpException) {
            Log.i(TAG, "item lookup refused: ${e.status} ${e.code}")
            if (e.status == HTTP_NOT_FOUND) ServerItemResult.Missing else ServerItemResult.Unreachable
        } catch (e: IOException) {
            Log.i(TAG, "item lookup failed: ${e.javaClass.simpleName}")
            ServerItemResult.Unreachable
        }
    }

    override suspend fun lookup(fingerprints: List<String>): Map<String, String>? = withContext(Dispatchers.IO) {
        val client = api() ?: return@withContext null
        try {
            fingerprints.chunked(LOOKUP_MAX).flatMap { client.itemsLookup(it).matches }
                .associate { it.fingerprint to it.itemId }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: IOException) {
            null
        }
    }

    override suspend fun pullCulling() {
        try {
            pull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.i(TAG, "culling pull failed: ${e.javaClass.simpleName}")
        }
    }

    private fun result(record: ItemRecord): ServerItemResult {
        if (record.deleted) return ServerItemResult.Gone(record.replacedBy)
        val locations = record.files.flatMap { it.locations }
        val nas = locations.firstOrNull { it.source == "nas" }
        val main = record.files.firstOrNull { it.fingerprint == record.mainFingerprint }
        val directory = main?.locations?.firstOrNull { it.source == "nas" }?.path?.substringBeforeLast('/', "")
            ?: nas?.path?.substringBeforeLast('/', "").orEmpty()
        return ServerItemResult.Found(
            ServerItemInfo(
                itemId = record.id,
                mainFingerprint = record.mainFingerprint,
                files = record.files.map { ServerFileInfo(it.fingerprint, it.role, it.name, it.size, it.format) },
                originalSource = if (nas != null || locations.isEmpty()) "nas" else "backup",
                folder = listOfNotNull(nas?.folderName, directory).filter { it.isNotEmpty() }.joinToString("/"),
                sortTime = record.sortTime,
                width = record.width,
                height = record.height,
                orientation = record.orientation,
            ),
        )
    }

    private companion object {
        const val TAG = "RevealHandoff"
        const val HTTP_NOT_FOUND = 404
        const val LOOKUP_MAX = 1000
    }
}
