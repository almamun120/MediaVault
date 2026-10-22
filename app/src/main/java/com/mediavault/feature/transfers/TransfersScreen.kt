package com.mediavault.feature.transfers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mediavault.core.database.DestinationDao
import com.mediavault.core.database.JobStatus
import com.mediavault.core.database.UploadJobEntity
import com.mediavault.transfer.QueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TransfersViewModel @Inject constructor(private val queue: QueueRepository, dests: DestinationDao) : ViewModel() {
    val jobs: StateFlow<List<UploadJobEntity>> = queue.allJobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val destinationNames: StateFlow<Map<String, String>> = kotlinx.coroutines.flow.flow { emit(dests.getAll().associate { it.id to it.name }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    fun retry(id: Long) = viewModelScope.launch { queue.retry(id) }
    fun pause(id: Long) = viewModelScope.launch { queue.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { queue.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { queue.cancel(id) }
    fun remove(id: Long) = viewModelScope.launch { queue.remove(id) }
    fun clearFinished() = viewModelScope.launch { queue.clearFinished() }
}

private enum class Tab(val label: String, val statuses: Set<String>?) {
    ALL("All", null),
    ACTIVE("Queued", setOf(JobStatus.QUEUED, JobStatus.UPLOADING, JobStatus.PAUSED)),
    DONE("Completed", setOf(JobStatus.DONE)),
    FAILED("Failed", setOf(JobStatus.FAILED)),
}

@Composable
fun TransfersScreen(vm: TransfersViewModel = hiltViewModel()) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val names by vm.destinationNames.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.ALL) }
    val shown = jobs.filter { tab.statuses == null || it.status in tab.statuses!! }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Transfers", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            Tab.entries.forEach { t ->
                val n = jobs.count { t.statuses == null || it.status in t.statuses }
                FilterChip(tab == t, { tab = t }, label = { Text("${t.label} ($n)") }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        if (jobs.any { it.status == JobStatus.DONE || it.status == JobStatus.CANCELED }) {
            TextButton(onClick = { vm.clearFinished() }) { Text("Clear completed and canceled") }
        }
        if (shown.isEmpty()) Text("Nothing here.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { j ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(File(j.filePath).name, style = MaterialTheme.typography.titleSmall)
                        Text("→ ${names[j.destinationId] ?: "(removed)"} ${j.remoteDir}/${j.remoteName}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            when (j.status) {
                                JobStatus.UPLOADING -> "Uploading… ${if (j.total > 0) j.transferred * 100 / j.total else 0}%"
                                JobStatus.QUEUED -> if (j.attempts > 0) "Waiting to retry (attempt ${j.attempts})" else "Queued — waits for the right network/charging conditions"
                                JobStatus.PAUSED -> "Paused"
                                JobStatus.DONE -> "Completed" + (j.errorMessage?.let { " — $it" } ?: "")
                                JobStatus.CANCELED -> "Canceled"
                                else -> "Failed"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (j.status == JobStatus.UPLOADING && j.total > 0) LinearProgressIndicator(progress = { j.transferred.toFloat() / j.total }, modifier = Modifier.fillMaxWidth())
                        if (j.errorType != null && j.status != JobStatus.DONE) {
                            Text("${j.errorType}: ${j.errorMessage}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (j.status) {
                                JobStatus.FAILED, JobStatus.CANCELED -> OutlinedButton(onClick = { vm.retry(j.id) }) { Text("Retry") }
                                JobStatus.PAUSED -> OutlinedButton(onClick = { vm.resume(j.id) }) { Text("Resume") }
                                JobStatus.QUEUED, JobStatus.UPLOADING -> {
                                    OutlinedButton(onClick = { vm.pause(j.id) }) { Text("Pause") }
                                    OutlinedButton(onClick = { vm.cancel(j.id) }) { Text("Cancel") }
                                }
                            }
                            if (j.status == JobStatus.PAUSED) OutlinedButton(onClick = { vm.cancel(j.id) }) { Text("Cancel") }
                            if (j.status in setOf(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELED)) TextButton(onClick = { vm.remove(j.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
