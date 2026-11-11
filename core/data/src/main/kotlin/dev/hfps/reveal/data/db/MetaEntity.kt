package dev.hfps.reveal.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A small key/value row for app-level facts that do not deserve their own table (for example the last schema seed). */
@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)
