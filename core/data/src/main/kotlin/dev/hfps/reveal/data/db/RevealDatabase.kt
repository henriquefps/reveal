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
 * | 6 | Sync (ADR 0018): `sync_records`, `photo_cloud`, `sync_uploads`, `sync_aliases`, `sync_inbox`, |
 * |   | `versions.copy_of` / `copy_device`, `sources.backup` |
 * | 7 | Presets (ADR 0019): `preset_packs`, `preset_groups`, `presets`, `preset_marks` |
 * | 8 | Unreadable hand-offs (ADR 0011): `photo_files.unreadable_at` |
 * | 9 | Smart preview formats (ADR 0018 FIX/P): `photo_cloud.preview_format` |
 * | 10 | Server view (ADR 0018 FIX/V): `photo_files.on_server`, `sync_deletes` |
 * | 11 | ADR 0018 FIX/W ([MIGRATION_10_11]): `versions.edit_device` / `edited_at`; Server photos read their header |
 * |    | again, hand-offs back up only edited photos |
 *
 * Open it with [configure] (migrations and the sync triggers of [SyncTriggers]).
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
        SyncRecordEntity::class,
        PhotoCloudEntity::class,
        SyncUploadEntity::class,
        SyncAliasEntity::class,
        SyncInboxEntity::class,
        PresetPackEntity::class,
        PresetGroupEntity::class,
        PresetEntity::class,
        PresetMarkEntity::class,
        SyncDeleteEntity::class,
    ],
    version = RevealDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
    ],
)
abstract class RevealDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao

    abstract fun sourceDao(): SourceDao

    abstract fun photoDao(): PhotoDao

    abstract fun editDao(): EditDao

    abstract fun cacheDao(): CacheDao

    abstract fun syncDao(): SyncDao

    abstract fun presetDao(): PresetDao

    companion object {
        const val NAME = "reveal.db"
        const val VERSION = 11

        /** Every manual migration, for `Room.databaseBuilder(...).addMigrations(*MIGRATIONS)`. */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_4_5, MIGRATION_10_11)

        /** The app's database setup for any builder (the app, tests): migrations and the sync triggers. */
        @Suppress("SpreadOperator") // once per open
        fun configure(builder: Builder<RevealDatabase>): Builder<RevealDatabase> =
            builder.addMigrations(*MIGRATIONS).addCallback(SyncTriggers.callback)

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

        /**
         * 10 -> 11 (ADR 0018 FIX/W):
         * - `versions.edit_device` / `edited_at`: who changed a version's edit last, and when (the conflict chooser);
         *   unknown for existing rows.
         * - Server photos read their header again (capture time, size, **orientation**): records from 0.5.3 and older
         *   carried no orientation, so a portrait shot was stored landscape and the viewer stretched it. The update
         *   touches only `metadata_state`, which no sync trigger watches.
         * - "Opened from other apps" backs up only edited photos (it was "Everything" without ever asking); the
         *   originals already uploaded stay on the server.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `versions` ADD COLUMN `edit_device` TEXT")
                db.execSQL("ALTER TABLE `versions` ADD COLUMN `edited_at` INTEGER")
                db.execSQL(
                    "UPDATE photos SET metadata_state = 'PENDING' WHERE source_id IN " +
                        "(SELECT id FROM sources WHERE kind = 'SERVER')",
                )
                db.execSQL("UPDATE sources SET backup = 0 WHERE kind = 'HANDOFF'")
            }
        }
    }
}
