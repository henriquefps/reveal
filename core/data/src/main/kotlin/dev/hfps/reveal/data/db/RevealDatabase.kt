package dev.hfps.reveal.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** The app database. Every schema change adds a migration and an exported schema file under `core/data/schemas`. */
@Database(entities = [MetaEntity::class], version = RevealDatabase.VERSION, exportSchema = true)
abstract class RevealDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao

    companion object {
        const val NAME = "reveal.db"
        const val VERSION = 1
    }
}
