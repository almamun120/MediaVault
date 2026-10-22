package com.mediavault.feature.home

import android.os.StatFs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mediavault.core.database.DestinationDao
import com.mediavault.core.database.JobStatus
import com.mediavault.core.database.ManagedFileDao
import com.mediavault.core.database.ManagedFileEntity
import com.mediavault.core.database.RuleDao
import com.mediavault.storage.ConnState
import com.mediavault.storage.DestinationRepository
import com.mediavault.transfer.QueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeState(
    val recent: List<ManagedFileEntity> = emptyList(),
    val queued: Int = 0,
    val failed: Int = 0,
    val destinations: Int = 0,
    val connected: Int = 0,
    val unreachable: Int = 0,
    val rules: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    files: ManagedFileDao, queue: QueueRepository, repo: DestinationRepository, rules: RuleDao,
) : ViewModel() {
    val state: StateFlow<HomeState> = combine(files.observeAll(), queue.allJobs, repo.destinations, repo.status, rules.observeAll()) { f, jobs, d, st, r ->
        HomeState(
            recent = f.take(10),
            queued = jobs.count { it.status == JobStatus.QUEUED || it.status == JobStatus.UPLOADING || it.status == JobStatus.PAUSED },
            failed = jobs.count { it.status == JobStatus.FAILED },
            destinations = d.size,
            connected = d.count { st[it.id] is ConnState.Ok },
            unreachable = d.count { st[it.id] is ConnState.Failed },
            rules = r.count { it.enabled },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@Composable
fun HomeScreen(
    onOpenCamera: () -> Unit, onOpenAudio: () -> Unit, onOpenScanner: () -> Unit, onOpenTransfers: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val free = remember0 {
        runCatching { StatFs((ctx.getExternalFilesDir(null) ?: ctx.filesDir).path).availableBytes }.getOrNull()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("MediaVault", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenCamera, Modifier.weight(1f)) { Icon(Icons.Filled.PhotoCamera, null); Text("  Photo") }
            Button(onClick = onOpenCamera, Modifier.weight(1f)) { Icon(Icons.Filled.Videocam, null); Text("  Video") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenAudio, Modifier.weight(1f)) { Icon(Icons.Filled.Mic, null); Text("  Audio") }
            Button(onClick = onOpenScanner, Modifier.weight(1f)) { Icon(Icons.Filled.DocumentScanner, null); Text("  Scan") }
        }
        Card(Modifier.fillMaxWidth().clickable { onOpenTransfers() }) {
            Column(Modifier.padding(14.dp)) {
                Text("Upload queue: ${s.queued}", style = MaterialTheme.typography.titleMedium)
                Text(if (s.failed > 0) "${s.failed} failed — tap to review and retry" else "No failed transfers", color = if (s.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Destinations: ${s.destinations}  •  Rules: ${s.rules}", style = MaterialTheme.typography.titleMedium)
                Text("Connected: ${s.connected}   Unreachable: ${s.unreachable}   (status updates when you test a connection in Storage)", style = MaterialTheme.typography.bodySmall)
                free?.let { Text("Free on this device: %.1f GB".format(it / 1_073_741_824.0), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("Recent media (${s.recent.size})", style = MaterialTheme.typography.titleMedium)
        if (s.recent.isEmpty()) Text("Nothing captured yet.", style = MaterialTheme.typography.bodyMedium)
        s.recent.forEach { f ->
            val flag = if (f.interrupted) "  (interrupted)" else ""
            Text("${f.displayName} — ${f.sizeBytes / 1024} KB$flag", Modifier.padding(vertical = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun <T> remember0(calc: () -> T): T = androidx.compose.runtime.remember { calc() }
