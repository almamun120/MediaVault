package com.mediavault.feature.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediavault.core.settings.AppSettings
import com.mediavault.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel
class AudioViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    // DataStore read is a single small value; blocking briefly on a button tap is acceptable here.
    fun currentSettings(): AppSettings = runBlocking { repo.settings.first() }
}

private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun AudioScreen(vm: AudioViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by AudioRecorderState.state.collectAsStateWithLifecycle()
    var hasMic by remember { mutableStateOf(has(context, Manifest.permission.RECORD_AUDIO)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasMic = has(context, Manifest.permission.RECORD_AUDIO)
        // Notification permission is optional: recording works without it, only the notification is hidden.
    }

    fun begin() {
        val s = vm.currentSettings()
        AudioRecordingService.send(context, AudioRecordingService.ACTION_START) {
            putExtra(AudioRecordingService.EXTRA_TEMPLATE, s.audioTemplate)
            putExtra(AudioRecordingService.EXTRA_BITRATE_KBPS, s.audioBitrateKbps)
            putExtra(AudioRecordingService.EXTRA_SAMPLE_RATE, s.audioSampleRate)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val secs = state.durationMs / 1000
        Text("%02d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.displayLarge)
        Text("%.2f MB".format(state.bytes / 1_048_576.0), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.status) {
                AudioStatus.IDLE -> "Ready"
                AudioStatus.RECORDING -> "Recording… (continues if you leave the app; a notification is shown)"
                AudioStatus.PAUSED -> "Paused"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        state.lastSavedName?.let { Text("Saved $it", color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))

        if (!hasMic) {
            Text("Microphone access is needed to record audio. Nothing is uploaded unless you set up a destination.", Modifier.padding(bottom = 12.dp))
            Button(onClick = {
                val perms = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                launcher.launch(perms.toTypedArray())
            }) { Text("Allow microphone") }
        } else when (state.status) {
            AudioStatus.IDLE -> Button(onClick = ::begin) {
                Icon(Icons.Filled.Mic, null); Text("  Start recording")
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    AudioRecordingService.send(
                        context,
                        if (state.status == AudioStatus.PAUSED) AudioRecordingService.ACTION_RESUME else AudioRecordingService.ACTION_PAUSE,
                    )
                }) {
                    if (state.status == AudioStatus.PAUSED) { Icon(Icons.Filled.PlayArrow, null); Text("  Resume") }
                    else { Icon(Icons.Filled.Pause, null); Text("  Pause") }
                }
                Button(onClick = { AudioRecordingService.send(context, AudioRecordingService.ACTION_STOP) }) {
                    Icon(Icons.Filled.Stop, null); Text("  Stop & save")
                }
            }
        }
    }
}
