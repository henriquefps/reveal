package dev.hfps.reveal.data.handoff

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * What another app asked Reveal to open (ADR 0011): the content URIs in the order the sender gave them, the optional
 * title of the batch, the item to open first, and whether the sender offered persistable grants.
 */
data class HandoffRequest(
    val uris: List<Uri>,
    val title: String? = null,
    /** The URI to open in the editor first; null = the editor for a single photo, the library for several. */
    val initial: Uri? = null,
    /** `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` was set: [HandoffRepository] keeps the grants across restarts. */
    val persistable: Boolean = false,
    val action: String? = null,
)

/**
 * Parses the intents Reveal accepts (ADR 0011): the explicit [ACTION_DEVELOP] and the standard `ACTION_VIEW`,
 * `ACTION_EDIT`, `ACTION_SEND`, `ACTION_SEND_MULTIPLE`. URIs are collected from the intent data, every `ClipData`
 * item, and `EXTRA_STREAM` (one URI or a list), in that order, without duplicates; only `content:` and `file:` URIs
 * count. Never throws on a malformed extra.
 */
object HandoffIntents {
    /** Explicit hand-off action: `ClipData` of content URIs with read (and write, for sidecars) grants. */
    const val ACTION_DEVELOP = "dev.hfps.reveal.action.DEVELOP"

    /** Optional `String`: a title for the batch, shown as the group header in "Opened from other apps". */
    const val EXTRA_TITLE = "dev.hfps.reveal.extra.TITLE"

    /** Optional `Uri`: the item (one of the URIs sent) to open in the editor first. */
    const val EXTRA_INITIAL_URI = "dev.hfps.reveal.extra.INITIAL_URI"

    private val actions = setOf(
        ACTION_DEVELOP,
        Intent.ACTION_VIEW,
        Intent.ACTION_EDIT,
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
    )
    private val schemes = setOf("content", "file")

    fun parse(intent: Intent?): HandoffRequest? {
        val handoff = intent?.takeIf { it.action in actions }
        val uris = handoff?.let(::collectUris).orEmpty()
        if (intent == null || uris.isEmpty()) return null
        val initial = runCatching { IntentCompat.getParcelableExtra(intent, EXTRA_INITIAL_URI, Uri::class.java) }
            .getOrNull()?.takeIf { it in uris }
        val title = runCatching { intent.getStringExtra(EXTRA_TITLE) }.getOrNull()?.trim()
        return HandoffRequest(
            uris = uris,
            title = title?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE),
            initial = initial,
            persistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0,
            action = intent.action,
        )
    }

    private fun collectUris(intent: Intent): List<Uri> {
        val found = LinkedHashSet<Uri>()
        intent.data?.let(found::add)
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(found::add)
        }
        runCatching { IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }
            .getOrNull()?.let(found::add)
        runCatching { IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }
            .getOrNull()?.forEach(found::add)
        return found.filter { it.scheme in schemes }
    }

    private const val MAX_TITLE = 120
}
