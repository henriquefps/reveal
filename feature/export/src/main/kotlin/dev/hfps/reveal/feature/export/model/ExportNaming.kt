package dev.hfps.reveal.feature.export.model

/** File names of exports (docs/adr/0009, "File names"). Pure, JVM-tested. */
object ExportNaming {
    private const val SEQUENCE_DIGITS = 4
    private const val DATE_PARTS = 3
    private const val MAX_STEM = 120
    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /**
     * The file name for the original [originalName] (`DSC01234.ARW`), taken at [captureLocal] (`yyyy:MM:dd HH:mm:ss`
     * from EXIF, or null), number [sequence] of the run, in [format].
     */
    fun fileName(
        originalName: String,
        captureLocal: String?,
        sequence: Int,
        naming: NamingOptions,
        format: ExportFormat,
    ): String {
        val stem = originalName.substringBeforeLast('.').ifBlank { "photo" }
        val base = when (naming.template) {
            NameTemplate.ORIGINAL -> stem
            NameTemplate.DATE -> captureLocal?.let(::dateStem) ?: stem
            NameTemplate.SEQUENCE -> "$stem-${sequence.toString().padStart(SEQUENCE_DIGITS, '0')}"
        }
        val safe = (base + naming.suffix).replace(UNSAFE, "_").trim().trimStart('.').take(MAX_STEM).ifBlank { "photo" }
        return "$safe.${format.extension}"
    }

    /** `2026:08:07 14:30:12` -> `20260807-143012`; null when it is not an EXIF date. */
    fun dateStem(exif: String): String? {
        val m = Regex("""(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}):(\d{2}):(\d{2})""").find(exif) ?: return null
        val g = m.groupValues.drop(1)
        return g.take(DATE_PARTS).joinToString("") + "-" + g.drop(DATE_PARTS).joinToString("")
    }
}
