package com.mediavault.storage.nfs

import com.mediavault.storage.api.*
import java.io.File

/**
 * NFS cannot be mounted by an ordinary (non-root) Android app, and a pure-Java NFS client would be an
 * unvetted dependency. This provider exists only so the destination type is visible and returns a clear,
 * honest error. Recommended alternative: expose the same export through SMB or WebDAV (e.g. Samba or an
 * nginx/Apache WebDAV gateway on the NFS server) and add that as a destination.
 */
class NfsStorageProvider : StorageProvider {
    private val err = StorageResult.Failure(
        StorageError(
            StorageErrorType.UNSUPPORTED,
            "NFS is not supported on standard Android without root. Share the folder over SMB or WebDAV and add that instead.",
        ),
    )
    override suspend fun testConnection() = err
    override suspend fun listFiles(path: String) = err
    override suspend fun createDirectory(path: String) = err
    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback) = err
    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback) = err
    override suspend fun renameFile(fromPath: String, toPath: String) = err
    override suspend fun deleteFile(path: String) = err
    override fun getAvailableCapabilities(): Set<StorageCapability> = emptySet()
}
