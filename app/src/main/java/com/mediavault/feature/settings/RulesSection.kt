package com.mediavault.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mediavault.core.database.ConflictPolicy
import com.mediavault.core.database.DestinationDao
import com.mediavault.core.database.DestinationEntity
import com.mediavault.core.database.RuleDao
import com.mediavault.core.database.RuleEntity
import com.mediavault.core.database.RuleMedia
import com.mediavault.core.database.UploadTiming
import com.mediavault.core.util.FilenameTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RulesViewModel @Inject constructor(private val rules: RuleDao, destinations: DestinationDao) : ViewModel() {
    val ruleList: StateFlow<List<RuleEntity>> = rules.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    // Re-read when rules change is not needed: destinations rarely change while this screen is open.
    val destinationList: StateFlow<List<DestinationEntity>> = kotlinx.coroutines.flow.flow { emit(destinations.getAll()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(r: RuleEntity) { viewModelScope.launch { rules.upsert(r) } }
    fun delete(id: String) { viewModelScope.launch { rules.delete(id) } }
}

@Composable
fun RulesSection(vm: RulesViewModel = hiltViewModel()) {
    val rules by vm.ruleList.collectAsStateWithLifecycle()
    val dests by vm.destinationList.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RuleEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<RuleEntity?>(null) }
    fun destName(id: String) = dests.firstOrNull { it.id == id }?.name ?: "(removed)"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Upload rules", style = MaterialTheme.typography.titleMedium)
        Text("Every capture is saved on this device first. Rules decide what is uploaded afterwards. Nothing is uploaded without a rule or a manual upload.", style = MaterialTheme.typography.bodySmall)
        if (rules.isEmpty()) Text("No rules yet.", style = MaterialTheme.typography.bodyMedium)
        rules.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${RuleMedia.valueOf(r.media).label} → ${destName(r.primaryDestinationId)}" + if (r.backups().isNotEmpty()) " + ${r.backups().joinToString { destName(it) }}" else "", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${UploadTiming.valueOf(r.timing).label} • ${if (r.wifiOnly) "Wi-Fi/unmetered only" else "any network"}${if (r.chargingOnly) " • charging only" else ""}" +
                            " • folder /${r.folderTemplate.trim('/')}${if (r.deleteLocalAfterUpload) " • deletes local copy after verified upload" else ""}${if (!r.enabled) " • DISABLED" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { editing = r }) { Text("Edit") }
                        TextButton(onClick = { toDelete = r }) { Text("Delete") }
                    }
                }
            }
        }
        Button(onClick = { creating = true }, enabled = dests.isNotEmpty()) { Text("Add rule") }
        if (dests.isEmpty()) Text("Add a destination in the Storage tab first.", style = MaterialTheme.typography.bodySmall)
    }

    if (creating) RuleEditor(null, dests, { vm.save(it); creating = false }) { creating = false }
    editing?.let { r -> RuleEditor(r, dests, { vm.save(it); editing = null }) { editing = null } }
    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete rule?") },
            text = { Text("Files already uploaded stay where they are. Future captures will no longer follow this rule.") },
            confirmButton = { TextButton(onClick = { vm.delete(r.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(initial: RuleEntity?, dests: List<DestinationEntity>, onSave: (RuleEntity) -> Unit, onDismiss: () -> Unit) {
    var media by remember { mutableStateOf(initial?.media ?: RuleMedia.PHOTO.name) }
    var primary by remember { mutableStateOf(initial?.primaryDestinationId ?: dests.firstOrNull()?.id.orEmpty()) }
    var backups by remember { mutableStateOf(initial?.backups()?.toSet() ?: emptySet()) }
    var folder by remember { mutableStateOf(initial?.folderTemplate ?: "Photos/{yyyy}/{MM}") }
    var fileTpl by remember { mutableStateOf(initial?.filenameTemplate ?: "") }
    var timing by remember { mutableStateOf(initial?.timing ?: UploadTiming.IMMEDIATE.name) }
    var wifi by remember { mutableStateOf(initial?.wifiOnly ?: true) }
    var charging by remember { mutableStateOf(initial?.chargingOnly ?: false) }
    var conflict by remember { mutableStateOf(initial?.conflict ?: ConflictPolicy.RENAME.name) }
    var deleteLocal by remember { mutableStateOf(initial?.deleteLocalAfterUpload ?: false) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    val now = remember { ZonedDateTime.now() }
    val folderPreview = FilenameTemplate.renderPath(folder, now)
    val validTemplates = !folderPreview.contains('{') && (fileTpl.isBlank() || !FilenameTemplate.render(fileTpl, now).contains('{'))
    val valid = primary.isNotBlank() && validTemplates

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Media type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleMedia.entries.forEach { m -> FilterChip(media == m.name, { media = m.name }, label = { Text(m.label) }, modifier = Modifier.padding(end = 6.dp)) }
                }
                Text("Primary destination")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dests.forEach { d -> FilterChip(primary == d.id, { primary = d.id; backups = backups - d.id }, label = { Text(d.name) }, modifier = Modifier.padding(end = 6.dp)) }
                }
                if (dests.size > 1) {
                    Text("Also copy to (backups)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dests.filter { it.id != primary }.forEach { d ->
                            FilterChip(d.id in backups, { backups = if (d.id in backups) backups - d.id else backups + d.id }, label = { Text(d.name) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                }
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder template") }, singleLine = true, isError = folderPreview.contains('{'), supportingText = { Text("Example: /$folderPreview") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fileTpl, { fileTpl = it }, label = { Text("Remote file name template (optional)") }, singleLine = true, supportingText = { Text("Blank keeps the local name. Example: Trip_{yyyyMMdd_HHmmss}") }, modifier = Modifier.fillMaxWidth())
                Text("Upload timing")
                Row { UploadTiming.entries.forEach { t -> FilterChip(timing == t.name, { timing = t.name }, label = { Text(t.label) }, modifier = Modifier.padding(end = 6.dp)) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(wifi, { wifi = it }); Text("  Wi-Fi / unmetered network only") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(charging, { charging = it }); Text("  Only while charging") }
                Text("If the file name already exists")
                Row { ConflictPolicy.entries.forEach { c -> FilterChip(conflict == c.name, { conflict = c.name }, label = { Text(c.label) }, modifier = Modifier.padding(end = 6.dp)) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(deleteLocal, { deleteLocal = it }); Text("  Delete the local copy after upload") }
                if (deleteLocal) Text("Only after every upload for that file succeeded and its size was verified. The only copy is never deleted.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, { enabled = it }); Text("  Rule enabled") }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    RuleEntity(
                        id = initial?.id ?: UUID.randomUUID().toString(), media = media, primaryDestinationId = primary,
                        backupDestinationIds = backups.joinToString(","), folderTemplate = folder.trim(), filenameTemplate = fileTpl.trim(),
                        timing = timing, wifiOnly = wifi, chargingOnly = charging, conflict = conflict,
                        deleteLocalAfterUpload = deleteLocal, enabled = enabled, createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
