package com.mediavault.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class RuleMedia(val label: String) { PHOTO("Photos"), VIDEO("Videos"), AUDIO("Audio"), DOCUMENT("Documents"), ALL("All media") }
enum class UploadTiming(val label: String) { IMMEDIATE("Right after capture"), MANUAL("Manual only") }
enum class ConflictPolicy(val label: String) { RENAME("Keep both (rename)"), SKIP("Skip if exists") }

/**
 * "Send [media] to [primaryDestinationId] (+ backups) under [folderTemplate]". Capture always saves a local copy
 * first; a rule only decides what is uploaded afterwards. Several rules may match; each destination gets one job.
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val media: String,
    val primaryDestinationId: String,
    /** Comma-separated destination ids. */
    val backupDestinationIds: String = "",
    val folderTemplate: String = "",
    /** Optional remote file name template, e.g. `Holiday_{yyyyMMdd_HHmmss}`; blank = keep the local name. */
    val filenameTemplate: String = "",
    val timing: String = UploadTiming.IMMEDIATE.name,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val conflict: String = ConflictPolicy.RENAME.name,
    /** Delete the local file only after EVERY upload for it succeeded and was size-verified. */
    val deleteLocalAfterUpload: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long,
) {
    fun backups(): List<String> = backupDestinationIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun getEnabled(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM rules WHERE primaryDestinationId = :destId AND backupDestinationIds = ''")
    suspend fun deleteOrphansFor(destId: String)
}
