package com.mediavault.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mediavault.core.database.UploadJobEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One unique WorkManager work per job ("upload-<id>"). KEEP means enqueueing twice is a no-op, which is what
 * prevents duplicate uploads; REPLACE is used only for explicit retry/resume. Work survives app restarts and
 * reboots (WorkManager persists it). Android decides *when* it runs: nothing is guaranteed to start immediately.
 */
interface UploadScheduler {
    fun enqueue(job: UploadJobEntity, replace: Boolean = false)
    fun cancel(jobId: Long)
}

@Singleton
class WorkManagerUploadScheduler @Inject constructor(@ApplicationContext private val context: Context) : UploadScheduler {
    private val wm get() = WorkManager.getInstance(context)

    override fun enqueue(job: UploadJobEntity, replace: Boolean) {
        val network = when {
            !job.requiresNetwork -> NetworkType.NOT_REQUIRED
            job.wifiOnly -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(network).setRequiresCharging(job.chargingOnly).build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_JOB_ID to job.id))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("upload")
            .build()
        wm.enqueueUniqueWork(workName(job.id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(jobId: Long) { wm.cancelUniqueWork(workName(jobId)) }

    private fun workName(id: Long) = "upload-$id"
}
