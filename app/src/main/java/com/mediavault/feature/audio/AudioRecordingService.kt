package com.mediavault.feature.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.mediavault.transfer.MediaIngestor
import com.mediavault.core.model.MediaKind
import com.mediavault.core.storage.MediaDirs
import com.mediavault.core.util.FilenameTemplate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Foreground service (type "microphone") that owns the MediaRecorder, so a recording survives the
 * app being backgrounded. It does not run forever: the user (or the system, under memory pressure)
 * can end it, and a killed process leaves an unfinalized .tmp file that cannot be played.
 */
@AndroidEntryPoint
class AudioRecordingService : Service() {
    @Inject lateinit var ingestor: MediaIngestor

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var tmp: File? = null
    private var template = "AUD_{yyyyMMdd_HHmmss}"
    private var startedAt = 0L
    private var accumulated = 0L
    private var paused = false

    private val ticker = object : Runnable {
        override fun run() {
            val elapsed = if (paused) accumulated else accumulated + (SystemClock.elapsedRealtime() - startedAt)
            AudioRecorderState.update { it.copy(durationMs = elapsed, bytes = tmp?.length() ?: 0) }
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                intent.getStringExtra(EXTRA_TEMPLATE) ?: template,
                intent.getIntExtra(EXTRA_BITRATE_KBPS, 128),
                intent.getIntExtra(EXTRA_SAMPLE_RATE, 44100),
            )
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopAndSave()
        }
        return START_NOT_STICKY
    }

    private fun start(template: String, bitrateKbps: Int, sampleRate: Int) {
        if (recorder != null) return
        this.template = template
        goForeground()
        val dir = MediaDirs.dirFor(this, MediaKind.AUDIO)
        // Only one recorder exists at a time, so any other .aud_*.tmp is an orphan from a killed process.
        dir.listFiles { f -> f.name.startsWith(".aud_") && f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val out = File(dir, ".aud_${System.currentTimeMillis()}.tmp")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(bitrateKbps * 1000)
            r.setAudioSamplingRate(sampleRate)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) { // IOException, IllegalStateException, RuntimeException (mic busy / unsupported settings)
            r.release()
            out.delete()
            AudioRecorderState.update { it.copy(status = AudioStatus.IDLE, error = "Could not start recording: ${e.message}") }
            stopForegroundCompat()
            stopSelf()
            return
        }
        recorder = r
        tmp = out
        paused = false
        accumulated = 0
        startedAt = SystemClock.elapsedRealtime()
        AudioRecorderState.update { it.copy(status = AudioStatus.RECORDING, durationMs = 0, bytes = 0, error = null) }
        handler.post(ticker)
    }

    private fun pause() {
        val r = recorder ?: return
        if (paused) return
        try { r.pause() } catch (e: IllegalStateException) { return }
        accumulated += SystemClock.elapsedRealtime() - startedAt
        paused = true
        AudioRecorderState.update { it.copy(status = AudioStatus.PAUSED) }
    }

    private fun resume() {
        val r = recorder ?: return
        if (!paused) return
        try { r.resume() } catch (e: IllegalStateException) { return }
        startedAt = SystemClock.elapsedRealtime()
        paused = false
        AudioRecorderState.update { it.copy(status = AudioStatus.RECORDING) }
    }

    private fun stopAndSave() {
        val r = recorder
        val file = tmp
        handler.removeCallbacks(ticker)
        recorder = null
        tmp = null
        var error: String? = null
        if (r != null) {
            try { r.stop() } catch (e: RuntimeException) { error = "Recording was too short or failed to finalize" }
            r.release()
        }
        var savedName: String? = null
        if (file != null && error == null && file.length() > 0) {
            val dir = file.parentFile!!
            val name = FilenameTemplate.render(template, ZonedDateTime.now())
            val dest = FilenameTemplate.uniqueFile(dir, name, MediaKind.AUDIO.extension)
            if (file.renameTo(dest)) {
                savedName = dest.name
                io.launch { ingestor.ingest(dest, MediaKind.AUDIO) }
            } else error = "Could not save the recording"
        } else file?.delete()
        AudioRecorderState.update { AudioState(lastSavedName = savedName, error = error) }
        stopForegroundCompat()
        stopSelf()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Audio recording", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, AudioRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MediaVault is recording audio")
            .setOngoing(true)
            .addAction(0, "Stop and save", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun stopForegroundCompat() = stopForeground(STOP_FOREGROUND_REMOVE)

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (recorder != null) stopAndSaveSilently()
        super.onDestroy()
    }

    /** Service killed while recording: finalize what we can instead of leaking the recorder. */
    private fun stopAndSaveSilently() = stopAndSave()

    companion object {
        const val ACTION_START = "com.mediavault.audio.START"
        const val ACTION_PAUSE = "com.mediavault.audio.PAUSE"
        const val ACTION_RESUME = "com.mediavault.audio.RESUME"
        const val ACTION_STOP = "com.mediavault.audio.STOP"
        const val EXTRA_TEMPLATE = "template"
        const val EXTRA_BITRATE_KBPS = "bitrate"
        const val EXTRA_SAMPLE_RATE = "rate"
        private const val CHANNEL = "audio_recording"
        private const val NOTIF_ID = 1001

        fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val i = Intent(context, AudioRecordingService::class.java).setAction(action).apply(configure)
            if (action == ACTION_START) androidx.core.content.ContextCompat.startForegroundService(context, i)
            else context.startService(i)
        }
    }
}
