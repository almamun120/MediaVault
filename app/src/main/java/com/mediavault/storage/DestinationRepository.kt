package com.mediavault.storage

import com.mediavault.core.database.DestinationDao
import com.mediavault.core.database.DestinationEntity
import com.mediavault.core.security.CredentialStore
import com.mediavault.storage.api.RemoteEntry
import com.mediavault.storage.api.StorageError
import com.mediavault.storage.api.StorageResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConnState {
    data object Unknown : ConnState
    data object Testing : ConnState
    data class Ok(val atMs: Long) : ConnState
    data class Failed(val error: StorageError) : ConnState
}

@Singleton
class DestinationRepository @Inject constructor(
    private val dao: DestinationDao,
    private val credentials: CredentialStore,
    private val factory: StorageProviderFactory,
) {
    val destinations: Flow<List<DestinationEntity>> = dao.observeAll()

    private val _status = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val status: StateFlow<Map<String, ConnState>> = _status.asStateFlow()

    /** [secrets] holds only values the user typed (password, privateKey, passphrase); blank = keep what is stored. */
    suspend fun save(d: DestinationEntity, secrets: Map<String, String>) {
        dao.upsert(d)
        secrets.forEach { (k, v) -> if (v.isNotBlank()) credentials.put(d.id, k, v) }
        _status.update { it - d.id }
    }

    suspend fun duplicate(id: String) {
        val src = dao.get(id) ?: return
        val copy = src.copy(id = UUID.randomUUID().toString(), name = "${src.name} (copy)", createdAt = System.currentTimeMillis())
        dao.upsert(copy)
        credentials.copy(src.id, copy.id)
    }

    /** Removes the destination AND its stored credentials. Already-uploaded remote files are not touched. */
    suspend fun delete(id: String) {
        dao.delete(id)
        credentials.clear(id)
        _status.update { it - id }
    }

    fun clearCredentials(id: String) = credentials.clear(id)
    fun hasPassword(id: String) = credentials.has(id, "password")
    fun hasKey(id: String) = credentials.has(id, "privateKey")

    suspend fun test(d: DestinationEntity) {
        _status.update { it + (d.id to ConnState.Testing) }
        val state = when (val r = factory.create(d).testConnection()) {
            is StorageResult.Success -> ConnState.Ok(System.currentTimeMillis())
            is StorageResult.Failure -> ConnState.Failed(r.error)
        }
        _status.update { it + (d.id to state) }
    }

    suspend fun list(d: DestinationEntity, path: String): StorageResult<List<RemoteEntry>> = factory.create(d).listFiles(path)
}
