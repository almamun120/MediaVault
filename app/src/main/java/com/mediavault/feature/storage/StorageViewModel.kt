package com.mediavault.feature.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediavault.core.database.DestinationEntity
import com.mediavault.storage.ConnState
import com.mediavault.storage.DestinationRepository
import com.mediavault.storage.api.RemoteEntry
import com.mediavault.storage.api.StorageResult
import com.mediavault.storage.sftp.SftpStorageProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StorageViewModel @Inject constructor(private val repo: DestinationRepository) : ViewModel() {
    val destinations: StateFlow<List<DestinationEntity>> =
        repo.destinations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val status: StateFlow<Map<String, ConnState>> = repo.status

    fun save(d: DestinationEntity, secrets: Map<String, String>, onDone: () -> Unit) {
        viewModelScope.launch { repo.save(d, secrets); onDone() }
    }
    fun test(d: DestinationEntity) { viewModelScope.launch { repo.test(d) } }
    fun duplicate(id: String) { viewModelScope.launch { repo.duplicate(id) } }
    fun delete(id: String) { viewModelScope.launch { repo.delete(id) } }
    fun clearCredentials(id: String) = repo.clearCredentials(id)
    fun hasPassword(id: String) = repo.hasPassword(id)
    fun hasKey(id: String) = repo.hasKey(id)

    fun browse(d: DestinationEntity, path: String, onResult: (StorageResult<List<RemoteEntry>>) -> Unit) {
        viewModelScope.launch { onResult(repo.list(d, path)) }
    }

    fun fetchSftpFingerprint(host: String, port: Int, onResult: (StorageResult<String>) -> Unit) {
        viewModelScope.launch { onResult(SftpStorageProvider.fetchHostFingerprint(host, port)) }
    }
}
