package dev.hfps.reveal.data.handoff

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** The app that handed a document over, named in "share it to Reveal again from …" (ADR 0011 "Fallbacks"). */
data class SenderApp(val packageName: String, val label: String, val launch: Intent)

/**
 * Finds the sender of a hand-off from its URI: the app whose content provider serves it (Hoard's stream provider, a
 * cloud app's documents). Only an app with a launcher entry counts, so the name is one the owner recognises and the
 * editor can open it; MediaStore and system document providers have none (the gallery that shared a MediaStore URI is
 * unknown: the editor offers gallery access instead). The manifest's `<queries>` for launcher apps makes them visible.
 */
object HandoffApps {
    fun sender(packageManager: PackageManager, uri: Uri, ownPackage: String): SenderApp? = runCatching {
        val authority = uri.authority ?: return null
        if (GalleryAccess.isMediaStore(uri.toString())) return null
        val app = packageManager.resolveContentProvider(authority, 0)?.applicationInfo ?: return null
        if (app.packageName == ownPackage) return null
        val launch = packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        SenderApp(app.packageName, packageManager.getApplicationLabel(app).toString(), launch)
    }.getOrNull()
}
