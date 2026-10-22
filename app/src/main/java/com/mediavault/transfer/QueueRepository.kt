package com.mediavault.transfer

import com.mediavault.core.database.ConflictPolicy
import com.mediavault.core.database.DestinationDao
import com.mediavault.core.database.DestinationType
import com.mediavault.core.database.JobStatus
import com.mediavault.core.database.RuleDao
import com.mediavault.core.database.UploadJobDao
import com.mediavault.core.database.UploadJobEntity
import com.mediavault.core.model.MediaKind
import com.mediavault.core.rules.RuleRouter
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueRepository @Inject constructor(
    private val jobs: UploadJobDao,
    private val rules: RuleDao,
    private val destinations: DestinationDao,
    private val scheduler: UploadScheduler,
) {
    val allJobs: Flow<List<UploadJobEntity>> = jobs.observeAll()

    /** Applies the saved rules to a freshly captured file. Interrupted/corrupt recordings are never auto-uploaded. */
    suspend fun enqueueFromRules(fileId: Long, file: File, kind: MediaKind) {
        val all = destinations.getAll()
        val plan = RuleRouter.plan(rules.getEnabled(), kind, file.name, ZonedDateTime.now(), all.map { it.id }.toSet())
        for (p in plan) {
            val type = all.first { it.id == p.destinationId }.type
            create(fileId, file, p.destinationId, p.remoteDir, p.remoteName, type, p.wifiOnly, p.chargingOnly, p.conflict, p.deleteLocalAfter)
        }
    }

    /** User-chosen upload from the library: no rule, default folder "/MediaVault/<kind>", any network. */
    suspend fun enqueueManual(fileId: Long, file: File, kind: MediaKind, destinationId: String): Boolean {
        val dest = destinations.get(destinationId) ?: return false
        return create(fileId, file, destinationId, "/" + kind.folder, file.name, dest.type, false, false, ConflictPolicy.RENAME, false)
    }

    private suspend fun create(
        fileId: Long, file: File, destId: String, dir: String, name: String, destType: String,
        wifiOnly: Boolean, charging: Boolean, conflict: ConflictPolicy, deleteLocal: Boolean,
    ): Boolean {
        val local = destType == DestinationType.LOCAL_APP.name || destType == DestinationType.SAF_FOLDER.name
        val now = System.currentTimeMillis()
        val job = UploadJobEntity(
            fileId = fileId, filePath = file.absolutePath, destinationId = destId, remoteDir = dir, remoteName = name,
            total = file.length(), requiresNetwork = !local, wifiOnly = wifiOnly, chargingOnly = charging,
            conflict = conflict.name, deleteLocalAfter = deleteLocal, createdAt = now, updatedAt = now,
        )
        val id = jobs.insert(job)
        if (id <= 0) return false // identical job already exists: ignored, no duplicate
        scheduler.enqueue(job.copy(id = id))
        return true
    }

    suspend fun retry(id: Long) { jobs.resetForRetry(id, now()); jobs.get(id)?.let { scheduler.enqueue(it, replace = true) } }
    suspend fun pause(id: Long) { jobs.setStatus(id, JobStatus.PAUSED, now()); scheduler.cancel(id) }
    suspend fun resume(id: Long) { jobs.setStatus(id, JobStatus.QUEUED, now()); jobs.get(id)?.let { scheduler.enqueue(it, replace = true) } }
    suspend fun cancel(id: Long) { jobs.setStatus(id, JobStatus.CANCELED, now()); scheduler.cancel(id) }
    suspend fun remove(id: Long) { scheduler.cancel(id); jobs.delete(id) }
    suspend fun clearFinished() = jobs.deleteFinished()

    /** After a process restart: make sure every queued/in-flight job has its work scheduled (KEEP = no duplicates). */
    suspend fun reconcile() {
        for (j in jobs.active()) {
            if (j.status == JobStatus.UPLOADING) jobs.setStatus(j.id, JobStatus.QUEUED, now())
            scheduler.enqueue(j.copy(status = JobStatus.QUEUED))
        }
    }

    private fun now() = System.currentTimeMillis()
}
