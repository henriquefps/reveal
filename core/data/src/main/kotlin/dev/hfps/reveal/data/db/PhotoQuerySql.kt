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

    /**
     * "Unavailable", defined once (`PhotoAvailability` is its Kotlin mirror): a file of the photo is unreadable AND the
     * sync server has no original AND no smart preview. The local preview is a file, not a row: the repository removes
     * the photos that have one after the query.
     */
    const val UNAVAILABLE_SQL =
        "EXISTS (SELECT 1 FROM photo_files uf WHERE uf.photo_id = p.id AND uf.unreadable_at IS NOT NULL) " +
            "AND COALESCE(c.original_on_server, 0) = 0 AND c.preview_sha IS NULL"

    /**
     * One row per version: the first version of every photo, then its other versions (sync conflict copies and
     * versions another device added, ADR 0018) right after it. `remote` = the photo lives only on the sync server.
     */
    private const val SELECT =
        "SELECT p.id, p.source_id, p.name, p.format, p.sort_time, p.capture_time, p.rating, p.label, p.flag, " +
            "p.has_edit, v.id AS version_id, r.updated_at AS edit_time, p.handoff_at, p.handoff_title, " +
            "v.position AS version_position, v.copy_of IS NOT NULL AS conflict, v.copy_device, " +
            "COALESCE(c.original_on_server, 0) AS cloud_original, c.preview_sha IS NOT NULL AS cloud_preview, " +
            "p.source_id IN (SELECT id FROM sources WHERE kind = 'SERVER') AS remote, " +
            "EXISTS (SELECT 1 FROM photo_files uf WHERE uf.photo_id = p.id AND uf.unreadable_at IS NOT NULL) " +
            "AS unreadable, ($UNAVAILABLE_SQL) AS unavailable, (SELECT uf.fingerprint FROM photo_files uf WHERE " +
            "uf.photo_id = p.id AND uf.unreadable_at IS NOT NULL LIMIT 1) AS unreadable_fingerprint " +
            "FROM photos p JOIN versions v ON v.photo_id = p.id " +
            "LEFT JOIN recipes r ON r.version_id = v.id AND r.is_default = 0 " +
            "LEFT JOIN photo_cloud c ON c.photo_id = p.id"

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
        LibraryScope.All -> Clause("p.source_id NOT IN (SELECT id FROM sources WHERE kind = 'SERVER')")

        LibraryScope.Server -> Clause("p.source_id IN (SELECT id FROM sources WHERE kind = 'SERVER')")

        LibraryScope.Recent -> Clause("p.last_opened_at IS NOT NULL")

        // Every photo another app handed over: hand-off photos, and folder photos a hand-off opened or merged into
        // (ADR 0011 "One photo across sources"); the Server source never.
        LibraryScope.FromApps -> Clause(
            "p.handoff_at IS NOT NULL AND p.source_id NOT IN (SELECT id FROM sources WHERE kind = 'SERVER')",
        )

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
        PhotoFilter.UNAVAILABLE -> Clause("($UNAVAILABLE_SQL)")
    }

    private fun order(query: PhotoQuery): String = when {
        query.scope == LibraryScope.Recent -> "p.last_opened_at DESC, p.id DESC"
        query.scope == LibraryScope.FromApps -> "p.handoff_at DESC, p.name COLLATE NOCASE ASC, p.id ASC"
        query.sort == PhotoSort.CAPTURE_NEWEST -> "p.sort_time DESC, p.name DESC, p.id DESC"
        query.sort == PhotoSort.CAPTURE_OLDEST -> "p.sort_time ASC, p.name ASC, p.id ASC"
        else -> "p.name COLLATE NOCASE ASC, p.id ASC"
    } + ", v.position ASC"

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
