package dev.hfps.reveal.ui.about

/** One third-party component and its licence (from `R.array.about_licences`, mirrored in `docs/licences.md`). */
data class LicenceEntry(val component: String, val licence: String)

/** Parses `component|licence` lines. */
fun parseLicences(lines: Array<String>): List<LicenceEntry> = lines.mapNotNull { line ->
    val parts = line.split('|', limit = 2)
    if (parts.size == 2) LicenceEntry(parts[0].trim(), parts[1].trim()) else null
}
