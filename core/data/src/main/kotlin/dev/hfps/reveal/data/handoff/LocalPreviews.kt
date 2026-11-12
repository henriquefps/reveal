package dev.hfps.reveal.data.handoff

import java.io.File
import java.io.FileNotFoundException

/**
 * The local previews of hand-off photos (ADR 0011 "Unreadable hand-offs"), in app storage and keyed by the main file's
 * content fingerprint, so a re-share under another URI (the same photo, ADR 0006 "Identity") finds them again:
 *
 * - `<key>.jpg`: the display image (long edge [DISPLAY_EDGE]) for the grid and the viewer, for every hand-off;
 * - `<key>.rvsp`: a smart preview (`SmartPreviewCodec`, the same ~2560 px one sync makes) to edit and export, for a
 *   hand-off whose grant is temporary (the sender offered no persistable grant, as the Samsung Gallery's `SEND`).
 *
 * Smart previews are capped at [maxBytes] in all (the least recently used go first: the display image stays). A photo
 * forgotten by the hand-off retention or merged into a folder photo loses its previews.
 */
class LocalPreviews(val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    fun displayFile(fingerprint: String): File = File(dir, key(fingerprint) + DISPLAY_SUFFIX)

    fun smartFile(fingerprint: String): File = File(dir, key(fingerprint) + SMART_SUFFIX)

    /** The display image of [fingerprint], or null. */
    fun display(fingerprint: String?): File? = fingerprint?.let(::displayFile)?.takeIf { it.isFile }

    /** The smart preview of [fingerprint], or null; marks it used (the cap keeps recently used ones). */
    fun smart(fingerprint: String?): File? =
        fingerprint?.let(::smartFile)?.takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }

    /** Stores [made] (a finished temporary file in [dir]) as the display image of [fingerprint]. */
    fun putDisplay(fingerprint: String, made: File) = put(made, displayFile(fingerprint))

    /** Stores [made] as the smart preview of [fingerprint], then keeps the total under the cap. */
    fun putSmart(fingerprint: String, made: File) {
        put(made, smartFile(fingerprint))
        trim()
    }

    private fun put(made: File, target: File) {
        if (!made.renameTo(target)) {
            made.delete()
            throw FileNotFoundException("could not store ${target.name}")
        }
    }

    /** A new temporary file in [dir] (renamed into place when complete, so a half-written preview is never used). */
    fun temp(): File {
        dir.mkdirs()
        return File.createTempFile("preview", ".tmp", dir)
    }

    /** Forgets the previews of [fingerprints] (a photo forgotten, or merged into a folder photo that has the file). */
    fun delete(fingerprints: Collection<String>) {
        for (fingerprint in fingerprints) {
            displayFile(fingerprint).delete()
            smartFile(fingerprint).delete()
        }
    }

    /** Deletes the least recently used smart previews until they fit [maxBytes]; drops stray temporary files. */
    fun trim() {
        val files = dir.listFiles().orEmpty()
        files.filter { it.name.endsWith(TEMP_SUFFIX) && it.lastModified() < System.currentTimeMillis() - STALE_MS }
            .forEach { it.delete() }
        val smart = files.filter { it.name.endsWith(SMART_SUFFIX) }.sortedBy { it.lastModified() }
        var total = smart.sumOf { it.length() }
        for (file in smart) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        /** Long edge of the display image (the size of the display JPEG inside a smart preview). */
        const val DISPLAY_EDGE = 1600
        const val DEFAULT_MAX_BYTES = 1536L * 1024 * 1024
        private const val DISPLAY_SUFFIX = ".jpg"
        private const val SMART_SUFFIX = ".rvsp"
        private const val TEMP_SUFFIX = ".tmp"
        private const val STALE_MS = 60L * 60 * 1000

        /** A file name for a fingerprint (`v1:<hex>`): letters, digits and `_` only. */
        fun key(fingerprint: String): String =
            fingerprint.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
    }
}
