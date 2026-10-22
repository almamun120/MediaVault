package com.mediavault.storage.api

import java.io.File

enum class StorageCapability { LIST, CREATE_DIRECTORY, UPLOAD, DOWNLOAD, RENAME, DELETE, RESUME }

enum class StorageErrorType { AUTH, PERMISSION, STORAGE_FULL, NETWORK, SERVER, NOT_FOUND, UNSUPPORTED, UNKNOWN }

data class StorageError(val type: StorageErrorType, val message: String, val retryable: Boolean = false)

sealed interface StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>
    data class Failure(val error: StorageError) : StorageResult<Nothing>
}

data class RemoteEntry(val path: String, val name: String, val isDirectory: Boolean, val sizeBytes: Long, val modifiedAt: Long)

typealias ProgressCallback = (transferred: Long, total: Long) -> Unit

/** Every destination (local, SMB, SFTP, ...) implements this; capture code never depends on a protocol. */
interface StorageProvider {
    suspend fun testConnection(): StorageResult<Unit>
    suspend fun listFiles(path: String): StorageResult<List<RemoteEntry>>
    suspend fun createDirectory(path: String): StorageResult<Unit>
    suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback = { _, _ -> }): StorageResult<Unit>
    suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback = { _, _ -> }): StorageResult<Unit>
    suspend fun renameFile(fromPath: String, toPath: String): StorageResult<Unit>
    suspend fun deleteFile(path: String): StorageResult<Unit>
    fun getAvailableCapabilities(): Set<StorageCapability>
}
