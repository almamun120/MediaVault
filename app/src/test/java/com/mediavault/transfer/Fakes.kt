package com.mediavault.transfer

import com.mediavault.core.database.DestinationDao
import com.mediavault.core.database.DestinationEntity
import com.mediavault.core.database.JobStatus
import com.mediavault.core.database.RuleDao
import com.mediavault.core.database.RuleEntity
import com.mediavault.core.database.UploadJobDao
import com.mediavault.core.database.UploadJobEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUploadJobDao : UploadJobDao {
    val rows = mutableListOf<UploadJobEntity>()
    private val flow = MutableStateFlow<List<UploadJobEntity>>(emptyList())
    private var nextId = 1L
    private fun publish() { flow.value = rows.toList() }
    private fun mutate(id: Long, f: (UploadJobEntity) -> UploadJobEntity) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = f(rows[i]); publish()
    }

    override suspend fun insert(job: UploadJobEntity): Long {
        // mirrors the unique index (filePath, destinationId, remoteDir) + OnConflictStrategy.IGNORE
        if (rows.any { it.filePath == job.filePath && it.destinationId == job.destinationId && it.remoteDir == job.remoteDir }) return -1
        rows += job.copy(id = nextId++); publish()
        return rows.last().id
    }
    override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<UploadJobEntity>> = flow
    override suspend fun forFile(path: String) = rows.filter { it.filePath == path }
    override suspend fun active() = rows.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.UPLOADING }
    override suspend fun markUploading(id: Long, attempts: Int, now: Long) = mutate(id) { it.copy(status = JobStatus.UPLOADING, attempts = attempts, errorType = null, errorMessage = null) }
    override suspend fun setProgress(id: Long, done: Long, total: Long, now: Long) = mutate(id) { if (it.status == JobStatus.UPLOADING) it.copy(transferred = done, total = total) else it }
    override suspend fun markDone(id: Long, name: String, note: String?, now: Long) = mutate(id) { it.copy(status = JobStatus.DONE, transferred = it.total, errorMessage = note, remoteName = name) }
    override suspend fun markError(id: Long, status: String, type: String, message: String, now: Long) = mutate(id) { it.copy(status = status, errorType = type, errorMessage = message) }
    override suspend fun setStatus(id: Long, status: String, now: Long) = mutate(id) { it.copy(status = status) }
    override suspend fun requeueIfUploading(id: Long, now: Long) = mutate(id) { if (it.status == JobStatus.UPLOADING) it.copy(status = JobStatus.QUEUED) else it }
    override suspend fun resetForRetry(id: Long, now: Long) = mutate(id) { it.copy(status = JobStatus.QUEUED, attempts = 0, transferred = 0, errorType = null, errorMessage = null) }
    override suspend fun deleteFinished() { rows.removeAll { it.status == JobStatus.DONE || it.status == JobStatus.CANCELED }; publish() }
    override suspend fun delete(id: Long) { rows.removeAll { it.id == id }; publish() }
    override suspend fun deleteUnfinishedFor(destId: String) { rows.removeAll { it.destinationId == destId && it.status != JobStatus.DONE }; publish() }
}

class FakeRuleDao(var rules: List<RuleEntity> = emptyList()) : RuleDao {
    override fun observeAll(): Flow<List<RuleEntity>> = MutableStateFlow(rules)
    override suspend fun getEnabled() = rules.filter { it.enabled }
    override suspend fun upsert(rule: RuleEntity) { rules = rules.filter { it.id != rule.id } + rule }
    override suspend fun delete(id: String) { rules = rules.filter { it.id != id } }
    override suspend fun deleteOrphansFor(destId: String) {}
}

class FakeDestinationDao(var items: List<DestinationEntity> = emptyList()) : DestinationDao {
    override fun observeAll(): Flow<List<DestinationEntity>> = MutableStateFlow(items)
    override suspend fun getAll() = items
    override suspend fun get(id: String) = items.firstOrNull { it.id == id }
    override suspend fun upsert(d: DestinationEntity) { items = items.filter { it.id != d.id } + d }
    override suspend fun delete(id: String) { items = items.filter { it.id != id } }
}

class RecordingScheduler : UploadScheduler {
    data class Call(val jobId: Long, val replace: Boolean)
    val enqueued = mutableListOf<Call>()
    val canceled = mutableListOf<Long>()
    override fun enqueue(job: UploadJobEntity, replace: Boolean) { enqueued += Call(job.id, replace) }
    override fun cancel(jobId: Long) { canceled += jobId }
}
