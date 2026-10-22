package com.mediavault.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset
import java.time.ZonedDateTime

class FilenameTemplateTest {
    private val t = ZonedDateTime.of(2026, 10, 11, 14, 5, 9, 0, ZoneOffset.UTC)

    @Test fun rendersDateTokens() =
        assertEquals("IMG_20261011_140509", FilenameTemplate.render("IMG_{yyyyMMdd_HHmmss}", t))

    @Test fun sanitizesIllegalCharacters() =
        assertEquals("a_b_c", FilenameTemplate.sanitize("a/b:c"))

    @Test fun invalidPatternIsLeftLiteral() =
        assertTrue(FilenameTemplate.render("X{bbbbbbbbbb}", t).startsWith("X"))

    @Test fun uniqueFileNeverOverwrites() {
        val dir = Files.createTempDirectory("mv").toFile()
        val first = FilenameTemplate.uniqueFile(dir, "IMG", "jpg").also { it.writeText("x") }
        val second = FilenameTemplate.uniqueFile(dir, "IMG", "jpg")
        assertEquals("IMG.jpg", first.name)
        assertEquals("IMG_1.jpg", second.name)
    }
}

class FilenameTemplatePathTest {
    private val t = java.time.ZonedDateTime.of(2026, 10, 18, 9, 5, 0, 0, java.time.ZoneOffset.UTC)
    @org.junit.Test fun rendersEachSegment() = org.junit.Assert.assertEquals("Photos/2026/10", FilenameTemplate.renderPath("Photos/{yyyy}/{MM}", t))
    @org.junit.Test fun dropsTraversalSegments() = org.junit.Assert.assertEquals("a/b", FilenameTemplate.renderPath("a/../b/./", t))
}
