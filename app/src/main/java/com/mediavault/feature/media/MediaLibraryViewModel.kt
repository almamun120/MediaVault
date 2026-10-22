package com.mediavault.feature.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediavault.core.database.DestinationDao
import com.mediavault.core.database.DestinationEntity
import com.mediavault.core.database.ManagedFileDao
import com.mediavault.core.database.UploadJobEntity
import com.mediavault.core.model.MediaKind
import com.mediavault.transfer.QueueRepository
import kotlinx.coroutines.flow.map
import com.mediavault.core.database.ManagedFileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class LibraryFilter(val label: String) { ALL("All"), PHOTO("Photos"), VIDEO("Videos"), AUDIO("Audio"), DOCUMENT("Documents"), INTERRUPTED("Interrupted") }
enum class LibrarySort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), LARGEST("Largest"), NAME("Name") }

@HiltViewModel
class MediaLibraryViewModel @Inject constructor(
    private val dao: ManagedFileDao,
    private val queue: QueueRepository,
    private val destinationDao: DestinationDao,
) : ViewModel() {
    val jobsByPath: StateFlow<Map<String, List<UploadJobEntity>>> =
        queue.allJobs.map { it.groupBy { j -> j.filePath } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val destinations: StateFlow<List<DestinationEntity>> = kotlinx.coroutines.flow.flow { emit(destinationDao.getAll()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Manual upload of a single file to a chosen destination (no rule involved). */
    fun uploadTo(item: ManagedFileEntity, destinationId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val ok = queue.enqueueManual(item.id, File(item.path), MediaKind.valueOf(item.kind), destinationId)
            onResult(if (ok) "Queued for upload" else "Already queued or uploaded to that destination")
        }
    }

    val filter = MutableStateFlow(LibraryFilter.ALL)
    val sort = MutableStateFlow(LibrarySort.NEWEST)
    val query = MutableStateFlow("")
    val grid = MutableStateFlow(true)

    val items: StateFlow<List<ManagedFileEntity>> =
        combine(dao.observeAll(), filter, sort, query) { all, f, s, q ->
            all.filter {
                when (f) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.INTERRUPTED -> it.interrupted
                    else -> it.kind == f.name
                } && it.displayName.contains(q.trim(), ignoreCase = true)
            }.let { list ->
                when (s) {
                    LibrarySort.NEWEST -> list.sortedByDescending { it.createdAt }
                    LibrarySort.OLDEST -> list.sortedBy { it.createdAt }
                    LibrarySort.LARGEST -> list.sortedByDescending { it.sizeBytes }
                    LibrarySort.NAME -> list.sortedBy { it.displayName.lowercase() }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(f: LibraryFilter) = filter.update { f }
    fun setSort(s: LibrarySort) = sort.update { s }
    fun setQuery(q: String) = query.update { q }
    fun toggleGrid() = grid.update { !it }

    /** Deletes the file and its index row. Only called after the user confirms. */
    fun delete(item: ManagedFileEntity) {
        viewModelScope.launch {
            File(item.path).delete()
            dao.delete(item.id)
        }
    }
}
