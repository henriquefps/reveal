package dev.hfps.reveal.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilter
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort

/** Turns a [PhotoQuery] into the grid's SQL (one statement, bound arguments, never string-concatenated input). */
object PhotoQuerySql {
    /** How many photos "Recent" shows. */
    const val RECENT_LIMIT = 500

    private const val SELECT =
        "SELECT p.id, p.source_id, p.name, p.format, p.sort_time, p.capture_time, p.rating, p.label, p.flag, " +
            "p.has_edit, v.id AS version_id, r.updated_at AS edit_time, p.handoff_at, p.handoff_title " +
            "FROM photos p JOIN versions v ON v.photo_id = p.id AND v.position = 0 " +
            "LEFT JOIN recipes r ON r.version_id = v.id AND r.is_default = 0"

    /** The statement text and its arguments, in order (exposed for tests). */
    fun build(query: PhotoQuery): Pair<String, List<Any>> {
        val clauses = listOfNotNull(
            Clause("p.hidden = 0"),
            scopeClause(query.scope),
            filterClause(query),
            query.search.trim().takeIf { it.isNotEmpty() }?.let {
                Clause("p.name LIKE ? ESCAPE '\\'", "%" + escapeLike(it) + "%")
            },
        )
        val sql = buildString {
            append(SELECT)
            append(" WHERE ").append(clauses.joinToString(" AND ") { it.sql })
            append(" ORDER BY ").append(order(query))
            if (query.scope == LibraryScope.Recent) append(" LIMIT ").append(RECENT_LIMIT)
        }
        return sql to clauses.mapNotNull { it.arg }
    }

    private class Clause(val sql: String, val arg: Any? = null)

    private fun scopeClause(scope: LibraryScope): Clause? = when (scope) {
        LibraryScope.All -> null
        LibraryScope.Recent -> Clause("p.last_opened_at IS NOT NULL")
        LibraryScope.FromApps -> Clause("p.source_id IN (SELECT id FROM sources WHERE kind = 'HANDOFF')")
        is LibraryScope.Source -> Clause("p.source_id = ?", scope.sourceId)
    }

    private fun filterClause(query: PhotoQuery): Clause? = when (query.filter) {
        PhotoFilter.ALL -> null
        PhotoFilter.EDITED -> Clause("p.has_edit = 1")
        PhotoFilter.NOT_EDITED -> Clause("p.has_edit = 0")
        PhotoFilter.PICKED -> Clause("p.flag = 'PICK'")
        PhotoFilter.RATED -> Clause("p.rating >= ?", query.minRating.coerceIn(1, MAX_RATING))
        PhotoFilter.RAW -> Clause("p.has_raw = 1")
        PhotoFilter.JPEG -> Clause("p.has_raw = 0")
    }

    private fun order(query: PhotoQuery): String = when {
        query.scope == LibraryScope.Recent -> "p.last_opened_at DESC, p.id DESC"
        query.scope == LibraryScope.FromApps -> "p.handoff_at DESC, p.name COLLATE NOCASE ASC, p.id ASC"
        query.sort == PhotoSort.CAPTURE_NEWEST -> "p.sort_time DESC, p.name DESC, p.id DESC"
        query.sort == PhotoSort.CAPTURE_OLDEST -> "p.sort_time ASC, p.name ASC, p.id ASC"
        else -> "p.name COLLATE NOCASE ASC, p.id ASC"
    }

    fun toQuery(query: PhotoQuery): SupportSQLiteQuery {
        val (sql, args) = build(query)
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /** `%`, `_` and the escape character itself match literally. */
    fun escapeLike(text: String): String = buildString {
        for (c in text) {
            if (c == '%' || c == '_' || c == '\\') append('\\')
            append(c)
        }
    }

    private const val MAX_RATING = 5
}
