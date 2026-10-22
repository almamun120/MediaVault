package com.mediavault.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

object JobStatus {
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val PAUSED = "PAUSED"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELED = "CANCELED"
}

/**
 * One file -> one destination. The unique index (file, destination, folder) is what makes enqueueing idempotent:
 * a second insert for the same triple is ignored, so a retry or a double tap can never create a duplicate upload.
 */
@Entity(
    tableName = "upload_jobs",
    indices = [Index(value = ["filePath", "destinationId", "remoteDir"], unique = true), Index("status")],
)
data class UploadJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: Long,
    val filePath: String,
    val destinationId: String,
    val remoteDir: String,
    val remoteName: String,
    val status: String = JobStatus.QUEUED,
    val attempts: Int = 0,
    val transferred: Long = 0,
    val total: Long = 0,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val requiresNetwork: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val conflict: String = ConflictPolicy.RENAME.name,
    val deleteLocalAfter: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface UploadJobDao {
    /** Returns -1 when an identical job already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(job: UploadJobEntity): Long

    @Query("SELECT * FROM upload_jobs WHERE id = :id")
    suspend fun get(id: Long): UploadJobEntity?

    @Query("SELECT * FROM upload_jobs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<UploadJobEntity>>

    @Query("SELECT * FROM upload_jobs WHERE filePath = :path")
    suspend fun forFile(path: String): List<UploadJobEntity>

    @Query("SELECT * FROM upload_jobs WHERE status IN ('QUEUED','UPLOADING')")
    suspend fun active(): List<UploadJobEntity>

    @Query("UPDATE upload_jobs SET status = 'UPLOADING', attempts = :attempts, errorType = NULL, errorMessage = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markUploading(id: Long, attempts: Int, now: Long)

    @Query("UPDATE upload_jobs SET transferred = :done, total = :total, updatedAt = :now WHERE id = :id AND status = 'UPLOADING'")
    suspend fun setProgress(id: Long, done: Long, total: Long, now: Long)

    @Query("UPDATE upload_jobs SET status = 'DONE', transferred = total, errorType = NULL, errorMessage = :note, remoteName = :name, updatedAt = :now WHERE id = :id")
    suspend fun markDone(id: Long, name: String, note: String?, now: Long)

    @Query("UPDATE upload_jobs SET status = :status, errorType = :type, errorMessage = :message, updatedAt = :now WHERE id = :id")
    suspend fun markError(id: Long, status: String, type: String, message: String, now: Long)

    @Query("UPDATE upload_jobs SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, now: Long)

    /** Used when WorkManager stops a running upload (constraints lost): put it back in the queue, but never override Pause/Cancel. */
    @Query("UPDATE upload_jobs SET status = 'QUEUED', updatedAt = :now WHERE id = :id AND status = 'UPLOADING'")
    suspend fun requeueIfUploading(id: Long, now: Long)

    @Query("UPDATE upload_jobs SET status = 'QUEUED', attempts = 0, transferred = 0, errorType = NULL, errorMessage = NULL, updatedAt = :now WHERE id = :id")
    suspend fun resetForRetry(id: Long, now: Long)

    @Query("DELETE FROM upload_jobs WHERE status IN ('DONE','CANCELED')")
    suspend fun deleteFinished()

    @Query("DELETE FROM upload_jobs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM upload_jobs WHERE destinationId = :destId AND status NOT IN ('DONE')")
    suspend fun deleteUnfinishedFor(destId: String)
}
