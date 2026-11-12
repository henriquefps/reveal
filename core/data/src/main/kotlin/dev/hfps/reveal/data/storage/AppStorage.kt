package dev.hfps.reveal.data.storage

import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.handoff.LocalPreviews
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How much Reveal stores, by kind, in bytes (Settings -> Storage). */
data class StorageUsage(
    val bytes: Map<StorageKind, Long>,
    /** Of the hand-off previews, the bytes "Clear cache" keeps: they are a photo's only readable copy. */
    val keptPreviews: Long,
) {
    operator fun get(kind: StorageKind): Long = bytes[kind] ?: 0L

    val total: Long get() = bytes.values.sum()

    /** What "Clear cache" would free (about: other caches written in the last minutes are kept). */
    val clearable: Long
        get() = get(StorageKind.THUMBNAILS) + get(StorageKind.SERVER_FILES) + get(StorageKind.OTHER) +
            (get(StorageKind.HANDOFF_PREVIEWS) - keptPreviews).coerceAtLeast(0)
}

/**
 * Reveal's own storage (ADR 0018 amendment FIX/W, "Storage"): what it uses by kind, and "Clear cache". Reveal never
 * copies or moves photos; everything here is derived (thumbnails, previews, parts of server files) except the library
 * database (edits) and the hand-off previews that are a photo's only copy, which "Clear cache" never touches.
 *
 * [cacheDir] is the app's cache; the [budget]'s areas own [areaDirs] in it. [noBackupDir] holds the hand-off previews
 * ([previews]) and the smart previews being uploaded ([uploadsDir]). [database] lists the library database's files.
 */
@Suppress("LongParameterList")
class AppStorage(
    private val db: RevealDatabase,
    private val budget: CacheBudget,
    private val previews: LocalPreviews?,
    private val cacheDir: File,
    private val uploadsDir: File?,
    private val database: () -> List<File>,
    private val areaDirs: Set<String> = AREA_DIRS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val areas = budget.areas().groupBy { it.kind }
        val bytes = LinkedHashMap<StorageKind, Long>()
        for (kind in listOf(StorageKind.THUMBNAILS, StorageKind.SERVER_FILES)) {
            bytes[kind] = areas[kind].orEmpty().sumOf { area -> area.items().sumOf { it.bytes } }
        }
        val local = previewFiles()
        bytes[StorageKind.HANDOFF_PREVIEWS] = local.sumOf { it.file.length() }
        bytes[StorageKind.OTHER] = otherFiles().sumOf { it.length() } + (uploadsDir?.let(::sizeOf) ?: 0L)
        bytes[StorageKind.LIBRARY] = database().filter { it.isFile }.sumOf { it.length() }
        StorageUsage(bytes, keptPreviews = local.filter { it.keep }.sumOf { it.file.length() })
    }

    /**
     * Empties every cache: thumbnails, server file parts and previews, other cached files not written in the last
     * [RECENT_MS] (an export in progress), and the hand-off previews that are not a photo's only copy (their photo is
     * gone, or the server holds its original). Never the database, never an edit. Returns the bytes freed.
     */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = usage()
        for (area in budget.areas()) area.clear()
        val recent = clock() - RECENT_MS
        for (file in otherFiles()) if (file.lastModified() < recent) file.delete()
        cacheDir.listFiles()?.filter { it.isDirectory && it.name !in areaDirs }?.forEach(::deleteEmptyDirs)
        for (preview in previewFiles()) if (!preview.keep) preview.file.delete()
        (before.total - usage().total).coerceAtLeast(0)
    }

    /** A hand-off preview file and whether it must stay (the only readable copy of its photo). */
    private class PreviewFile(val file: File, val keep: Boolean)

    private suspend fun previewFiles(): List<PreviewFile> {
        val files = previews?.dir?.listFiles()?.filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }.orEmpty()
        if (files.isEmpty()) return emptyList()
        // Key -> the server holds a copy; a key with no file left is an orphan (its photo was forgotten).
        val printed = HashMap<String, Boolean>()
        for (f in db.syncDao().printedFiles()) {
            val key = LocalPreviews.key(f.fingerprint)
            printed[key] = printed[key] == true || f.onServer
        }
        return files.map { file ->
            val onServer = printed[file.name.substringBefore('.')]
            PreviewFile(file, keep = onServer == false)
        }
    }

    /** Cached files outside the budget's areas (exports being written, shared files, downloads), recursively. */
    private fun otherFiles(): List<File> = cacheDir.listFiles().orEmpty()
        .filter { it.name !in areaDirs }
        .flatMap { if (it.isDirectory) it.walkTopDown().filter(File::isFile).toList() else listOf(it) }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter(File::isFile).sumOf { it.length() }

    private fun deleteEmptyDirs(dir: File) {
        dir.walkBottomUp().filter { it.isDirectory && it.listFiles().isNullOrEmpty() }.forEach { it.delete() }
    }

    companion object {
        /** The cache directories the budget's areas own (thumbnails, server file parts, server previews). */
        val AREA_DIRS = setOf("thumbs", "sync-originals", "sync-previews")
        const val RECENT_MS = 10 * 60 * 1000L
        private const val TEMP_SUFFIX = ".tmp"
    }
}
