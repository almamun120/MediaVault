package com.mediavault.storage

import com.mediavault.storage.api.*
import java.io.File

/** In-memory provider for tests: no server needed. Set [failWith] to simulate any error type. */
class FakeStorageProvider(var failWith: StorageError? = null) : StorageProvider {
    val files = mutableMapOf<String, ByteArray>()
    var uploadCalls = 0

    private fun <T> guard(block: () -> T): StorageResult<T> =
        failWith?.let { StorageResult.Failure(it) } ?: StorageResult.Success(block())

    override suspend fun testConnection() = guard { }
    override suspend fun listFiles(path: String) = guard {
        files.keys.filter { it.startsWith(joinPath(path) .trimEnd('/') + "/") }.map { RemoteEntry(it, nameOf(it), false, files.getValue(it).size.toLong(), 0) }
    }
    override suspend fun createDirectory(path: String) = guard { }
    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback) = guard {
        uploadCalls++
        val p = joinPath(remotePath)
        check(p !in files) { "exists" }
        files[p] = source.readBytes()
        onProgress(source.length(), source.length())
    }
    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback) = guard {
        target.writeBytes(files[joinPath(remotePath)] ?: error("missing"))
    }
    override suspend fun renameFile(fromPath: String, toPath: String) = guard { files[joinPath(toPath)] = files.remove(joinPath(fromPath))!! ; Unit }
    override suspend fun deleteFile(path: String) = guard { files.remove(joinPath(path)); Unit }
    override fun getAvailableCapabilities() = StorageCapability.entries.toSet()
}
