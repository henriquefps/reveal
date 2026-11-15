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
    /** Photos the sender's paired server holds, handed over as references (ADR 0011 "Amendment W3/H"); null = none. */
    val server: ServerRefs? = null,
    val title: String? = null,
    /** The URI to open in the editor first; null = the editor for a single photo, the library for several. */
    val initial: Uri? = null,
    /** `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` was set: [HandoffRepository] keeps the grants across restarts. */
    val persistable: Boolean = false,
    val action: String? = null,
    /** The sender's culling decisions per item (ADR 0011 "Culling"), keyed by URIs of [uris]; usually empty. */
    val culling: Map<Uri, HandoffCulling> = emptyMap(),
)

/** One shot the sender's server holds (ADR 0011 "Amendment W3/H"): the item id and what helps to find it again. */
data class ServerItemRef(
    val itemId: String,
    /** The v1 fingerprint of the main file, when the sender had it. */
    val fingerprint: String? = null,
    /** The v1 fingerprints of every member file (RAW, JPEG, ...), when the sender had them. */
    val fingerprints: List<String> = emptyList(),
    /** The main file's name, for display before the server's record arrives. */
    val name: String? = null,
) {
    /** Every fingerprint the sender named, main first, without duplicates. */
    val allFingerprints: List<String> get() = (listOfNotNull(fingerprint) + fingerprints).distinct()
}

/** The server references of a request: whose server, which items, and the one to open at once. */
data class ServerRefs(
    val serverId: String,
    val items: List<ServerItemRef>,
    /** An item id among [items] to open in the editor at once; wins over [HandoffRequest.initial]. */
    val initialItem: String? = null,
)

/**
 * What the sender (Hoard) decided about one item when it handed it over (ADR 0011 "Culling"). Each field is optional:
 * null = not sent, the photo keeps what it has. [label] `""` clears the colour label. The `*At` times (Unix ms, when
 * the decision was made; a sender may add them, ADR 0027) date the change Reveal pushes to the sync server; without
 * one the hand-off's time is used.
 */
data class HandoffCulling(
    val rating: Int? = null,
    val flag: PickFlag? = null,
    val label: String? = null,
    val ratingAt: Long? = null,
    val flagAt: Long? = null,
    val labelAt: Long? = null,
    /** `CULLING_VERSION` 2: the server deviceId of the author of each field (sender paired), null = not sent. */
    val ratingDevice: String? = null,
    val flagDevice: String? = null,
    val labelDevice: String? = null,
) {
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
    const val CULLING_VERSION = 2

    /** Optional `Int`: layout version of the server-reference extras (absent = no server references). */
    const val EXTRA_SERVER_REF_VERSION = "dev.hfps.reveal.extra.SERVER_REF_VERSION"

    /** The [EXTRA_SERVER_REF_VERSION] this Reveal reads. */
    const val SERVER_REF_VERSION = 1

    /** `String`: the `serverId` of the server the sender is paired with. Required with [EXTRA_SERVER_ITEMS]. */
    const val EXTRA_SERVER_ID = "dev.hfps.reveal.extra.SERVER_ID"

    /** `ArrayList<Bundle>`: one bundle per shot the server holds, see [KEY_ITEM_ID]. At most [MAX_SERVER_ITEMS]. */
    const val EXTRA_SERVER_ITEMS = "dev.hfps.reveal.extra.SERVER_ITEMS"

    /** Optional `String`: an item id among [EXTRA_SERVER_ITEMS] to open in the editor at once. */
    const val EXTRA_INITIAL_ITEM = "dev.hfps.reveal.extra.INITIAL_ITEM"

    /** Item bundle keys: `String` id (required), `String` fingerprint, `String[]` fingerprints, `String` name. */
    const val KEY_ITEM_ID = "itemId"
    const val KEY_ITEM_FINGERPRINT = "fingerprint"
    const val KEY_ITEM_FINGERPRINTS = "fingerprints"
    const val KEY_ITEM_NAME = "name"

    /** Server items read per intent; a bigger batch is several intents with the same title (the Binder limit). */
    const val MAX_SERVER_ITEMS = 500

    /** `Int` 0..5: star rating. */
    const val KEY_RATING = "rating"

    /** `String` `pick` | `reject` | `none` (case-insensitive). */
    const val KEY_FLAG = "flag"

    /** `String`: colour label (`xmp:Label`, e.g. `Red`); `""` clears it. At most 64 characters. */
    const val KEY_LABEL = "label"

    /**
     * Optional `Long`s (any [EXTRA_CULLING_VERSION]): when each decision was made, Unix ms.
     * Read only together with their field; a value ≤ 0 is ignored.
     */
    const val KEY_RATING_AT = "ratingModifiedAt"
    const val KEY_FLAG_AT = "flagModifiedAt"
    const val KEY_LABEL_AT = "labelModifiedAt"

    /** Optional `String`s (version 2): the server deviceId that made each decision, sent only by a paired sender. */
    const val KEY_RATING_DEVICE = "ratingDevice"
    const val KEY_FLAG_DEVICE = "flagDevice"
    const val KEY_LABEL_DEVICE = "labelDevice"

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
        val server = handoff?.let(::serverRefs)
        if (intent == null || (uris.isEmpty() && server == null)) return null
        val initial = runCatching { IntentCompat.getParcelableExtra(intent, EXTRA_INITIAL_URI, Uri::class.java) }
            .getOrNull()?.takeIf { it in uris }
        val title = runCatching { intent.getStringExtra(EXTRA_TITLE) }.getOrNull()?.trim()
        return HandoffRequest(
            uris = uris,
            server = server,
            title = title?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE),
            initial = initial,
            persistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0,
            action = intent.action,
            culling = runCatching { culling(intent, uris) }.getOrDefault(emptyMap()),
        )
    }

    /**
     * The server references of [intent] (ADR 0011 "Amendment W3/H"), or null when there is nothing usable: no or an
     * unknown [EXTRA_SERVER_REF_VERSION], no [EXTRA_SERVER_ID], no valid item. Items are read in order; one that is
     * not a `Bundle`, has no `itemId` or repeats one is skipped (the others stay); at most [MAX_SERVER_ITEMS] count.
     * Optional fields of the wrong type or too long are dropped one by one.
     */
    private fun serverRefs(intent: Intent): ServerRefs? = runCatching {
        val version = intent.getIntExtra(EXTRA_SERVER_REF_VERSION, 0)
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
        if (version < 1 || serverId == null) return@runCatching null
        val raw = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_SERVER_ITEMS, Bundle::class.java)
        val seen = HashSet<String>()
        val items = raw.orEmpty().asSequence().filterIsInstance<Bundle>().mapNotNull(::serverItem)
            .filter { seen.add(it.itemId) }.take(MAX_SERVER_ITEMS).toList()
        if (items.isEmpty()) return@runCatching null
        val initial = intent.getStringExtra(EXTRA_INITIAL_ITEM)?.trim()?.takeIf { id -> items.any { it.itemId == id } }
        ServerRefs(serverId, items, initial)
    }.getOrNull()

    private fun serverItem(item: Bundle): ServerItemRef? {
        val id = item.typed<String>(KEY_ITEM_ID)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
            ?: return null
        val print = { value: String? -> value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } }
        return ServerItemRef(
            itemId = id,
            fingerprint = print(item.typed<String>(KEY_ITEM_FINGERPRINT)),
            fingerprints = item.typed<Array<*>>(KEY_ITEM_FINGERPRINTS).orEmpty().filterIsInstance<String>()
                .mapNotNull(print).distinct().take(MAX_FINGERPRINTS),
            name = item.typed<String>(KEY_ITEM_NAME)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME },
        )
    }

    /** The server-reference extras for [refs] exactly as a sender builds them (the spec in code; tests send it). */
    fun serverExtras(intent: Intent, refs: ServerRefs): Intent = intent.apply {
        putExtra(EXTRA_SERVER_REF_VERSION, SERVER_REF_VERSION)
        putExtra(EXTRA_SERVER_ID, refs.serverId)
        putParcelableArrayListExtra(
            EXTRA_SERVER_ITEMS,
            ArrayList(
                refs.items.map { ref ->
                    Bundle().apply {
                        putString(KEY_ITEM_ID, ref.itemId)
                        ref.fingerprint?.let { putString(KEY_ITEM_FINGERPRINT, it) }
                        if (ref.fingerprints.isNotEmpty()) {
                            putStringArray(KEY_ITEM_FINGERPRINTS, ref.fingerprints.toTypedArray())
                        }
                        ref.name?.let { putString(KEY_ITEM_NAME, it) }
                    }
                },
            ),
        )
        refs.initialItem?.let { putExtra(EXTRA_INITIAL_ITEM, it) }
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
        val time = { key: String -> item.typed<Long>(key)?.takeIf { it > 0 } }
        val device = { key: String -> item.typed<String>(key)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } }
        return HandoffCulling(
            rating = rating,
            flag = flag,
            label = label,
            ratingAt = rating?.let { time(KEY_RATING_AT) },
            flagAt = flag?.let { time(KEY_FLAG_AT) },
            labelAt = label?.let { time(KEY_LABEL_AT) },
            ratingDevice = rating?.let { device(KEY_RATING_DEVICE) },
            flagDevice = flag?.let { device(KEY_FLAG_DEVICE) },
            labelDevice = label?.let { device(KEY_LABEL_DEVICE) },
        )
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
                    culling.ratingAt?.let { putLong(KEY_RATING_AT, it) }
                    culling.flagAt?.let { putLong(KEY_FLAG_AT, it) }
                    culling.labelAt?.let { putLong(KEY_LABEL_AT, it) }
                    culling.ratingDevice?.let { putString(KEY_RATING_DEVICE, it) }
                    culling.flagDevice?.let { putString(KEY_FLAG_DEVICE, it) }
                    culling.labelDevice?.let { putString(KEY_LABEL_DEVICE, it) }
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
    private const val MAX_ID = 128
    private const val MAX_NAME = 255
    private const val MAX_FINGERPRINTS = 16
}
