package com.mediavault.transfer

import com.mediavault.core.database.ConflictPolicy
import com.mediavault.core.database.RuleEntity
import com.mediavault.core.database.RuleMedia
import com.mediavault.core.database.UploadTiming
import com.mediavault.core.model.MediaKind
import com.mediavault.core.rules.RuleRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RuleRouterTest {
    private val now = ZonedDateTime.of(2026, 10, 18, 9, 30, 0, 0, ZoneOffset.UTC)
    private val all = setOf("a", "b", "c")
    private fun rule(media: RuleMedia = RuleMedia.PHOTO, primary: String = "a", backups: String = "", folder: String = "Photos/{yyyy}/{MM}",
                     name: String = "", timing: UploadTiming = UploadTiming.IMMEDIATE, enabled: Boolean = true, id: String = "r") =
        RuleEntity(id = id, media = media.name, primaryDestinationId = primary, backupDestinationIds = backups, folderTemplate = folder,
            filenameTemplate = name, timing = timing.name, enabled = enabled, createdAt = 0)

    private fun plan(vararg r: RuleEntity, kind: MediaKind = MediaKind.PHOTO) = RuleRouter.plan(r.toList(), kind, "IMG_1.jpg", now, all)

    @Test fun rendersFolderTemplate() = assertEquals("/Photos/2026/10", plan(rule()).single().remoteDir)
    @Test fun primaryPlusBackups() = assertEquals(listOf("a", "b", "c"), plan(rule(backups = "b,c")).map { it.destinationId })
    @Test fun otherMediaIsIgnored() = assertTrue(plan(rule(media = RuleMedia.VIDEO)).isEmpty())
    @Test fun allMediaMatchesEverything() = assertEquals(1, plan(rule(media = RuleMedia.ALL), kind = MediaKind.AUDIO).size)
    @Test fun manualAndDisabledRulesDoNotUpload() { assertTrue(plan(rule(timing = UploadTiming.MANUAL)).isEmpty()); assertTrue(plan(rule(enabled = false)).isEmpty()) }
    @Test fun unknownDestinationsAreDropped() = assertEquals(listOf("a"), plan(rule(backups = "gone")).map { it.destinationId })
    @Test fun sameDestinationFromTwoRulesUploadsOnce() = assertEquals(1, plan(rule(id = "1"), rule(media = RuleMedia.ALL, id = "2")).size)
    @Test fun remoteNameTemplateKeepsExtension() = assertEquals("Trip_20261018.jpg", plan(rule(name = "Trip_{yyyyMMdd}")).single().remoteName)
    @Test fun blankNameKeepsLocalName() = assertEquals("IMG_1.jpg", plan(rule()).single().remoteName)
    @Test fun carriesConflictPolicy() = assertEquals(ConflictPolicy.RENAME, plan(rule()).single().conflict)
    @Test fun emptyFolderMeansRoot() = assertEquals("/", plan(rule(folder = "")).single().remoteDir)
}
