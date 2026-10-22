package com.mediavault.storage.local

import com.mediavault.storage.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** File-system destination rooted at [root] (app-specific storage). Streams in 64 KiB chunks. */
class LocalStorageProvider(private val root: File) : StorageProvider {

    private fun resolve(path: String): File? {
        val f = File(root, path.trimStart('/')).canonicalFile
        return if (f.path.startsWith(root.canonicalPath)) f else null // blocks ../ escapes
    }

    private inline fun <T> io(block: () -> T): StorageResult<T> = try {
        StorageResult.Success(block())
    } catch (e: IOException) {
        val full = e.message?.contains("ENOSPC", true) == true
        StorageResult.Failure(
            StorageError(
                if (full) StorageErrorType.STORAGE_FULL else StorageErrorType.UNKNOWN,
                e.message ?: "I/O error",
            ),
        )
    } catch (e: SecurityException) {
        StorageResult.Failure(StorageError(StorageErrorType.PERMISSION, e.message ?: "Permission denied"))
    } catch (e: IllegalStateException) {
        StorageResult.Failure(StorageError(StorageErrorType.UNKNOWN, e.message ?: "Invalid state"))
    }

    private val outside = StorageResult.Failure(
        StorageError(StorageErrorType.PERMISSION, "Path is outside the destination folder"),
    )

    override suspend fun testConnection(): StorageResult<Unit> = withContext(Dispatchers.IO) {
        io {
            check(root.exists() || root.mkdirs()) { "Cannot create ${root.path}" }
            check(root.canWrite()) { "Folder is not writable" }
        }
    }

    override suspend fun listFiles(path: String): StorageResult<List<RemoteEntry>> = withContext(Dispatchers.IO) {
        val dir = resolve(path) ?: return@withContext outside
        if (!dir.isDirectory) {
            return@withContext StorageResult.Failure(StorageError(StorageErrorType.NOT_FOUND, "No such folder"))
        }
        io {
            dir.listFiles().orEmpty().map {
                RemoteEntry(it.relativeTo(root).path, it.name, it.isDirectory, it.length(), it.lastModified())
            }
        }
    }

    override suspend fun createDirectory(path: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        val dir = resolve(path) ?: return@withContext outside
        io { check(dir.isDirectory || dir.mkdirs()) { "Cannot create folder" } }
    }

    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback): StorageResult<Unit> =
        withContext(Dispatchers.IO) {
            val dest = resolve(remotePath) ?: return@withContext outside
            io {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, ".${dest.name}.part")
                copy(source, tmp, onProgress)
                if (tmp.length() != source.length()) {
                    tmp.delete()
                    throw IOException("Size mismatch after copy")
                }
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    throw IOException("Could not finalize ${dest.name}")
                }
            }
        }

    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback): StorageResult<Unit> =
        withContext(Dispatchers.IO) {
            val src = resolve(remotePath) ?: return@withContext outside
            if (!src.isFile) {
                return@withContext StorageResult.Failure(StorageError(StorageErrorType.NOT_FOUND, "No such file"))
            }
            io { copy(src, target, onProgress) }
        }

    override suspend fun renameFile(fromPath: String, toPath: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        val a = resolve(fromPath)
        val b = resolve(toPath)
        if (a == null || b == null) return@withContext outside
        if (b.exists()) {
            return@withContext StorageResult.Failure(StorageError(StorageErrorType.UNKNOWN, "Target already exists"))
        }
        io { if (!a.renameTo(b)) throw IOException("Rename failed") }
    }

    override suspend fun deleteFile(path: String): StorageResult<Unit> = withContext(Dispatchers.IO) {
        val f = resolve(path) ?: return@withContext outside
        io { if (f.exists() && !f.delete()) throw IOException("Delete failed") }
    }

    override fun getAvailableCapabilities() = setOf(
        StorageCapability.LIST, StorageCapability.CREATE_DIRECTORY, StorageCapability.UPLOAD,
        StorageCapability.DOWNLOAD, StorageCapability.RENAME, StorageCapability.DELETE,
    )

    private fun copy(from: File, to: File, onProgress: ProgressCallback) {
        val total = from.length()
        var done = 0L
        from.inputStream().use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
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
}
