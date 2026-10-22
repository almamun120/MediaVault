package com.mediavault.storage.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import com.mediavault.storage.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * SFTP with strict host-key checking. A connection is refused unless the server's host key matches the
 * fingerprint the user explicitly trusted ([trustedFingerprint], stored in the destination). Credentials
 * are only sent after that check passes.
 */
class SftpStorageProvider(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String?,
    private val privateKeyPem: String?,
    private val passphrase: String?,
    private val trustedFingerprint: String?,
    private val rootDir: String,
) : StorageProvider {

    companion object {
        fun fingerprint(key: ByteArray): String =
            "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

        /**
         * Reads the server's host-key fingerprint WITHOUT authenticating (no credentials are sent): the
         * repository records the key and then rejects it, so the handshake aborts before login.
         */
        suspend fun fetchHostFingerprint(host: String, port: Int): StorageResult<String> = withContext(Dispatchers.IO) {
            var captured: String? = null
            val jsch = JSch()
            jsch.hostKeyRepository = FingerprintRepository(null) { captured = it }
            val s = jsch.getSession("probe", host, port)
            s.setConfig("StrictHostKeyChecking", "yes")
            try {
                s.connect(10_000)
                s.disconnect()
            } catch (e: JSchException) {
                // expected: the probe aborts on purpose
                if (captured == null) return@withContext StorageResult.Failure(mapJsch(e))
            }
            captured?.let { StorageResult.Success(it) }
                ?: StorageResult.Failure(StorageError(StorageErrorType.NETWORK, "Could not read the server host key", true))
        }

        private fun mapJsch(e: JSchException): StorageError {
            val msg = e.message.orEmpty()
            val cause = e.cause
            return when {
                "Auth fail" in msg || "Auth cancel" in msg -> StorageError(StorageErrorType.AUTH, "Authentication failed. Check the username, password or key.")
                "HostKey" in msg || "UnknownHostKey" in msg || "reject" in msg.lowercase() ->
                    StorageError(StorageErrorType.PERMISSION, "The server's host key does not match the trusted fingerprint. This may be a man-in-the-middle attack, or the server was reinstalled.")
                cause is UnknownHostException -> StorageError(StorageErrorType.NETWORK, "Server not found", true)
                cause is ConnectException || cause is SocketTimeoutException || "timeout" in msg.lowercase() || "Connection" in msg ->
                    StorageError(StorageErrorType.NETWORK, "Cannot reach the server: $msg", true)
                else -> StorageError(StorageErrorType.SERVER, "SSH error: $msg", true)
            }
        }

        private fun mapSftp(e: SftpException): StorageError = when {
            e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> StorageError(StorageErrorType.NOT_FOUND, "No such file or folder")
            e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> StorageError(StorageErrorType.PERMISSION, "Permission denied on the server")
            e.message.orEmpty().contains("space", true) -> StorageError(StorageErrorType.STORAGE_FULL, "The server has no space left")
            else -> StorageError(StorageErrorType.UNKNOWN, e.message ?: "SFTP error")
        }
    }

    /** Accepts a key only if its SHA-256 fingerprint equals the trusted one; optionally reports what it saw. */
    private class FingerprintRepository(private val trusted: String?, private val onSeen: ((String) -> Unit)?) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            val fp = fingerprint(key)
            onSeen?.invoke(fp)
            return when {
                trusted == null -> HostKeyRepository.NOT_INCLUDED
                trusted == fp -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "mediavault"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private fun full(path: String) = joinPath(rootDir, path)

    private fun <T> sftp(block: (ChannelSftp) -> T): T {
        if (trustedFingerprint.isNullOrBlank()) {
            throw failure(StorageErrorType.PERMISSION, "The server host key has not been verified yet. Open the destination and verify the fingerprint first.")
        }
        var session: Session? = null
        var channel: ChannelSftp? = null
        try {
            val jsch = JSch()
            jsch.hostKeyRepository = FingerprintRepository(trustedFingerprint, null)
            if (privateKeyPem != null) jsch.addIdentity("key", privateKeyPem.toByteArray(), null, passphrase?.toByteArray())
            session = jsch.getSession(username, host, port)
            if (password != null) session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", if (privateKeyPem != null) "publickey" else "password,keyboard-interactive")
            session.timeout = 30_000
            session.connect(15_000)
            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15_000)
            return block(channel)
        } catch (e: JSchException) {
            throw StorageException(mapJsch(e))
        } catch (e: SftpException) {
            throw StorageException(mapSftp(e))
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
    }

    private fun exists(ch: ChannelSftp, path: String) = try { ch.stat(path); true } catch (e: SftpException) { false }

    private fun mkdirs(ch: ChannelSftp, path: String) {
        var cur = ""
        for (seg in path.segments()) {
            cur += "/$seg"
            if (!exists(ch, cur)) ch.mkdir(cur)
        }
    }

    private fun monitor(total: Long, onProgress: ProgressCallback) = object : SftpProgressMonitor {
        var done = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
        override fun count(count: Long): Boolean { done += count; onProgress(done, total); return true }
        override fun end() {}
    }

    override suspend fun testConnection(): StorageResult<Unit> = safeIo {
        sftp { ch -> ch.stat(full("/")); Unit }
    }

    override suspend fun listFiles(path: String): StorageResult<List<RemoteEntry>> = safeIo {
        sftp { ch ->
            ch.ls(full(path)).filterIsInstance<ChannelSftp.LsEntry>()
                .filter { it.filename != "." && it.filename != ".." }
                .map { RemoteEntry(joinPath(path, it.filename), it.filename, it.attrs.isDir, it.attrs.size, it.attrs.mTime * 1000L) }
        }
    }

    override suspend fun createDirectory(path: String): StorageResult<Unit> = safeIo {
        sftp { ch -> mkdirs(ch, full(path)) }
    }

    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        sftp { ch ->
            val dest = full(remotePath)
            mkdirs(ch, parentOf(dest))
            if (exists(ch, dest)) throw failure(StorageErrorType.PERMISSION, "A file with this name already exists on the server")
            val tmp = "$dest.part"
            try {
                source.inputStream().use { ch.put(it, tmp, monitor(source.length(), onProgress), ChannelSftp.OVERWRITE) }
                val size = ch.stat(tmp).size
                if (size != source.length()) throw failure(StorageErrorType.SERVER, "Upload size mismatch ($size of ${source.length()} bytes)", retryable = true)
                ch.rename(tmp, dest)
            } catch (e: Exception) {
                runCatching { ch.rm(tmp) }
                throw e
            }
        }
    }

    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        sftp { ch ->
            val src = full(remotePath)
            val total = ch.stat(src).size
            val part = File(target.parentFile, ".${target.name}.part")
            try {
                part.outputStream().use { ch.get(src, it, monitor(total, onProgress)) }
                if (part.length() != total) throw failure(StorageErrorType.NETWORK, "Download was cut short", retryable = true)
                if (!part.renameTo(target)) throw failure(StorageErrorType.UNKNOWN, "Could not finalize ${target.name}")
            } finally {
                if (part.exists()) part.delete()
            }
        }
    }

    override suspend fun renameFile(fromPath: String, toPath: String): StorageResult<Unit> = safeIo {
        sftp { ch ->
            if (exists(ch, full(toPath))) throw failure(StorageErrorType.PERMISSION, "Target already exists")
            mkdirs(ch, parentOf(full(toPath)))
            ch.rename(full(fromPath), full(toPath))
        }
    }

    override suspend fun deleteFile(path: String): StorageResult<Unit> = safeIo {
        sftp { ch ->
            val p = full(path)
            if (ch.stat(p).isDir) ch.rmdir(p) else ch.rm(p)
        }
    }

    override fun getAvailableCapabilities() = setOf(
        StorageCapability.LIST, StorageCapability.CREATE_DIRECTORY, StorageCapability.UPLOAD,
        StorageCapability.DOWNLOAD, StorageCapability.RENAME, StorageCapability.DELETE,
    )
}
