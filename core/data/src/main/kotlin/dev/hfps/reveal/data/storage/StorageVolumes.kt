package dev.hfps.reveal.data.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A storage volume as the sources list shows it. [id] is `primary` or the volume uuid (`1234-ABCD`). */
data class VolumeInfo(val id: String, val description: String, val removable: Boolean, val mounted: Boolean)

/**
 * Storage volumes (internal storage, SD cards, USB drives) and their mount state, from `StorageManager`. A tree of
 * the platform's `ExternalStorageProvider` names its volume in the document id (`primary:DCIM`, `1234-ABCD:DCIM`);
 * trees of other providers have no volume and are probed by listing them.
 */
class StorageVolumes(private val context: Context) {
    private val storage = context.getSystemService(StorageManager::class.java)

    /** Volumes known now (mounted ones and, on some devices, unmounted removable ones). */
    fun volumes(): List<VolumeInfo> = storage.storageVolumes.map { volume ->
        val state = volume.state
        VolumeInfo(
            id = if (volume.isPrimary) PRIMARY else volume.uuid ?: volume.getDescription(context),
            description = volume.getDescription(context),
            removable = volume.isRemovable,
            mounted = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY,
        )
    }

    fun volume(id: String): VolumeInfo? = volumes().firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Emits whenever a volume is mounted, unmounted, ejected or removed (and once at the start). */
    fun changes(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            MEDIA_ACTIONS.forEach(::addAction)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    companion object {
        const val PRIMARY = "primary"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        private val MEDIA_ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
        )

        /** The volume id of a tree of the platform storage provider, else null. */
        fun volumeIdOf(treeUri: Uri): String? = treeUri.takeIf { it.authority == EXTERNAL_STORAGE_AUTHORITY }
            ?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
            ?.substringBefore(':')
            ?.takeIf { it.isNotEmpty() }
    }
}
