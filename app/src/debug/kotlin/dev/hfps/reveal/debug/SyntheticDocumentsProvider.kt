package dev.hfps.reveal.debug

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Debug builds only: a Storage Access Framework provider over `files/saf-test/` of the app, so instrumented tests
 * (and a person on an emulator) can add a library folder full of generated files without touching shared storage.
 * It runs in its own process (`:documents`), so every listing and read crosses a binder like a real provider.
 * Document ids are paths relative to that directory (`""` = the root).
 */
class SyntheticDocumentsProvider : DocumentsProvider() {
    private val base: File get() = File(checkNotNull(context).filesDir, DIRECTORY)

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        base.mkdirs()
        newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT)
            add(Root.COLUMN_TITLE, "Reveal test files")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addRow(this, documentId, file(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        file(parentDocumentId).listFiles()?.forEach { child -> addRow(cursor, id(child), child) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        var target = File(parent, displayName)
        var n = 1
        while (target.exists()) {
            target =
                File(parent, "${displayName.substringBeforeLast('.')} (${n++}).${displayName.substringAfterLast('.')}")
        }
        if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        return id(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId.isEmpty() || documentId.startsWith("$parentDocumentId/")

    private fun file(documentId: String): File {
        val file = if (documentId.isEmpty()) base else File(base, documentId)
        if (!file.canonicalPath.startsWith(base.canonicalPath)) throw FileNotFoundException(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun id(file: File): String = file.relativeTo(base).path

    private fun addRow(cursor: MatrixCursor, id: String, file: File) {
        val mime = if (file.isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        }
        val flags = if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (id.isEmpty()) DIRECTORY else file.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    companion object {
        const val AUTHORITY = "dev.hfps.reveal.debug.synthetic"
        const val DIRECTORY = "saf-test"
        const val ROOT_ID = "synthetic"
        const val ROOT_DOCUMENT = ""

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        /** The tree URI of a sub-folder of `files/saf-test` ("" = all of it), as `ACTION_OPEN_DOCUMENT_TREE` returns. */
        fun treeUri(folder: String) = DocumentsContract.buildTreeDocumentUri(AUTHORITY, folder)
    }
}
