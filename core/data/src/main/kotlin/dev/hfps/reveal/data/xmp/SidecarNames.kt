package dev.hfps.reveal.data.xmp

/**
 * The names under which a shot's XMP sidecar is looked for (ADR 0006 "XMP"), in order of preference:
 * `<stem>.xmp` (Lightroom, Hoard), `<file name>.xmp` of each of its files (darktable), then the same names hidden
 * with a leading dot (`.<stem>.xmp`, `.<file name>.xmp`: tools that keep sidecars out of sight). A normal name always
 * wins over a hidden one. Reveal itself only ever creates `<stem>.xmp`.
 */
object SidecarNames {
    const val SUFFIX = ".xmp"

    /** Lower-case candidate names for a shot of [stem] whose files are [fileNames] (main file first). */
    fun candidates(stem: String, fileNames: List<String>): List<String> {
        val normal = buildList {
            add("${stem.lowercase()}$SUFFIX")
            fileNames.forEach { add("${it.lowercase()}$SUFFIX") }
        }.distinct()
        return normal + normal.map { ".$it" }
    }

    /** A sidecar name: ends in `.xmp`, and is not a macOS AppleDouble file (`._name.xmp`). */
    fun isSidecar(name: String): Boolean = name.endsWith(SUFFIX, ignoreCase = true) && !name.startsWith("._")

    /** `.<name>.xmp`: hidden by its leading dot. */
    fun isHidden(name: String): Boolean = name.startsWith('.')
}
