package com.mediavault.storage

import com.mediavault.storage.api.StorageErrorType
import com.mediavault.storage.api.StorageResult
import com.mediavault.storage.local.LocalStorageProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalStorageProviderTest {
    private val root = Files.createTempDirectory("lsp").toFile()
    private val provider = LocalStorageProvider(root)

    @Test fun uploadCopiesFileAndLeavesNoPartFile() = runBlocking {
        val src = Files.createTempFile("src", ".bin").toFile().apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        val r = provider.uploadFile(src, "Photos/2026/a.bin")
        assertTrue(r is StorageResult.Success)
        assertEquals(src.length(), root.resolve("Photos/2026/a.bin").length())
        assertFalse(root.resolve("Photos/2026").listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun pathOutsideRootIsRejected() = runBlocking {
        val src = Files.createTempFile("src", ".bin").toFile().apply { writeText("x") }
        val r = provider.uploadFile(src, "../evil.bin")
        assertTrue(r is StorageResult.Failure)
        assertEquals(StorageErrorType.PERMISSION, (r as StorageResult.Failure).error.type)
    }

    @Test fun renameRefusesToOverwrite() = runBlocking {
        root.resolve("a.txt").writeText("a"); root.resolve("b.txt").writeText("b")
        val r = provider.renameFile("a.txt", "b.txt")
        assertTrue(r is StorageResult.Failure)
        assertEquals("b", root.resolve("b.txt").readText())
    }

    @Test fun listMissingFolderIsNotFound() = runBlocking {
        val r = provider.listFiles("nope")
        assertEquals(StorageErrorType.NOT_FOUND, (r as StorageResult.Failure).error.type)
    }
}
