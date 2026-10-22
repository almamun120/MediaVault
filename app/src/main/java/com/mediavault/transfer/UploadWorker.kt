package com.mediavault.transfer

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mediavault.core.database.ConflictPolicy
import com.mediavault.core.database.DestinationDao
import com.mediavault.core.database.JobStatus
import com.mediavault.core.database.ManagedFileDao
import com.mediavault.core.database.UploadJobDao
import com.mediavault.core.database.UploadJobEntity
import com.mediavault.storage.StorageProviderFactory
import com.mediavault.storage.api.StorageError
import com.mediavault.storage.api.StorageErrorType
import com.mediavault.storage.api.StorageResult
import com.mediavault.storage.api.joinPath
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val jobs: UploadJobDao,
    private val files: ManagedFileDao,
    private val destinations: DestinationDao,
    private val factory: StorageProviderFactory,
    private val notifier: UploadNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_JOB_ID, -1)
        val job = jobs.get(id) ?: return Result.success()
        if (job.status != JobStatus.QUEUED && job.status != JobStatus.UPLOADING) return Result.success() // paused, canceled, done...

        val file = File(job.filePath)
        if (!file.isFile) return fail(job, StorageError(StorageErrorType.NOT_FOUND, "The local file no longer exists"))
        val dest = destinations.get(job.destinationId)
            ?: return fail(job, StorageError(StorageErrorType.NOT_FOUND, "The destination was removed"))

        val attempt = job.attempts + 1
        jobs.markUploading(id, attempt, now())
        try { setForeground(notifier.foregroundInfo(id, file.name, 0)) } catch (e: Exception) { /* not allowed from background on some Android versions: continue without */ }

        try {
            val provider = factory.create(dest)
            var name = job.remoteName

            // Idempotency: if an earlier attempt finished the upload but died before recording it, the same
            // name + size is already there, so we just mark the job done instead of uploading twice.
            when (val listing = provider.listFiles(job.remoteDir)) {
                is StorageResult.Success -> {
                    val existing = listing.value.firstOrNull { it.name == name && !it.isDirectory }
                    if (existing != null) {
                        if (existing.sizeBytes == file.length()) return done(job, name, "Already on the destination")
                        if (job.conflict == ConflictPolicy.SKIP.name) return done(job, name, "Skipped: a different file with this name exists")
                        name = uniqueName(listing.value.map { it.name }.toSet(), name)
                    }
                }
                is StorageResult.Failure -> if (listing.error.type != StorageErrorType.NOT_FOUND) return handle(job, attempt, listing.error)
            }

            var last = 0L
            val result = provider.uploadFile(file, joinPath(job.remoteDir, name)) { sent, total ->
                val t = SystemClock.elapsedRealtime()
                if (t - last > 500) {
                    last = t
                    runBlocking { jobs.setProgress(id, sent, total, now()) }
                    val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                    try { setForegroundAsync(notifier.foregroundInfo(id, file.name, pct)) } catch (e: Exception) { }
                }
            }
            return when (result) {
                is StorageResult.Success -> done(job, name, if (name != job.remoteName) "Saved as $name" else null)
                is StorageResult.Failure -> handle(job, attempt, result.error)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { jobs.requeueIfUploading(id, now()) }
            throw e
        }
    }

    private suspend fun done(job: UploadJobEntity, name: String, note: String?): Result {
        jobs.markDone(job.id, name, note, now())
        maybeDeleteLocal(job)
        return Result.success()
    }

    private suspend fun handle(job: UploadJobEntity, attempt: Int, error: StorageError): Result =
        if (error.retryable && attempt < MAX_ATTEMPTS) {
            // Temporary problem (network, server busy, SD card out): back to the queue; WorkManager backs off exponentially.
            jobs.markError(job.id, JobStatus.QUEUED, error.type.name, error.message, now())
            Result.retry()
        } else fail(job, error)

    private suspend fun fail(job: UploadJobEntity, error: StorageError): Result {
        jobs.markError(job.id, JobStatus.FAILED, error.type.name, error.message, now())
        notifier.failed(job.id, File(job.filePath).name, error.message)
        return Result.failure()
    }

    /**
     * Local copy is deleted only when the rule asked for it AND every job for this file finished successfully
     * (each upload was size-verified by its provider). The only existing copy is never removed.
     */
    private suspend fun maybeDeleteLocal(job: UploadJobEntity) {
        val all = jobs.forFile(job.filePath)
        if (all.none { it.deleteLocalAfter }) return
        if (all.any { it.status != JobStatus.DONE }) return
        val f = File(job.filePath)
        if (f.isFile && f.delete()) files.markLocalDeleted(job.fileId)
    }

    private fun uniqueName(taken: Set<String>, name: String): String {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val MAX_ATTEMPTS = 8
    }
}
