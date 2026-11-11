package dev.hfps.reveal.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app database (ADR 0006). Every schema change adds a migration and an exported schema file under
 * `core/data/schemas`; `RevealDatabaseMigrationTest` opens every exported version and migrates it to the current one.
 *
 * | Version | Change |
 * | --- | --- |
 * | 1 | M0: `meta` |
 * | 2 | M1 library: sources, photos, photo_files, versions, recipes, history, history_cursor, cache_entries |
 * | 3 | Hand-offs (ADR 0011): `sources.kind`, `photos.handoff_at`, `photos.handoff_title` |
 * | 4 | Identity (ADR 0006): `photo_files.fingerprint` |
 * | 5 | Stable ids for sync (ADR 0006): `photos.uuid`, `versions.uuid`, backfilled ([MIGRATION_4_5]) |
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
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class RevealDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao

    abstract fun sourceDao(): SourceDao

    abstract fun photoDao(): PhotoDao

    abstract fun editDao(): EditDao

    abstract fun cacheDao(): CacheDao

    companion object {
        const val NAME = "reveal.db"
        const val VERSION = 5

        /** Every manual migration, for `Room.databaseBuilder(...).addMigrations(*MIGRATIONS)`. */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_4_5)

        /**
         * A random version 4 UUID in SQL (lower-case, dashes), one per evaluated row: the backfill of existing rows.
         * Rows inserted by the app get theirs from [newUuid].
         */
        private const val SQL_UUID = "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
            "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || " +
            "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

        /**
         * 4 -> 5: a stable `uuid` on photos and versions (`docs/plan/sync.md` "Prepare now"). Added empty, filled with
         * a random UUID per existing row, then made unique. An auto-migration cannot fill per-row random values
         * before the unique index exists, hence a manual one.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("photos", "versions")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE `$table` SET `uuid` = $SQL_UUID")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uuid` ON `$table` (`uuid`)")
                }
            }
        }
    }
}
