package com.mediavault.storage.ftp

import com.mediavault.storage.api.*
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.IOException

enum class FtpSecurity { PLAIN, EXPLICIT_TLS, IMPLICIT_TLS }

/**
 * FTP / FTPS. PLAIN sends the password and all data unencrypted. The TLS modes use commons-net's default
 * trust manager, which validates the server certificate chain, and endpoint (hostname) checking stays
 * enabled. Certificate checks are never turned off.
 */
class FtpStorageProvider(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val security: FtpSecurity,
    private val rootDir: String,
) : StorageProvider {

    private fun full(path: String) = joinPath(rootDir, path)

    private fun fail(c: FTPClient, what: String): Nothing {
        val code = c.replyCode
        val text = c.replyString.orEmpty().trim()
        throw when (code) {
            530, 532, 331 -> failure(StorageErrorType.AUTH, "Login failed ($code)")
            550 -> if (text.contains("denied", true) || text.contains("permission", true))
                failure(StorageErrorType.PERMISSION, "Permission denied: $what")
            else failure(StorageErrorType.NOT_FOUND, "Not found: $what")
            552, 452 -> failure(StorageErrorType.STORAGE_FULL, "The server has no space left")
            421, 425, 426 -> failure(StorageErrorType.NETWORK, "Connection interrupted ($code)", retryable = true)
            else -> failure(StorageErrorType.UNKNOWN, "FTP error $code during $what: $text")
        }
    }

    private fun <T> withClient(block: (FTPClient) -> T): T {
        val c: FTPClient = when (security) {
            FtpSecurity.PLAIN -> FTPClient()
            FtpSecurity.EXPLICIT_TLS -> FTPSClient(false)
            FtpSecurity.IMPLICIT_TLS -> FTPSClient(true)
        }
        c.connectTimeout = 15_000
        try {
            c.connect(host, port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) fail(c, "connect")
            c.soTimeout = 30_000
            if (!c.login(username, password)) {
                throw failure(StorageErrorType.AUTH, "Login failed. Check the username and password.")
            }
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            if (c is FTPSClient) {
                c.execPBSZ(0)
                c.execPROT("P") // encrypt the data channel too
            }
            return block(c)
        } finally {
            try { if (c.isConnected) { c.logout(); c.disconnect() } } catch (_: IOException) {}
        }
    }

    private fun mkdirs(c: FTPClient, path: String) {
        var cur = ""
        for (seg in path.segments()) {
            cur += "/$seg"
            if (!c.changeWorkingDirectory(cur) && !c.makeDirectory(cur)) fail(c, "create folder $cur")
        }
        c.changeWorkingDirectory("/")
    }

    private fun remoteSize(c: FTPClient, path: String): Long? = c.listFiles(path).firstOrNull()?.size

    override suspend fun testConnection(): StorageResult<Unit> = safeIo {
        withClient { c -> if (full("/") != "/" && !c.changeWorkingDirectory(full("/"))) fail(c, "open folder") }
    }

    override suspend fun listFiles(path: String): StorageResult<List<RemoteEntry>> = safeIo {
        withClient { c ->
            val files = c.listFiles(full(path)) ?: fail(c, "list ${full(path)}")
            files.filter { it.name != "." && it.name != ".." }
                .map { RemoteEntry(joinPath(path, it.name), it.name, it.isDirectory, it.size, it.timestamp?.timeInMillis ?: 0L) }
        }
    }

    override suspend fun createDirectory(path: String): StorageResult<Unit> = safeIo {
        withClient { c -> mkdirs(c, full(path)) }
    }

    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        withClient { c ->
            val dest = full(remotePath)
            mkdirs(c, parentOf(dest))
            if (c.listFiles(dest).isNotEmpty()) throw failure(StorageErrorType.PERMISSION, "A file with this name already exists on the server")
            val tmp = "$dest.part"
            val ok = source.inputStream().use { c.storeFile(tmp, ProgressInputStream(it, source.length(), onProgress)) }
            if (!ok) fail(c, "upload")
            val size = remoteSize(c, tmp)
            if (size != null && size != source.length()) {
                c.deleteFile(tmp)
                throw failure(StorageErrorType.SERVER, "Upload size mismatch ($size of ${source.length()} bytes)", retryable = true)
            }
            if (!c.rename(tmp, dest)) fail(c, "finalize upload")
        }
    }

    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        withClient { c ->
            val src = full(remotePath)
            val total = remoteSize(c, src) ?: fail(c, "download")
            val part = File(target.parentFile, ".${target.name}.part")
            try {
                val ok = part.outputStream().use { c.retrieveFile(src, ProgressOutputStream(it, total, onProgress)) }
                if (!ok) fail(c, "download")
                if (part.length() != total) throw failure(StorageErrorType.NETWORK, "Download was cut short", retryable = true)
                if (!part.renameTo(target)) throw failure(StorageErrorType.UNKNOWN, "Could not finalize ${target.name}")
            } finally {
                if (part.exists()) part.delete()
            }
        }
    }

    override suspend fun renameFile(fromPath: String, toPath: String): StorageResult<Unit> = safeIo {
        withClient { c ->
            mkdirs(c, parentOf(full(toPath)))
            if (!c.rename(full(fromPath), full(toPath))) fail(c, "rename")
        }
    }

    override suspend fun deleteFile(path: String): StorageResult<Unit> = safeIo {
        withClient { c ->
            val p = full(path)
            if (!c.deleteFile(p) && !c.removeDirectory(p)) fail(c, "delete")
        }
    }

    override fun getAvailableCapabilities() = setOf(
        StorageCapability.LIST, StorageCapability.CREATE_DIRECTORY, StorageCapability.UPLOAD,
        StorageCapability.DOWNLOAD, StorageCapability.RENAME, StorageCapability.DELETE,
    )
}
