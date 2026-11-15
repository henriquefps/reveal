package dev.hfps.reveal.data.library

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity

/**
 * The folder grants of Export XMP (ADR 0026 "Export XMP"): a folder the user picks gets a persisted READ|WRITE grant,
 * so a job resumed after a kill or a reboot still writes there; it is released when the job ends (done, cancelled by
 * the user, failed) unless something else still uses that tree: another export job ([release]'s `inUse`), a library
 * folder (which keeps its READ grant, and its WRITE when it had one before), the image export's remembered folder, or
 * an export preset.
 */
class XmpGrants(
    private val resolver: ContentResolver,
    private val db: RevealDatabase,
    /** The image export's remembered folder (`ExportPreferences.folderUri`). */
    private val exportFolder: () -> String?,
) {
    /** Takes a persisted READ|WRITE grant on [treeUri]; false when the provider has none to give (may still work). */
    fun take(treeUri: String): Boolean = runCatching {
        resolver.takePersistableUriPermission(treeUri.toUri(), READ_WRITE)
    }.isSuccess

    /** Releases [take]'s grant on [treeUri] unless another use holds it (see the class). */
    @Suppress("ReturnCount")
    suspend fun release(treeUri: String, inUse: Set<String> = emptySet()) {
        if (treeUri in inUse || treeUri == exportFolder()) return
        if (db.versionDao().exportPresetsMentioning(treeUri) > 0) return
        val source = db.sourceDao().byTree(treeUri)
        val flags = when {
            source == null -> READ_WRITE

            source.writable -> return

            // A library folder keeps the READ grant it was added with.
            else -> Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        runCatching { resolver.releasePersistableUriPermission(treeUri.toUri(), flags) }
    }

    companion object {
        private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /**
         * Whether the tree the user picked ([pickedTree]) is, or contains, [source]'s folder: "next to the photos"
         * writes through it into the source's own document ids, which only resolve inside it. Same provider, and the
         * source's root document is the picked one or below it (`primary:` / `primary:DCIM` / `primary:DCIM/Shoot`);
         * [isChild] asks the provider when the ids are not path-like.
         */
        @Suppress("ReturnCount")
        fun covers(
            pickedTree: String,
            source: SourceEntity,
            isChild: (pickedTree: Uri, sourceRoot: String) -> Boolean = { _, _ -> false },
        ): Boolean {
            val picked = pickedTree.toUri()
            val own = source.treeUri.toUri()
            if (picked.authority != own.authority) return false
            val pickedRoot = runCatching { DocumentsContract.getTreeDocumentId(picked) }.getOrNull() ?: return false
            val root = source.rootDocumentId.ifEmpty {
                runCatching { DocumentsContract.getTreeDocumentId(own) }.getOrNull() ?: return false
            }
            val prefix = if (pickedRoot.endsWith(':') || pickedRoot.endsWith('/')) pickedRoot else "$pickedRoot/"
            return root == pickedRoot || root.startsWith(prefix) ||
                runCatching { isChild(picked, root) }.getOrDefault(false)
        }

        /** [covers]' provider question through [resolver] (`DocumentsContract.isChildDocument`). */
        fun providerChild(resolver: ContentResolver): (Uri, String) -> Boolean = { picked, root ->
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                picked,
                DocumentsContract.getTreeDocumentId(picked),
            )
            val child = DocumentsContract.buildDocumentUriUsingTree(picked, root)
            DocumentsContract.isChildDocument(resolver, parent, child)
        }
    }
}
