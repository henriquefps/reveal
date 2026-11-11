package dev.hfps.reveal.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app database (ADR 0006). Every schema change adds a migration and an exported schema file under
 * `core/data/schemas`; `RevealDatabaseMigrationTest` opens every exported version and migrates it to the current one.
 *
 * | Version | Change |
 * | --- | --- |
 * | 1 | M0: `meta` |
 * | 2 | M1 library: sources, photos, photo_files, versions, recipes, history, history_cursor, cache_entries |
 */
@Database(
    entities = [
        MetaEntity::class,
        SourceEntity::class,
        PhotoEntity::class,
        PhotoFileEntity::class,
        VersionEntity::class,
        RecipeEntity::class,
        HistoryEntity::class,
        HistoryCursorEntity::class,
        CacheEntryEntity::class,
    ],
    version = RevealDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class RevealDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao

    abstract fun sourceDao(): SourceDao

    abstract fun photoDao(): PhotoDao

    abstract fun editDao(): EditDao

    abstract fun cacheDao(): CacheDao

    companion object {
        const val NAME = "reveal.db"
        const val VERSION = 2
    }
}
