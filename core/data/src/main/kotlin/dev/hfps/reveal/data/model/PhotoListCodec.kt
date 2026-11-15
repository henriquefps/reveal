package dev.hfps.reveal.data.model

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate

/**
 * A [PhotoQuery] (view and filters included) as one string for a navigation route or the saved library state: the
 * editor's filmstrip and the viewer page through the same photos as the grid they were opened from. Fields are
 * separated by U+0001, which no file-name search contains.
 *
 * Format 2 (FIX/L): `2 | view | filters | sort | search`, filters as comma-separated tokens ([encodeFilters]). Format 1
 * (`scope | filter | minRating | sort | search`, before the library had one home) still decodes, its scope and filter
 * mapped to the equivalent filters ([legacyFilters]).
 */
object PhotoListCodec {
    private const val SEPARATOR = '\u0001'
    private const val VERSION = "2"
    private const val PARTS = 5
    private const val FOLDER = "f:"
    private const val LEGACY_SOURCE = "src:"
    private const val DATE = "d:"
    private const val CAMERA = "cam:"
    private const val LENS = "lens:"

    fun encode(query: PhotoQuery): String = listOf(
        VERSION,
        if (query.scope == LibraryScope.Recent) "recent" else "all",
        encodeFilters(query.filters),
        query.sort.name,
        query.search,
    ).joinToString(SEPARATOR.toString())

    /** The query in [text], or `null` when it is not one [encode] (or format 1) wrote. */
    fun decode(text: String): PhotoQuery? = runCatching {
        val parts = text.split(SEPARATOR, limit = PARTS)
        require(parts.size == PARTS)
        val fields = parts.iterator()
        if (parts.first() == VERSION) {
            fields.next()
            PhotoQuery(
                scope = when (fields.next()) {
                    "all" -> LibraryScope.All
                    "recent" -> LibraryScope.Recent
                    else -> error("view")
                },
                filters = checkNotNull(decodeFilters(fields.next())),
                sort = PhotoSort.valueOf(fields.next()),
                search = fields.next(),
            )
        } else {
            decodeLegacy(fields)
        }
    }.getOrNull()

    /**
     * The filters as tokens: `f:<id>`, `apps`, `server`, then one name per culling/edit/type/sync choice, then the
     * dates ([dateToken]), `cam:<make>~<model>` and `lens:<name>` (percent-encoded, W3/D).
     */
    fun encodeFilters(filters: PhotoFilters): String = buildList {
        filters.sources.filterIsInstance<SourceFilter.Folder>().map {
            it.sourceId
        }.sorted().forEach { add("$FOLDER$it") }
        if (SourceFilter.FromApps in filters.sources) add("apps")
        if (SourceFilter.ServerOnly in filters.sources) add("server")
        filters.culling.sorted().forEach { add("c:${it.name}") }
        filters.edits.sorted().forEach { add("e:${it.name}") }
        filters.types.sorted().forEach { add("t:${it.name}") }
        filters.sync.sorted().forEach { add("s:${it.name}") }
        filters.dates.map(::dateToken).sorted().forEach(::add)
        filters.cameras.map { "$CAMERA${escape(it.make)}~${escape(it.model)}" }.sorted().forEach(::add)
        filters.lenses.map { "$LENS${escape(it.name)}" }.sorted().forEach(::add)
    }.joinToString(",")

    /** `d:month`, `d:30d`, `d:year`, `d:onthisday`, `d:2024-01-01_2024-03-31` (W3/D). */
    fun dateToken(filter: DateFilter): String = DATE + when (filter) {
        DateFilter.ThisMonth -> "month"
        DateFilter.Last30Days -> "30d"
        DateFilter.ThisYear -> "year"
        DateFilter.OnThisDay -> "onthisday"
        is DateFilter.Range -> "${filter.from}_${filter.to}"
    }

    private fun decodeDate(text: String): DateFilter? = when (text) {
        "month" -> DateFilter.ThisMonth

        "30d" -> DateFilter.Last30Days

        "year" -> DateFilter.ThisYear

        "onthisday" -> DateFilter.OnThisDay

        else -> runCatching {
            val (from, to) = text.split('_').also { require(it.size == 2) }
            DateFilter.Range(LocalDate.parse(from), LocalDate.parse(to))
        }.getOrNull()
    }

    /** Names (cameras, lenses) percent-encoded: no `,` `~` or separator survives into the token list. */
    private fun escape(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

    private fun unescape(text: String): String = URLDecoder.decode(text, Charsets.UTF_8.name())

    /** [encodeFilters]' text back; unknown tokens are skipped (a newer build's choice), garbage gives null. */
    @Suppress("CyclomaticComplexMethod")
    fun decodeFilters(text: String): PhotoFilters? = runCatching {
        var filters = PhotoFilters.NONE
        for (token in text.split(',').filter { it.isNotEmpty() }) {
            filters = when {
                token == "apps" -> filters.toggle(SourceFilter.FromApps)

                token == "server" -> filters.toggle(SourceFilter.ServerOnly)

                token.startsWith(FOLDER) -> filters.toggle(SourceFilter.Folder(token.removePrefix(FOLDER).toLong()))

                token.startsWith("c:") -> enumOrNull<CullingFilter>(token)?.let(filters::toggle) ?: filters

                token.startsWith("e:") -> enumOrNull<EditFilter>(token)?.let(filters::toggle) ?: filters

                token.startsWith("t:") -> enumOrNull<TypeFilter>(token)?.let(filters::toggle) ?: filters

                token.startsWith("s:") -> enumOrNull<SyncFilter>(token)?.let(filters::toggle) ?: filters

                token.startsWith(DATE) -> decodeDate(token.removePrefix(DATE))?.let(filters::toggle) ?: filters

                token.startsWith(CAMERA) -> token.removePrefix(CAMERA).split('~').takeIf { it.size == 2 }?.let {
                    filters.toggle(CameraFilter(unescape(it[0]), unescape(it[1])))
                } ?: filters

                token.startsWith(LENS) -> filters.toggle(LensFilter(unescape(token.removePrefix(LENS))))

                else -> filters
            }
        }
        filters
    }.getOrNull()

    private inline fun <reified E : Enum<E>> enumOrNull(token: String): E? =
        enumValues<E>().firstOrNull { it.name == token.substringAfter(':') }

    /** Format 1: the old scope and single filter. */
    private fun decodeLegacy(fields: Iterator<String>): PhotoQuery {
        val scope = fields.next()
        val filter = fields.next()
        fields.next() // the minimum rating: any rating is "Starred" now
        val sort = fields.next()
        val search = fields.next()
        val view = if (scope == "recent") LibraryScope.Recent else LibraryScope.All
        val sources = checkNotNull(legacyScope(scope))
        return PhotoQuery(
            scope = view,
            filters = legacyFilters(sources, filter),
            sort = PhotoSort.valueOf(sort),
            search = search,
        )
    }

    /** The Source group of a format-1 scope (null = not a scope). */
    private fun legacyScope(text: String): Set<SourceFilter>? = when {
        text == "all" || text == "recent" -> emptySet()
        text == "apps" -> setOf(SourceFilter.FromApps)
        text == "server" -> setOf(SourceFilter.ServerOnly)
        text.startsWith(LEGACY_SOURCE) -> setOf(SourceFilter.Folder(text.removePrefix(LEGACY_SOURCE).toLong()))
        else -> null
    }

    /**
     * An old scope ([sources]) and single filter as filters (FIX/L): ALL and UNAVAILABLE narrow nothing, RATED of any
     * minimum is "Starred".
     */
    fun legacyFilters(sources: Set<SourceFilter>, filter: String): PhotoFilters {
        val base = PhotoFilters(sources = sources)
        return when (filter) {
            "EDITED" -> base.toggle(EditFilter.EDITED)
            "NOT_EDITED" -> base.toggle(EditFilter.NOT_EDITED)
            "PICKED" -> base.toggle(CullingFilter.PICKED)
            "RATED" -> base.toggle(CullingFilter.STARRED)
            "RAW" -> base.toggle(TypeFilter.RAW)
            "JPEG" -> base.toggle(TypeFilter.JPEG)
            "ALL", "UNAVAILABLE" -> base
            else -> error("filter $filter")
        }
    }

    /**
     * The library's last selection as saved before FIX/L (`meta` `library.last_selection`: `f:<id>` a folder,
     * `s:<SPECIAL>` an entry of the sources list) as the equivalent filters; null when there is nothing to carry over.
     */
    fun legacySelection(text: String): PhotoFilters? = when {
        text.startsWith("f:") -> text.removePrefix("f:").toLongOrNull()?.let(PhotoFilters::folder)
        text == "s:FROM_APPS" -> PhotoFilters.FROM_APPS
        text == "s:SERVER" -> PhotoFilters.SERVER_ONLY
        else -> null
    }
}
