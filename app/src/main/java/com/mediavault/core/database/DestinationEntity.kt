package com.mediavault.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

enum class DestinationType(val label: String) {
    LOCAL_APP("App storage"),
    SAF_FOLDER("Device / SD card folder"),
    SMB("SMB share"),
    FTP("FTP (unencrypted)"),
    FTPS("FTPS (TLS)"),
    SFTP("SFTP (SSH)"),
    WEBDAV("WebDAV"),
    GOOGLE_DRIVE("Google Drive"),
    NFS("NFS (not supported)"),
}

/** Non-secret configuration only. Passwords, keys and tokens live in [com.mediavault.core.security.CredentialStore]. */
@Entity(tableName = "destinations")
data class DestinationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val host: String = "",
    val port: Int = 0,
    val path: String = "",
    val username: String = "",
    /** JSON object: share, domain, ftpsImplicit, authMethod, hostKeyFingerprint, treeUri, googleConnected. */
    val options: String = "{}",
    val createdAt: Long,
) {
    fun opt(key: String): String = runCatching { JSONObject(options).optString(key, "") }.getOrDefault("")
    fun withOpt(key: String, value: String): DestinationEntity =
        copy(options = JSONObject(options.ifBlank { "{}" }).put(key, value).toString())
}

@Dao
interface DestinationDao {
    @Query("SELECT * FROM destinations ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<DestinationEntity>>

    @Query("SELECT * FROM destinations")
    suspend fun getAll(): List<DestinationEntity>

    @Query("SELECT * FROM destinations WHERE id = :id")
    suspend fun get(id: String): DestinationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: DestinationEntity)

    @Query("DELETE FROM destinations WHERE id = :id")
    suspend fun delete(id: String)
}
