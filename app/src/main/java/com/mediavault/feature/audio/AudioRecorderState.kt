package com.mediavault.feature.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AudioStatus { IDLE, RECORDING, PAUSED }

data class AudioState(
    val status: AudioStatus = AudioStatus.IDLE,
    val durationMs: Long = 0,
    val bytes: Long = 0,
    val lastSavedName: String? = null,
    val error: String? = null,
)

/** Process-wide state written by [AudioRecordingService] and observed by the UI. */
object AudioRecorderState {
    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()
    fun update(transform: (AudioState) -> AudioState) = _state.update(transform)
}
