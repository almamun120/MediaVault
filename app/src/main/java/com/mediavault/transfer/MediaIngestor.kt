package com.mediavault.transfer

import com.mediavault.core.database.ManagedFileDao
import com.mediavault.core.database.ManagedFileEntity
import com.mediavault.core.model.MediaKind
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Single entry point for "a new file was captured": index it, then apply upload rules. */
@Singleton
class MediaIngestor @Inject constructor(
    private val files: ManagedFileDao,
    private val queue: QueueRepository,
) {
    suspend fun ingest(file: File, kind: MediaKind, interrupted: Boolean = false) {
        val id = files.insert(
            ManagedFileEntity(
                path = file.absolutePath, displayName = file.name, kind = kind.name, sizeBytes = file.length(),
                createdAt = System.currentTimeMillis(), interrupted = interrupted,
            ),
        )
        if (!interrupted) queue.enqueueFromRules(id, file, kind)
    }
}
