package dev.hfps.reveal.feature.export.model

/**
 * The original-file copy (docs/adr/0009 "Original copy"): its name (the original's, made safe), its MIME type (the
 * gallery takes `image/` types only) and its XMP sidecar's name. Pure, JVM-tested.
 */
object OriginalCopy {
    const val SIDECAR_MIME = "application/rdf+xml"
    private const val MAX_NAME = 160
    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    private val MIME = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "hif" to "image/heif",
        "avif" to "image/avif",
        "png" to "image/png",
        "webp" to "image/webp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "dng" to "image/x-adobe-dng",
        "arw" to "image/x-sony-arw",
        "srf" to "image/x-sony-srf",
        "sr2" to "image/x-sony-sr2",
        "cr2" to "image/x-canon-cr2",
        "cr3" to "image/x-canon-cr3",
        "crw" to "image/x-canon-crw",
        "nef" to "image/x-nikon-nef",
        "nrw" to "image/x-nikon-nrw",
        "raf" to "image/x-fuji-raf",
        "orf" to "image/x-olympus-orf",
        "rw2" to "image/x-panasonic-rw2",
        "pef" to "image/x-pentax-pef",
        "srw" to "image/x-samsung-srw",
        "3fr" to "image/x-hasselblad-3fr",
        "iiq" to "image/x-phaseone-iiq",
    )

    /** The copy's file name: the original's, without characters a destination refuses. */
    fun fileName(original: String): String =
        original.replace(UNSAFE, "_").trim().trimStart('.').take(MAX_NAME).ifBlank { "photo" }

    /** The MIME type of [name] by its extension; an unknown one is still an image type (the gallery needs one). */
    fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME[ext] ?: if (ext.isNotEmpty()) "image/x-$ext" else "application/octet-stream"
    }

    /** The sidecar of the copy delivered as [deliveredName]: `<stem>.xmp`, as Lightroom, darktable and Hoard expect. */
    fun sidecarName(deliveredName: String): String = deliveredName.substringBeforeLast('.') + ".xmp"

    /** The gallery (MediaStore `Pictures/`) holds images only: a sidecar goes to a folder or the share sheet. */
    fun sidecarFits(destination: ExportDestination): Boolean = destination != ExportDestination.Pictures
}
