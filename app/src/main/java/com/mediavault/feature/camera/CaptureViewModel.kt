package com.mediavault.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediavault.transfer.MediaIngestor
import com.mediavault.core.model.MediaKind
import com.mediavault.core.settings.AppSettings
import com.mediavault.core.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val ingestor: MediaIngestor,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Adds a finished capture to the Room index. */
    fun register(file: File, kind: MediaKind, interrupted: Boolean = false) {
        viewModelScope.launch { ingestor.ingest(file, kind, interrupted) }
    }
}
