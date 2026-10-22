package com.mediavault.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mediavault.core.settings.AppSettings
import com.mediavault.core.settings.SettingsRepository
import com.mediavault.core.util.FilenameTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    fun save(s: AppSettings) { viewModelScope.launch { repo.save(s) } }
}

private fun preview(template: String, ext: String): String {
    val base = FilenameTemplate.render(template, ZonedDateTime.now())
    return "$base.$ext"
}

/** A template is valid when it renders to something other than the bare fallback name and has no braces left over. */
private fun valid(template: String) =
    template.isNotBlank() && !FilenameTemplate.render(template, ZonedDateTime.now()).contains('{')

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(saved) }
    var baseline by remember { mutableStateOf(saved) }
    // Adopt stored values (e.g. after DataStore loads) unless the user already started editing.
    LaunchedEffect(saved) {
        if (draft == baseline) draft = saved
        baseline = saved
    }

    val ok = valid(draft.photoTemplate) && valid(draft.videoTemplate) && valid(draft.audioTemplate) && valid(draft.documentTemplate)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Filename templates", style = MaterialTheme.typography.titleMedium)
        Text("Put a date pattern in braces, e.g. IMG_{yyyyMMdd_HHmmss}. Existing files are never overwritten; a number is added on collision.", style = MaterialTheme.typography.bodySmall)
        TemplateField("Photos", draft.photoTemplate, "jpg") { draft = draft.copy(photoTemplate = it) }
        TemplateField("Videos", draft.videoTemplate, "mp4") { draft = draft.copy(videoTemplate = it) }
        TemplateField("Audio", draft.audioTemplate, "m4a") { draft = draft.copy(audioTemplate = it) }
        TemplateField("Scanned documents", draft.documentTemplate, "pdf") { draft = draft.copy(documentTemplate = it) }

        Text("Audio quality", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(64, 128, 192, 256).forEach { k ->
                FilterChip(selected = draft.audioBitrateKbps == k, onClick = { draft = draft.copy(audioBitrateKbps = k) }, label = { Text("$k kbps") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(22050, 44100, 48000).forEach { r ->
                FilterChip(selected = draft.audioSampleRate == r, onClick = { draft = draft.copy(audioSampleRate = r) }, label = { Text("${r / 1000.0} kHz") })
            }
        }

        Text("Default video quality", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("480p", "720p", "1080p", "4K").forEach { q ->
                FilterChip(selected = draft.defaultVideoQuality == q, onClick = { draft = draft.copy(defaultVideoQuality = q) }, label = { Text(q) })
            }
        }
        Text("If the device does not support the chosen quality, the closest supported one is used.", style = MaterialTheme.typography.bodySmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.save(draft) }, enabled = ok && draft != saved) { Text("Save") }
            OutlinedButton(onClick = { draft = AppSettings() }) { Text("Reset to defaults") }
        }
        if (!ok) Text("A template is empty or contains an invalid date pattern.", color = MaterialTheme.colorScheme.error)

        RulesSection()
    }
}

@Composable
private fun TemplateField(label: String, value: String, ext: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) }, isError = !valid(value),
        supportingText = { Text("Example: ${preview(value, ext)}") },
        modifier = Modifier.fillMaxWidth(),
    )
}
