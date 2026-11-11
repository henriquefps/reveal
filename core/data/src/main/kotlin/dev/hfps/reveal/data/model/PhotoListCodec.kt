package dev.hfps.reveal.data.model

/**
 * A [PhotoQuery] (scope included) as one string for a navigation route: the editor's filmstrip pages through the
 * same photos as the grid or viewer it was opened from. Fields are separated by U+0001, which no file-name search
 * contains.
 */
object PhotoListCodec {
    private const val SEPARATOR = '\u0001'
    private const val PARTS = 5
    private const val SOURCE = "src:"

    fun encode(query: PhotoQuery): String = listOf(
        scope(query.scope),
        query.filter.name,
        query.minRating.toString(),
        query.sort.name,
        query.search,
    ).joinToString(SEPARATOR.toString())

    /** The query in [text], or `null` when it is not one [encode] wrote. */
    fun decode(text: String): PhotoQuery? = runCatching {
        val parts = text.split(SEPARATOR, limit = PARTS)
        require(parts.size == PARTS)
        PhotoQuery(
            scope = checkNotNull(scope(parts[0])),
            filter = PhotoFilter.valueOf(parts[1]),
            minRating = parts[2].toInt(),
            sort = PhotoSort.valueOf(parts[3]),
            search = parts[4],
        )
    }.getOrNull()

    private fun scope(scope: LibraryScope): String = when (scope) {
        LibraryScope.All -> "all"
        LibraryScope.Recent -> "recent"
        LibraryScope.FromApps -> "apps"
        is LibraryScope.Source -> "$SOURCE${scope.sourceId}"
    }

    private fun scope(text: String): LibraryScope? = when {
        text == "all" -> LibraryScope.All
        text == "recent" -> LibraryScope.Recent
        text == "apps" -> LibraryScope.FromApps
        text.startsWith(SOURCE) -> LibraryScope.Source(text.removePrefix(SOURCE).toLong())
        else -> null
    }
}
