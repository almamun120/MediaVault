package com.mediavault.transfer

import com.mediavault.core.database.DestinationEntity
import com.mediavault.core.database.DestinationType
import com.mediavault.core.database.JobStatus
import com.mediavault.core.database.RuleEntity
import com.mediavault.core.database.RuleMedia
import com.mediavault.core.model.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QueueRepositoryTest {
    private lateinit var jobs: FakeUploadJobDao
    private lateinit var scheduler: RecordingScheduler
    private lateinit var queue: QueueRepository
    private lateinit var file: File

    private fun dest(id: String, type: DestinationType) = DestinationEntity(id = id, name = id, type = type.name, createdAt = 0)
    private fun rule(media: RuleMedia, primary: String, backups: String = "", folder: String = "Photos") =
        RuleEntity(id = "r-$primary-$media", media = media.name, primaryDestinationId = primary, backupDestinationIds = backups, folderTemplate = folder, createdAt = 0)

    @Before fun setUp() {
        jobs = FakeUploadJobDao()
        scheduler = RecordingScheduler()
        file = Files.createTempFile("IMG_", ".jpg").toFile().apply { writeText("hello") }
        queue = QueueRepository(
            jobs,
            FakeRuleDao(listOf(rule(RuleMedia.PHOTO, "sftp", backups = "sd", folder = "Photos/Test"))),
            FakeDestinationDao(listOf(dest("sftp", DestinationType.SFTP), dest("sd", DestinationType.SAF_FOLDER))),
            scheduler,
        )
    }

    @Test fun ruleCreatesOneJobPerDestinationAndSchedulesEach() = runBlocking {
        queue.enqueueFromRules(1, file, MediaKind.PHOTO)
        assertEquals(2, jobs.rows.size)
        assertEquals(setOf("sftp", "sd"), jobs.rows.map { it.destinationId }.toSet())
        assertTrue(jobs.rows.all { it.remoteDir == "/Photos/Test" && it.status == JobStatus.QUEUED })
        assertEquals(2, scheduler.enqueued.size)
    }

    @Test fun localDestinationsDoNotRequireNetwork() = runBlocking {
        queue.enqueueFromRules(1, file, MediaKind.PHOTO)
        assertTrue(jobs.rows.first { it.destinationId == "sftp" }.requiresNetwork)
        assertFalse(jobs.rows.first { it.destinationId == "sd" }.requiresNetwork)
    }

    @Test fun ruleForOtherMediaCreatesNothing() = runBlocking {
        queue.enqueueFromRules(1, file, MediaKind.VIDEO)
        assertTrue(jobs.rows.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test fun enqueueingTwiceNeverCreatesDuplicates() = runBlocking {
        queue.enqueueFromRules(1, file, MediaKind.PHOTO)
        queue.enqueueFromRules(1, file, MediaKind.PHOTO)
        assertEquals(2, jobs.rows.size)
        assertEquals(2, scheduler.enqueued.size)
    }

    @Test fun manualUploadIsIdempotent() = runBlocking {
        assertTrue(queue.enqueueManual(1, file, MediaKind.PHOTO, "sftp"))
        assertFalse(queue.enqueueManual(1, file, MediaKind.PHOTO, "sftp"))
        assertFalse(queue.enqueueManual(1, file, MediaKind.PHOTO, "missing"))
        assertEquals(1, jobs.rows.size)
    }

    @Test fun retryResetsFailedJobAndReplacesWork() = runBlocking {
        queue.enqueueManual(1, file, MediaKind.PHOTO, "sftp")
        val id = jobs.rows.single().id
        jobs.markError(id, JobStatus.FAILED, "AUTH", "bad password", 0)
        queue.retry(id)
        val j = jobs.get(id)!!
        assertEquals(JobStatus.QUEUED, j.status); assertEquals(0, j.attempts); assertEquals(null, j.errorMessage)
        assertTrue(scheduler.enqueued.last().replace)
    }

    @Test fun pauseResumeCancelTransitions() = runBlocking {
        queue.enqueueManual(1, file, MediaKind.PHOTO, "sftp")
        val id = jobs.rows.single().id
        queue.pause(id)
        assertEquals(JobStatus.PAUSED, jobs.get(id)!!.status); assertTrue(id in scheduler.canceled)
        queue.resume(id)
        assertEquals(JobStatus.QUEUED, jobs.get(id)!!.status); assertTrue(scheduler.enqueued.last().replace)
        queue.cancel(id)
        assertEquals(JobStatus.CANCELED, jobs.get(id)!!.status)
    }

    @Test fun stoppedUploadIsRequeuedButPauseIsNotOverridden() = runBlocking {
        queue.enqueueManual(1, file, MediaKind.PHOTO, "sftp")
        val id = jobs.rows.single().id
        jobs.markUploading(id, 1, 0)
        jobs.requeueIfUploading(id, 0)
        assertEquals(JobStatus.QUEUED, jobs.get(id)!!.status)
        queue.pause(id)
        jobs.requeueIfUploading(id, 0)
        assertEquals(JobStatus.PAUSED, jobs.get(id)!!.status)
    }

    @Test fun clearFinishedKeepsActiveAndFailed() = runBlocking {
        queue.enqueueManual(1, file, MediaKind.PHOTO, "sftp")
        queue.enqueueManual(1, file, MediaKind.PHOTO, "sd")
        val (a, b) = jobs.rows.map { it.id }
        jobs.markDone(a, "x", null, 0)
        jobs.markError(b, JobStatus.FAILED, "NETWORK", "x", 0)
        queue.clearFinished()
        assertEquals(listOf(b), jobs.rows.map { it.id })
    }

    @Test fun reconcileReschedulesInterruptedJobs() = runBlocking {
        queue.enqueueManual(1, file, MediaKind.PHOTO, "sftp")
        val id = jobs.rows.single().id
        jobs.markUploading(id, 1, 0)
        scheduler.enqueued.clear()
        queue.reconcile()
        assertEquals(JobStatus.QUEUED, jobs.get(id)!!.status)
        assertEquals(listOf(RecordingScheduler.Call(id, false)), scheduler.enqueued)
    }
}
