package dev.hfps.reveal.data.handoff

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.xmp.XmpFields

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
    /** The sender's culling decisions per item (ADR 0011 "Culling"), keyed by URIs of [uris]; usually empty. */
    val culling: Map<Uri, HandoffCulling> = emptyMap(),
)

/**
 * What the sender (Hoard) decided about one item when it handed it over (ADR 0011 "Culling"). Each field is optional:
 * null = not sent, the photo keeps what it has. [label] `""` clears the colour label.
 */
data class HandoffCulling(val rating: Int? = null, val flag: PickFlag? = null, val label: String? = null) {
    val isEmpty: Boolean get() = rating == null && flag == null && label == null
}

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

    /**
     * Optional `Bundle`: culling decisions per item (ADR 0011 "Culling"). Key: an item's URI exactly as sent
     * (`uri.toString()`); value: a `Bundle` with [KEY_RATING], [KEY_FLAG] and/or [KEY_LABEL].
     */
    const val EXTRA_CULLING = "dev.hfps.reveal.extra.CULLING"

    /** Optional `Int`: the version of the [EXTRA_CULLING] layout the sender wrote (absent = 1). */
    const val EXTRA_CULLING_VERSION = "dev.hfps.reveal.extra.CULLING_VERSION"

    /** The [EXTRA_CULLING] layout this Reveal writes the spec of; later versions only add keys. */
    const val CULLING_VERSION = 1

    /** `Int` 0..5: star rating. */
    const val KEY_RATING = "rating"

    /** `String` `pick` | `reject` | `none` (case-insensitive). */
    const val KEY_FLAG = "flag"

    /** `String`: colour label (`xmp:Label`, e.g. `Red`); `""` clears it. At most 64 characters. */
    const val KEY_LABEL = "label"

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
            culling = runCatching { culling(intent, uris) }.getOrDefault(emptyMap()),
        )
    }

    /**
     * The [EXTRA_CULLING] entries of URIs among [uris] (others are ignored), with invalid values dropped field by
     * field: a rating outside 0..5, an unknown flag, a value of the wrong type. A version below 1 is not this contract.
     */
    private fun culling(intent: Intent, uris: List<Uri>): Map<Uri, HandoffCulling> {
        val bundle = intent.getBundleExtra(EXTRA_CULLING)
        if (bundle == null || intent.getIntExtra(EXTRA_CULLING_VERSION, 1) < 1) return emptyMap()
        val byText = uris.associateBy { it.toString() }
        return bundle.keySet().mapNotNull { key ->
            val uri = byText[key]
            val values = uri?.let { runCatching { bundle.getBundle(key) }.getOrNull() }?.let(::item)
            if (values == null || values.isEmpty) null else uri to values
        }.toMap()
    }

    private fun item(item: Bundle): HandoffCulling {
        val rating = item.typed<Int>(KEY_RATING)?.takeIf { it in 0..XmpFields.MAX_RATING }
        val flag = when (item.typed<String>(KEY_FLAG)?.trim()?.lowercase()) {
            "pick" -> PickFlag.PICK
            "reject" -> PickFlag.REJECT
            "none" -> PickFlag.NONE
            else -> null
        }
        val label = item.typed<String>(KEY_LABEL)?.trim()?.take(MAX_LABEL)
        return HandoffCulling(rating, flag, label)
    }

    /**
     * The [EXTRA_CULLING] value for [values] exactly as a sender builds it (the spec of ADR 0011 in code; Reveal's own
     * tests send it). Absent fields are left out; a cleared label is `""`.
     */
    fun cullingExtra(values: Map<Uri, HandoffCulling>): Bundle = Bundle().apply {
        for ((uri, culling) in values) {
            putBundle(
                uri.toString(),
                Bundle().apply {
                    culling.rating?.let { putInt(KEY_RATING, it) }
                    culling.flag?.let { putString(KEY_FLAG, it.name.lowercase()) }
                    culling.label?.let { putString(KEY_LABEL, it) }
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Bundle.typed(key: String): T? = runCatching { get(key) as? T }.getOrNull()

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
    private const val MAX_LABEL = 64
}
