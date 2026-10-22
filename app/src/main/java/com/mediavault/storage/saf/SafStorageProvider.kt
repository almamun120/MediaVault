package com.mediavault.storage.saf

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.mediavault.storage.api.*
import java.io.File
import java.io.IOException

/**
 * A folder the user picked through the Storage Access Framework (internal storage or a removable SD card).
 * Only that folder's tree is reachable; Android's scoped-storage rules are respected, nothing is bypassed.
 * If the volume is ejected the root resolves to null and every call fails with a retryable error.
 */
class SafStorageProvider(private val context: Context, private val treeUri: Uri) : StorageProvider {

    private fun root(): DocumentFile {
        val r = DocumentFile.fromTreeUri(context, treeUri)
        if (r == null || !r.exists()) {
            throw failure(StorageErrorType.NOT_FOUND, "The folder is not available (SD card removed, or access was revoked).", retryable = true)
        }
        return r
    }

    private fun resolve(path: String, create: Boolean): DocumentFile? {
        var cur = root()
        for (seg in path.segments()) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory }
                ?: if (create) cur.createDirectory(seg) ?: throw failure(StorageErrorType.PERMISSION, "Cannot create folder $seg")
                else return null
        }
        return cur
    }

    override suspend fun testConnection(): StorageResult<Unit> = safeIo {
        val r = root()
        if (!r.canWrite()) throw failure(StorageErrorType.PERMISSION, "The folder is read-only or permission was revoked.")
    }

    override suspend fun listFiles(path: String): StorageResult<List<RemoteEntry>> = safeIo {
        val dir = resolve(path, false) ?: throw failure(StorageErrorType.NOT_FOUND, "No such folder")
        dir.listFiles().map { RemoteEntry(joinPath(path, it.name.orEmpty()), it.name.orEmpty(), it.isDirectory, it.length(), it.lastModified()) }
    }

    override suspend fun createDirectory(path: String): StorageResult<Unit> = safeIo { resolve(path, true); Unit }

    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        val dir = resolve(parentOf(remotePath), true)!!
        val name = nameOf(remotePath)
        if (dir.findFile(name) != null) throw failure(StorageErrorType.PERMISSION, "A file with this name already exists")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        // SAF providers disagree about temp names, so write the final name directly and delete it on failure.
        val doc = dir.createFile(mime, name) ?: throw failure(StorageErrorType.PERMISSION, "Cannot create $name")
        try {
            val out = context.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("Cannot open $name for writing")
            out.use { o ->
                source.inputStream().use { i ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        onProgress(done, source.length())
                    }
                }
            }
            if (doc.length() != source.length()) throw failure(StorageErrorType.STORAGE_FULL, "Copy is incomplete (${doc.length()} of ${source.length()} bytes) — storage may be full", retryable = false)
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
    }

    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        val doc = resolve(parentOf(remotePath), false)?.findFile(nameOf(remotePath)) ?: throw failure(StorageErrorType.NOT_FOUND, "No such file")
        val total = doc.length()
        val part = File(target.parentFile, ".${target.name}.part")
        try {
            context.contentResolver.openInputStream(doc.uri)!!.use { i ->
                part.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("Could not finalize ${target.name}")
        } finally {
            if (part.exists()) part.delete()
        }
    }

    override suspend fun renameFile(fromPath: String, toPath: String): StorageResult<Unit> = safeIo {
        if (parentOf(fromPath) != parentOf(toPath)) throw failure(StorageErrorType.UNSUPPORTED, "Moving between folders is not supported on device storage; only renaming in place.")
        val doc = resolve(parentOf(fromPath), false)?.findFile(nameOf(fromPath)) ?: throw failure(StorageErrorType.NOT_FOUND, "No such file")
        if (!doc.renameTo(nameOf(toPath))) throw failure(StorageErrorType.PERMISSION, "Rename failed")
    }

    override suspend fun deleteFile(path: String): StorageResult<Unit> = safeIo {
        val doc = resolve(parentOf(path), false)?.findFile(nameOf(path)) ?: return@safeIo
        if (!doc.delete()) throw failure(StorageErrorType.PERMISSION, "Delete failed")
    }

    override fun getAvailableCapabilities() = setOf(
        StorageCapability.LIST, StorageCapability.CREATE_DIRECTORY, StorageCapability.UPLOAD,
        StorageCapability.DOWNLOAD, StorageCapability.RENAME, StorageCapability.DELETE,
    )

    companion object {
        /** Removable volumes (SD cards, USB) currently mounted, for the "SD card" shortcut. */
        fun removableVolumes(context: Context) =
            context.getSystemService(StorageManager::class.java).storageVolumes.filter { it.isRemovable && it.state == "mounted" }
    }
}
