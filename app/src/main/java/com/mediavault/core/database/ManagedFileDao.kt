package com.mediavault.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ManagedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: ManagedFileEntity): Long

    @Query("UPDATE managed_files SET localDeleted = 1 WHERE id = :id")
    suspend fun markLocalDeleted(id: Long)

    @Query("DELETE FROM managed_files WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM managed_files ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ManagedFileEntity>>
}
