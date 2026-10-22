package com.mediavault.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "managed_files", indices = [Index(value = ["path"], unique = true)])
data class ManagedFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val displayName: String,
    val kind: String,
    val sizeBytes: Long,
    val createdAt: Long,
    /** True when a recording ended with an error and the file may be incomplete. */
    val interrupted: Boolean = false,
    /** True once the local copy was removed after every upload succeeded (see rule "delete local after upload"). */
    val localDeleted: Boolean = false,
)
