package com.mediavault.transfer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(PROGRESS, "Upload progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(FAILURES, "Upload failures", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun progress(name: String, percent: Int): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading $name")
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun foregroundInfo(jobId: Long, name: String, percent: Int): ForegroundInfo {
        val n = progress(name, percent)
        val id = NOTIF_BASE + (jobId % 1000).toInt()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, n)
    }

    /** Failure notices are shown only if the user allowed notifications; otherwise the Transfers screen shows them. */
    fun failed(jobId: Long, name: String, message: String) {
        ensureChannels()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, FAILURES)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Upload failed: $name")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(FAIL_BASE + (jobId % 1000).toInt(), n)
    }

    private companion object {
        const val PROGRESS = "upload_progress"
        const val FAILURES = "upload_failures"
        const val NOTIF_BASE = 2000
        const val FAIL_BASE = 3000
    }
}
