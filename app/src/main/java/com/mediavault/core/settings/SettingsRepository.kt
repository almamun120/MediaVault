package com.mediavault.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val photoTemplate: String = "IMG_{yyyyMMdd_HHmmss}",
    val videoTemplate: String = "VID_{yyyyMMdd_HHmmss}",
    val audioTemplate: String = "AUD_{yyyyMMdd_HHmmss}",
    val documentTemplate: String = "SCAN_{yyyyMMdd_HHmmss}",
    val audioBitrateKbps: Int = 128,
    val audioSampleRate: Int = 44100,
    val defaultVideoQuality: String = "1080p",
)

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val photo = stringPreferencesKey("photo_template")
        val video = stringPreferencesKey("video_template")
        val audio = stringPreferencesKey("audio_template")
        val doc = stringPreferencesKey("document_template")
        val bitrate = intPreferencesKey("audio_bitrate")
        val rate = intPreferencesKey("audio_rate")
        val quality = stringPreferencesKey("video_quality")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            p[K.photo] ?: d.photoTemplate, p[K.video] ?: d.videoTemplate, p[K.audio] ?: d.audioTemplate, p[K.doc] ?: d.documentTemplate,
            p[K.bitrate] ?: d.audioBitrateKbps, p[K.rate] ?: d.audioSampleRate, p[K.quality] ?: d.defaultVideoQuality,
        )
    }

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p: androidx.datastore.preferences.core.MutablePreferences ->
            p[K.photo] = s.photoTemplate; p[K.video] = s.videoTemplate; p[K.audio] = s.audioTemplate; p[K.doc] = s.documentTemplate
            p[K.bitrate] = s.audioBitrateKbps; p[K.rate] = s.audioSampleRate; p[K.quality] = s.defaultVideoQuality
        }
    }
}
