package dev.hfps.reveal.data.handoff

/** The paired hoard-server as the hand-off sees it (`:core:sync` implements [ServerItems]). */
data class ServerPairing(val serverId: String, val serverName: String, val deviceId: String)

/** One member file of a server item (Hoard ADR 0014): its v1 fingerprint, role (`raw`, `rendered`, ...), name, size. */
data class ServerFileInfo(
    val fingerprint: String,
    val role: String,
    /** Blank when the server has no name for the file (uploaded originals, hoard-server 0.9.0). */
    val name: String,
    val size: Long = 0,
    /** Content format (`jpeg`, `heif`, `arw`, ...), the extension of a nameless file. */
    val format: String = "",
) {
    /**
     * The file's name: its own, else [fallback] (the hand-off item's name, for the main file), else one made of its
     * fingerprint and format, so it still classifies as an image (`3fa9c1d20b7e.jpg`).
     */
    fun named(fallback: String? = null): ServerFileInfo = if (name.isNotBlank()) {
        this
    } else {
        copy(name = fallback?.takeIf { it.isNotBlank() } ?: "${fingerprint.removePrefix("v1:").take(NAME_HEX)}.${extension()}")
    }

    private fun extension(): String = when (val f = format.lowercase().trim().removePrefix(".")) {
        "jpeg", "jpg", "" -> if (role == "raw" && format.isBlank()) "dng" else "jpg"
        "heif", "heic" -> "heic"
        "tiff", "tif" -> "tif"
        else -> f
    }

    private companion object {
        const val NAME_HEX = 12
    }
}

/** An item of the server (`GET /v1/items/{itemId}`, Hoard ADR 0014): what Reveal needs to file it as a Server photo. */
data class ServerItemInfo(
    val itemId: String,
    val mainFingerprint: String?,
    val files: List<ServerFileInfo>,
    /** `nas` or `backup`: where the server holds the original (any file), for the photo's cloud state. */
    val originalSource: String = "nas",
    /** The NAS folder (name + directory) of the main file, `""` when the item is only an upload. */
    val folder: String = "",
    val sortTime: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = 1,
) {
    /** Every member fingerprint. */
    val fingerprints: List<String> get() = files.map { it.fingerprint }.distinct()
}

/** What the server answered about one item id. */
sealed interface ServerItemResult {
    data class Found(val item: ServerItemInfo) : ServerItemResult

    /** A tombstone; [replacedBy] is the item that took the shot over, when there is one. */
    data class Gone(val replacedBy: String?) : ServerItemResult

    /** 404: the server does not know the id. */
    data object Missing : ServerItemResult

    /** The server did not answer (offline, timeout, 5xx). */
    data object Unreachable : ServerItemResult
}

/**
 * What [HandoffRepository] asks of the sync side to resolve server references (ADR 0011 "Amendment W3/H"). Kept as an
 * interface so `:core:data` stays free of the sync client; `:core:sync` binds the real one. [None] = never paired.
 */
interface ServerItems {
    /** The server this device is paired with, or null. */
    fun pairing(): ServerPairing?

    /** `GET /v1/items/{itemId}`. Never throws: a failure is [ServerItemResult.Unreachable]. */
    suspend fun item(itemId: String): ServerItemResult

    /** `POST /v1/items/lookup`: the item id of each fingerprint the server knows; null when it did not answer. */
    suspend fun lookup(fingerprints: List<String>): Map<String, String>?

    /** Pulls the culling records and applies them to the library (best effort; never throws). */
    suspend fun pullCulling()

    object None : ServerItems {
        override fun pairing(): ServerPairing? = null

        override suspend fun item(itemId: String): ServerItemResult = ServerItemResult.Unreachable

        override suspend fun lookup(fingerprints: List<String>): Map<String, String>? = null

        override suspend fun pullCulling() = Unit
    }
}

/** Why a batch of server references could not be opened (the UI says what to do). */
sealed interface ServerIssue {
    /** Reveal is not paired: pairing with the sender's server opens the batch ([HandoffRepository.resumePending]). */
    data class NotPaired(val count: Int) : ServerIssue

    /** Reveal is paired with another server, [serverName]. */
    data class OtherServer(val serverName: String, val count: Int) : ServerIssue

    /** The server did not answer: the photos open from what Reveal has and fill in when it does. */
    data class Unreachable(val count: Int) : ServerIssue
}
