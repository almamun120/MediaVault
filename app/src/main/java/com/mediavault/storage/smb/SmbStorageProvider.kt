package com.mediavault.storage.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.DiskShare
import com.mediavault.storage.api.*
import java.io.File
import java.io.IOException
import java.util.EnumSet

/** SMB2/SMB3 via smbj. Credentials are sent over the SMB session (encrypted when the server negotiates SMB3 encryption). */
class SmbStorageProvider(
    private val host: String,
    private val port: Int,
    private val share: String,
    private val username: String,
    private val password: String,
    private val domain: String?,
    private val rootDir: String,
) : StorageProvider {

    private fun full(path: String) = joinPath(rootDir, path).trim('/').replace('/', '\\')

    private fun map(e: Exception): StorageException = when {
        e is StorageException -> e
        e is SMBApiException -> when (e.status) {
            NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_WRONG_PASSWORD, NtStatus.STATUS_ACCOUNT_DISABLED ->
                failure(StorageErrorType.AUTH, "Login failed. Check the username, password and domain.")
            NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_SHARING_VIOLATION ->
                failure(StorageErrorType.PERMISSION, "Access denied by the server.")
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_BAD_NETWORK_NAME ->
                failure(StorageErrorType.NOT_FOUND, "File, folder or share not found.")
            NtStatus.STATUS_DISK_FULL -> failure(StorageErrorType.STORAGE_FULL, "The share has no space left.")
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> failure(StorageErrorType.PERMISSION, "A file with this name already exists.")
            else -> failure(StorageErrorType.SERVER, "SMB error ${e.status}", retryable = true)
        }
        e is SMBRuntimeException -> failure(StorageErrorType.NETWORK, "SMB connection problem: ${e.message}", retryable = true)
        else -> StorageException(e.toStorageError())
    }

    private fun <T> withShare(block: (DiskShare) -> T): T {
        try {
            SMBClient().use { client ->
                client.connect(host, port).use { conn ->
                    val auth = AuthenticationContext(username, password.toCharArray(), domain ?: "")
                    conn.authenticate(auth).use { session ->
                        (session.connectShare(share) as DiskShare).use { return block(it) }
                    }
                }
            }
        } catch (e: Exception) {
            throw map(e)
        }
    }

    private fun mkdirs(s: DiskShare, path: String) {
        var cur = ""
        for (seg in path.split('\\').filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) seg else "$cur\\$seg"
            if (!s.folderExists(cur)) s.mkdir(cur)
        }
    }

    override suspend fun testConnection(): StorageResult<Unit> = safeIo {
        withShare { s -> val p = full("/"); if (p.isNotEmpty() && !s.folderExists(p)) throw failure(StorageErrorType.NOT_FOUND, "Folder not found on the share") }
    }

    override suspend fun listFiles(path: String): StorageResult<List<RemoteEntry>> = safeIo {
        withShare { s ->
            s.list(full(path)).filter { it.fileName != "." && it.fileName != ".." }.map {
                RemoteEntry(
                    path = joinPath(path, it.fileName),
                    name = it.fileName,
                    isDirectory = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    sizeBytes = it.endOfFile,
                    modifiedAt = it.changeTime.toEpochMillis(),
                )
            }
        }
    }

    override suspend fun createDirectory(path: String): StorageResult<Unit> = safeIo { withShare { mkdirs(it, full(path)) } }

    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        withShare { s ->
            val dest = full(remotePath)
            mkdirs(s, dest.substringBeforeLast('\\', ""))
            if (s.fileExists(dest)) throw failure(StorageErrorType.PERMISSION, "A file with this name already exists on the share")
            var created = false
            try {
                // FILE_CREATE fails if the name exists, so an existing file can never be overwritten.
                s.openFile(
                    dest, EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, null,
                ).use { f ->
                    created = true
                    f.outputStream.use { out ->
                        source.inputStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, source.length())
                            }
                        }
                    }
                }
                val size = s.getFileInformation(dest).standardInformation.endOfFile
                if (size != source.length()) throw failure(StorageErrorType.SERVER, "Upload size mismatch ($size of ${source.length()} bytes)", retryable = true)
            } catch (e: Exception) {
                // No atomic rename here, so remove the partial file instead of leaving a truncated copy.
                if (created) runCatching { s.rm(dest) }
                throw e
            }
        }
    }

    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        withShare { s ->
            val src = full(remotePath)
            val total = s.getFileInformation(src).standardInformation.endOfFile
            val part = File(target.parentFile, ".${target.name}.part")
            try {
                s.openFile(src, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { f ->
                    f.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                }
                if (part.length() != total) throw IOException("Download was cut short")
                if (!part.renameTo(target)) throw IOException("Could not finalize ${target.name}")
            } finally {
                if (part.exists()) part.delete()
            }
        }
    }

    override suspend fun renameFile(fromPath: String, toPath: String): StorageResult<Unit> = safeIo {
        withShare { s ->
            val to = full(toPath)
            if (s.fileExists(to) || s.folderExists(to)) throw failure(StorageErrorType.PERMISSION, "Target already exists")
            mkdirs(s, to.substringBeforeLast('\\', ""))
            s.open(
                full(fromPath), EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { it.rename(to) }
        }
    }

    override suspend fun deleteFile(path: String): StorageResult<Unit> = safeIo {
        withShare { s ->
            val p = full(path)
            if (s.folderExists(p)) s.rmdir(p, false) else s.rm(p)
        }
    }

    override fun getAvailableCapabilities() = setOf(
        StorageCapability.LIST, StorageCapability.CREATE_DIRECTORY, StorageCapability.UPLOAD,
        StorageCapability.DOWNLOAD, StorageCapability.RENAME, StorageCapability.DELETE,
    )
}
