package dev.hfps.reveal.data.sync

import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ImageKind
import dev.hfps.reveal.data.xmp.SidecarNames
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One folder Hoard mirrors on this phone (Hoard ADR 0024 "`GET /v1/device` gains `physicalDevice.backup`"): its
 * volume (`primary` or an SD card's id) and path on it, the kinds of files it backs up, and whether it is `active`
 * (not paused or stopped, and a live Hoard of this phone reported it in the last 7 days).
 */
@Serializable
data class BackupRoot(
    val id: String,
    val volume: String,
    val path: String,
    /** A subset of `image`, `video`, `raw`, `sidecar`; a file whose kind is not listed is not backed up by Hoard. */
    val types: List<String> = emptyList(),
    val active: Boolean = false,
    val since: Long = 0,
    val app: String? = null,
)

/** Where a folder lives on the phone's storage: an `ExternalStorageProvider` volume and the path on it. */
data class StorageLocation(val volume: String, val path: String) {
    /** The location of [child] (a relative, `/`-separated path) under this one. */
    fun child(child: String): StorageLocation =
        if (child.isEmpty()) this else copy(path = if (path.isEmpty()) child else "$path/$child")
}

/**
 * Which of Reveal's folders and files Hoard's phone backup covers (ADR 0018 "R1", Hoard ADR 0024 "Reveal: one backup
 * per phone"). Pure: the roots come from the sync engine ([META_ROOTS] in `meta`, refreshed with every sync run).
 *
 * - Only `ExternalStorageProvider` trees can be covered (`primary:DCIM/Camera` = volume `primary`, path `DCIM/Camera`);
 *   any other tree (a USB drive's provider, a cloud provider, hand-offs) never is.
 * - A folder is covered by an **active** root of this phone whose volume equals it (any case) and whose path equals the
 *   folder's or is a prefix of it at a `/`.
 * - A file is covered when its folder is and its kind is in that root's `types`.
 */
object BackupCoverage {
    /** `meta`: this phone's backup roots as the server last listed them (JSON list), absent without `backup`. */
    const val META_ROOTS = "sync.backup.roots"

    /** The authority of Android's own storage provider: the only trees Hoard's mirror can name. */
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    const val TYPE_IMAGE = "image"
    const val TYPE_VIDEO = "video"
    const val TYPE_RAW = "raw"
    const val TYPE_SIDECAR = "sidecar"

    /**
     * RAW extensions Android's MediaStore lists as images (its MIME map): the `image` type covers these RAWs too; CR3,
     * among others, only the `raw` type does (Hoard ADR 0024 "`GET /v1/backup/roots`").
     */
    private val MEDIA_STORE_RAWS = setOf("dng", "cr2", "nef", "nrw", "arw", "orf", "pef", "raf", "rw2", "srw")

    /** Stills MediaStore lists, screenshots and stickers included (Hoard 0.12.0 mirrors PNG, WebP and GIF too). */
    private val IMAGE_KINDS = setOf(ImageKind.JPEG, ImageKind.HEIF, ImageKind.PNG, ImageKind.WEBP, ImageKind.GIF)
    private val VIDEOS = setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(BackupRoot.serializer())

    fun encode(roots: List<BackupRoot>): String = json.encodeToString(serializer, roots)

    /** The stored roots; an unreadable value is no roots (nothing covered: Reveal uploads as before). */
    fun decode(text: String?): List<BackupRoot> =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /**
     * The storage location of a folder source: its tree's root document id split at the first `:`, for
     * `ExternalStorageProvider` trees only; null for any other provider.
     */
    @Suppress("ReturnCount")
    fun location(treeUri: String, rootDocumentId: String): StorageLocation? {
        if (!treeUri.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY/")) return null
        val colon = rootDocumentId.indexOf(':')
        if (colon <= 0) return null
        return StorageLocation(rootDocumentId.substring(0, colon), rootDocumentId.substring(colon + 1).trim('/'))
    }

    /** The active root of [roots] that covers the folder at [location], or null. */
    fun rootFor(roots: List<BackupRoot>, location: StorageLocation): BackupRoot? =
        roots.filter { it.active }.firstOrNull { contains(it, location) }

    /** True when [root] holds [location] (inactive roots too: their entries stay on the server). */
    fun contains(root: BackupRoot, location: StorageLocation): Boolean {
        if (!root.volume.equals(location.volume, ignoreCase = true)) return false
        val base = root.path.trim('/')
        val path = location.path.trim('/')
        return base.isEmpty() || path == base || path.startsWith("$base/")
    }

    /**
     * The root covering file [name] in the folder at [location]: the folder is covered and the file's kind is in the
     * root's types (an unknown kind never is). Null = not covered: Reveal uploads it as before.
     */
    fun coveringRoot(roots: List<BackupRoot>, location: StorageLocation, name: String): BackupRoot? {
        val root = rootFor(roots, location) ?: return null
        val type = typesOf(name)
        return root.takeIf { type.any { it in root.types } }
    }

    /**
     * The backup types a file of [name] belongs to (Hoard ADR 0024): stills MediaStore lists are `image` (with the
     * RAWs it knows), every RAW is `raw`, `.xmp` is `sidecar`, videos are `video`; anything else none.
     */
    @Suppress("ReturnCount")
    fun typesOf(name: String): Set<String> {
        if (SidecarNames.isSidecar(name)) return setOf(TYPE_SIDECAR)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in VIDEOS) return setOf(TYPE_VIDEO)
        val type = ImageFormats.classify(name)
        return when {
            type.isRaw && extension in MEDIA_STORE_RAWS -> setOf(TYPE_RAW, TYPE_IMAGE)
            type.isRaw -> setOf(TYPE_RAW)
            type.kind in IMAGE_KINDS -> setOf(TYPE_IMAGE)
            else -> emptySet()
        }
    }
}
