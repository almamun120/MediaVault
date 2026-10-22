package com.mediavault.feature.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediavault.core.database.DestinationEntity
import com.mediavault.core.database.DestinationType
import com.mediavault.storage.ConnState
import com.mediavault.storage.api.RemoteEntry
import com.mediavault.storage.api.StorageResult

private fun iconFor(t: DestinationType): ImageVector = when (t) {
    DestinationType.LOCAL_APP -> Icons.Filled.Folder
    DestinationType.SAF_FOLDER -> Icons.Filled.SdCard
    DestinationType.FTP -> Icons.Filled.LockOpen
    DestinationType.FTPS, DestinationType.SFTP -> Icons.Filled.Lock
    DestinationType.GOOGLE_DRIVE, DestinationType.WEBDAV -> Icons.Filled.Cloud
    else -> Icons.Filled.Storage
}

private fun summary(d: DestinationEntity): String {
    val t = DestinationType.valueOf(d.type)
    return when (t) {
        DestinationType.SAF_FOLDER -> "Folder chosen on this device"
        DestinationType.GOOGLE_DRIVE -> "Drive › ${d.path.ifBlank { "MediaVault" }}"
        DestinationType.WEBDAV -> d.host
        DestinationType.LOCAL_APP -> "App storage › ${d.path.ifBlank { "Destination" }}"
        DestinationType.SMB -> "\\\\${d.host}\\${d.opt("share")}\\${d.path.trim('/')}"
        else -> "${d.host}:${d.port}${if (d.path.isBlank()) "" else "/" + d.path.trim('/')}"
    }
}

@Composable
fun StorageScreen(vm: StorageViewModel = hiltViewModel()) {
    val list by vm.destinations.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<DestinationEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<DestinationEntity?>(null) }
    var browsing by remember { mutableStateOf<DestinationEntity?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No destinations yet", style = MaterialTheme.typography.titleLarge)
                Text("Add SFTP, SMB, FTP/FTPS, WebDAV, Google Drive or a device/SD-card folder. Credentials are stored encrypted.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { d ->
                val type = DestinationType.valueOf(d.type)
                val st = status[d.id] ?: ConnState.Unknown
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconFor(type), type.label)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(d.name, style = MaterialTheme.typography.titleMedium)
                                Text("${type.label} • ${summary(d)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (type == DestinationType.FTP) {
                            Text("Unencrypted: the password and files travel in clear text. Prefer SFTP or FTPS.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (type == DestinationType.WEBDAV && d.host.startsWith("http://")) {
                            Text("Plain HTTP: the password and files are not encrypted. Use https:// if the server supports it.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        when (st) {
                            ConnState.Unknown -> Text("Not tested", style = MaterialTheme.typography.bodySmall)
                            ConnState.Testing -> Text("Testing connection…", style = MaterialTheme.typography.bodySmall)
                            is ConnState.Ok -> Text("Connected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            is ConnState.Failed -> Text("${st.error.type}: ${st.error.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { vm.test(d) }, enabled = st != ConnState.Testing) { Text("Test connection") }
                            TextButton(onClick = { browsing = d }) { Text("Browse") }
                            Box(Modifier.weight(1f))
                            IconButton(onClick = { editing = d }) { Icon(Icons.Filled.Edit, "Edit ${d.name}") }
                            IconButton(onClick = { vm.duplicate(d.id) }) { Icon(Icons.Filled.ContentCopy, "Duplicate ${d.name}") }
                            IconButton(onClick = { toDelete = d }) { Icon(Icons.Filled.Delete, "Remove ${d.name}") }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Add destination") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating) DestinationEditor(null, vm) { creating = false }
    editing?.let { d -> DestinationEditor(d, vm) { editing = null } }
    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove destination?") },
            text = { Text("\"${d.name}\" and its saved credentials will be deleted from this device. Files already stored there are not touched.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.id); toDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
    browsing?.let { d -> BrowseDialog(d, vm) { browsing = null } }
}

@Composable
private fun BrowseDialog(d: DestinationEntity, vm: StorageViewModel, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf("/") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(path) {
        loading = true
        vm.browse(d, path) { r ->
            loading = false
            when (r) {
                is StorageResult.Success -> { entries = r.value.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() }); error = null }
                is StorageResult.Failure -> { entries = emptyList(); error = r.error.message }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(path) },
        text = {
            Column {
                if (path != "/") TextButton(onClick = { path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }) { Text("⬆ Up") }
                when {
                    loading -> Text("Loading…")
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    entries.isEmpty() -> Text("Empty folder")
                    else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(entries, key = { it.path }) { e ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                if (e.isDirectory) TextButton(onClick = { path = e.path }) { Text("📁 ${e.name}") }
                                else Text("${e.name}  (${e.sizeBytes / 1024} KB)", Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
