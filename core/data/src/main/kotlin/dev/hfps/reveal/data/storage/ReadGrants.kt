package dev.hfps.reveal.data.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process

/**
 * Whether this process holds an explicit read grant on [uri] of another app's provider (ADR 0018 "W3/C"): a persisted
 * grant, or the sender's temporary one (`checkUriPermission` answers from the system's grant table: the provider is
 * never called, no file is opened). Documents of Reveal's own providers answer false (their access is decided by the
 * provider itself, as the debug provider's "ended grants" are), and so does anything that is not `content:`.
 */
fun readGrants(context: Context): (Uri) -> Boolean {
    val own = context.packageName
    val owners = java.util.concurrent.ConcurrentHashMap<String, String>()
    // The persisted grants, read once per [PERSISTED_TTL_MS] (one IPC, not one per document of a pass).
    var persistedAt = 0L
    var persisted: Set<Uri> = emptySet()
    return grant@{ uri ->
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@grant false
        val authority = uri.authority ?: return@grant false
        val owner = owners.getOrPut(authority) {
            runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
                .orEmpty()
        }
        if (owner.isEmpty() || owner == own) return@grant false
        val now = System.nanoTime() / NANOS_PER_MS
        if (now - persistedAt > PERSISTED_TTL_MS) {
            persisted = runCatching {
                context.contentResolver.persistedUriPermissions.filter {
                    it.isReadPermission
                }.mapTo(HashSet()) { it.uri }
            }.getOrDefault(emptySet())
            persistedAt = now
        }
        uri in persisted || context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private const val PERSISTED_TTL_MS = 10_000L
private const val NANOS_PER_MS = 1_000_000L
