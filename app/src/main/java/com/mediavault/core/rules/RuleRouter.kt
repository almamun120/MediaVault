package com.mediavault.core.rules

import com.mediavault.core.database.ConflictPolicy
import com.mediavault.core.database.RuleEntity
import com.mediavault.core.database.RuleMedia
import com.mediavault.core.database.UploadTiming
import com.mediavault.core.model.MediaKind
import com.mediavault.core.util.FilenameTemplate
import java.time.ZonedDateTime

data class PlannedUpload(
    val ruleId: String,
    val destinationId: String,
    val remoteDir: String,
    val remoteName: String,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val conflict: ConflictPolicy,
    val deleteLocalAfter: Boolean,
)

/** Pure routing logic (no Android types) so it can be unit-tested. */
object RuleRouter {
    fun plan(
        rules: List<RuleEntity>,
        kind: MediaKind,
        originalName: String,
        now: ZonedDateTime,
        existingDestinationIds: Set<String>,
    ): List<PlannedUpload> {
        val ext = originalName.substringAfterLast('.', "")
        return rules
            .filter { it.enabled && it.timing == UploadTiming.IMMEDIATE.name && (it.media == RuleMedia.ALL.name || it.media == kind.name) }
            .flatMap { r ->
                val dir = "/" + FilenameTemplate.renderPath(r.folderTemplate, now)
                val name = if (r.filenameTemplate.isBlank()) originalName
                else FilenameTemplate.render(r.filenameTemplate, now) + if (ext.isEmpty()) "" else ".$ext"
                (listOf(r.primaryDestinationId) + r.backups()).distinct()
                    .filter { it in existingDestinationIds }
                    .map {
                        PlannedUpload(
                            r.id, it, dir.trimEnd('/').ifEmpty { "/" }, name, r.wifiOnly, r.chargingOnly,
                            ConflictPolicy.valueOf(r.conflict), r.deleteLocalAfterUpload,
                        )
                    }
            }
            // Two rules pointing at the same destination + folder + name must not upload twice.
            .distinctBy { Triple(it.destinationId, it.remoteDir, it.remoteName) }
    }
}
