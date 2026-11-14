package dev.hfps.reveal.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.hfps.reveal.data.model.CullingFilter
import dev.hfps.reveal.data.model.EditFilter
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.data.model.SourceFilter
import dev.hfps.reveal.data.model.SyncFilter
import dev.hfps.reveal.data.model.TypeFilter

/**
 * Turns a [PhotoQuery] into the grid's SQL (one statement, bound arguments, never string-concatenated input). The
 * filters compose OR within a group and AND across groups (FIX/L, ADR 0006 "Filters").
 */
object PhotoQuerySql {
    /** How many photos "Recent" shows. */
    const val RECENT_LIMIT = 500

    /**
     * A Server-source photo the library lists (ADR 0018 "Server photos", FIX/V): the server can serve a file of the
     * shot (`photo_cloud.original_on_server`, any file for these photos) or holds its smart preview. A record without
     * either only describes a photo (ratings, edits) and waits, unlisted, until the photo shows up.
     */
    const val SERVER_OPENABLE_SQL = "(COALESCE(c.original_on_server, 0) = 1 OR c.preview_sha IS NOT NULL)"

    /** The photo belongs to the Server source (no file on this device). */
    const val SERVER_SOURCE_SQL = "p.source_id IN (SELECT id FROM sources WHERE kind = 'SERVER')"

    /**
     * "Synced" (the Sync filter): the server holds the original of any file of the shot (the photo's lookup, or a file
     * the lookups or the NAS feed marked, FIX/V), or the photo is a Server photo.
     */
    const val SYNCED_SQL = "($SERVER_SOURCE_SQL OR COALESCE(c.original_on_server, 0) = 1 OR " +
        "EXISTS (SELECT 1 FROM photo_files sf WHERE sf.photo_id = p.id AND sf.on_server = 1))"

    /**
     * An upload this device still owes for the photo (its original or smart preview): the grid's pending badge.
     * `+su.state` keeps SQLite on the `photo_id` index (the `state` one would read every pending upload per row).
     */
    const val UPLOADING_SQL = "EXISTS (SELECT 1 FROM sync_uploads su WHERE su.photo_id = p.id " +
        "AND +su.state = 'pending' AND su.kind IN ('original', 'preview'))"

    /**
     * "Unavailable", defined once (`PhotoAvailability` is its Kotlin mirror): a file of one of this device's photos is
     * unreadable AND the sync server has no original AND no smart preview (Server photos are listed only when they
     * open, FIX/V, so never unavailable). The local preview is a file, not a row: the repository removes
     * the photos that have one after the query.
     */
    const val UNAVAILABLE_SQL =
        "EXISTS (SELECT 1 FROM photo_files uf WHERE uf.photo_id = p.id AND uf.unreadable_at IS NOT NULL) " +
            "AND COALESCE(c.original_on_server, 0) = 0 AND c.preview_sha IS NULL " +
            "AND NOT $SERVER_SOURCE_SQL"

    /** The row's version has an edit (a non-default recipe); `r` is the left-joined non-default recipe. */
    const val EDITED_SQL = "r.version_id IS NOT NULL"

    /**
     * The FROM clause of one row per version (a grid cell, docs/adr/0025): the counts use it too, so a virtual copy
     * counts like the cell it is.
     */
    const val CELLS_FROM = "FROM photos p JOIN versions v ON v.photo_id = p.id " +
        "LEFT JOIN recipes r ON r.version_id = v.id AND r.is_default = 0 " +
        "LEFT JOIN photo_cloud c ON c.photo_id = p.id"

    /**
     * One row per version: the first version of every photo, then its other versions (virtual copies, docs/adr/0025;
     * sync conflict copies, ADR 0018) right after it. `remote` = the photo lives only on the sync server.
     */
    private const val SELECT =
        "SELECT p.id, p.source_id, p.name, p.format, p.sort_time, p.capture_time, p.rating, p.label, p.flag, " +
            "p.has_edit, v.id AS version_id, r.updated_at AS edit_time, p.handoff_at, p.handoff_title, " +
            "v.position AS version_position, v.copy_of IS NOT NULL AS conflict, v.copy_device, " +
            "v.name AS version_name, " +
            "COALESCE(c.original_on_server, 0) AS cloud_original, c.preview_sha IS NOT NULL AS cloud_preview, " +
            "$SERVER_SOURCE_SQL AS remote, ($UPLOADING_SQL) AS uploading, " +
            "EXISTS (SELECT 1 FROM photo_files uf WHERE uf.photo_id = p.id AND uf.unreadable_at IS NOT NULL) " +
            "AS unreadable, ($UNAVAILABLE_SQL) AS unavailable, (SELECT uf.fingerprint FROM photo_files uf WHERE " +
            "uf.photo_id = p.id AND uf.unreadable_at IS NOT NULL LIMIT 1) AS unreadable_fingerprint " +
            CELLS_FROM

    /** The statement text and its arguments, in order (exposed for tests). */
    fun build(query: PhotoQuery): Pair<String, List<Any>> {
        val clauses = buildList {
            add(Clause("p.hidden = 0"))
            if (!query.withUnopenable) add(Clause("(NOT $SERVER_SOURCE_SQL OR $SERVER_OPENABLE_SQL)"))
            scopeClause(query.scope)?.let(::add)
            addAll(filterClauses(query.filters))
            if (query.unavailableOnly) add(Clause("($UNAVAILABLE_SQL)"))
            query.search.trim().takeIf { it.isNotEmpty() }?.let {
                add(Clause("p.name LIKE ? ESCAPE '\\'", listOf("%" + escapeLike(it) + "%")))
            }
        }
        val sql = buildString {
            append(SELECT)
            append(" WHERE ").append(clauses.joinToString(" AND ") { it.sql })
            append(" ORDER BY ").append(order(query))
            if (query.scope == LibraryScope.Recent) append(" LIMIT ").append(RECENT_LIMIT)
        }
        return sql to clauses.flatMap { it.args }
    }

    private class Clause(val sql: String, val args: List<Any> = emptyList())

    /**
     * Every view lists this device's photos and the Server photos that open (FIX/V): one row per photo, since a Server
     * photo whose file shows up here is merged into this device's photo (ADR 0018 "Identity", FIX/V).
     */
    private fun scopeClause(scope: LibraryScope): Clause? = when (scope) {
        LibraryScope.All -> null
        LibraryScope.Recent -> Clause("p.last_opened_at IS NOT NULL")
    }

    /** One clause per group that has choices: its choices joined by OR. */
    private fun filterClauses(filters: PhotoFilters): List<Clause> = listOfNotNull(
        group(sourceChoices(filters.sources)),
        group(
            filters.culling.sorted().map {
                when (it) {
                    CullingFilter.PICKED -> Clause("p.flag = 'PICK'")
                    CullingFilter.STARRED -> Clause("p.rating >= 1")
                    CullingFilter.REJECTED -> Clause("p.flag = 'REJECT'")
                }
            },
        ),
        group(
            filters.edits.sorted().map {
                when (it) {
                    // Per grid cell (docs/adr/0025): a virtual copy is edited or not on its own.
                    EditFilter.EDITED -> Clause(EDITED_SQL)

                    EditFilter.NOT_EDITED -> Clause("NOT $EDITED_SQL")
                }
            },
        ),
        group(
            filters.types.sorted().map {
                when (it) {
                    TypeFilter.RAW -> Clause("p.has_raw = 1")
                    TypeFilter.JPEG -> Clause("p.has_raw = 0")
                }
            },
        ),
        group(
            filters.sync.sorted().map {
                when (it) {
                    SyncFilter.SYNCED -> Clause(SYNCED_SQL)
                    SyncFilter.NOT_SYNCED -> Clause("NOT $SYNCED_SQL")
                }
            },
        ),
    )

    private fun sourceChoices(sources: Set<SourceFilter>): List<Clause> {
        val folders = sources.filterIsInstance<SourceFilter.Folder>().map { it.sourceId }.sorted()
        return listOfNotNull(
            folders.takeIf { it.isNotEmpty() }?.let { ids ->
                Clause("p.source_id IN (" + ids.joinToString(", ") { "?" } + ")", ids)
            },
            // Every photo another app handed over: hand-off photos, and folder photos a hand-off opened or merged
            // into (ADR 0011 "One photo across sources"); the Server source never.
            Clause("(p.handoff_at IS NOT NULL AND NOT $SERVER_SOURCE_SQL)").takeIf { SourceFilter.FromApps in sources },
            Clause(SERVER_SOURCE_SQL).takeIf { SourceFilter.ServerOnly in sources },
        )
    }

    private fun group(choices: List<Clause>): Clause? = when (choices.size) {
        0 -> null
        1 -> choices.single()
        else -> Clause(choices.joinToString(" OR ", "(", ")") { it.sql }, choices.flatMap { it.args })
    }

    private fun order(query: PhotoQuery): String = when {
        query.scope == LibraryScope.Recent -> "p.last_opened_at DESC, p.id DESC"
        query.filters.onlyFromApps -> "p.handoff_at DESC, p.name COLLATE NOCASE ASC, p.id ASC"
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
}
