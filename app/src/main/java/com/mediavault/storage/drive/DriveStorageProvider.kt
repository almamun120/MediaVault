package com.mediavault.storage.drive

import com.mediavault.storage.api.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Google Drive v3 REST over HTTPS with the official endpoints. Files live under a "[rootFolderName]" folder
 * in My Drive. With the drive.file scope the app can only see files it created, so "choosing a folder"
 * means choosing a path below that app-created root. Uploads use the resumable protocol in 8 MiB chunks.
 * [token] returns a current OAuth access token (or null when re-authorization is required).
 */
class DriveStorageProvider(
    private val token: () -> String?,
    private val rootFolderName: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build(),
) : StorageProvider {

    private val api = "https://www.googleapis.com/drive/v3"
    private val folderMime = "application/vnd.google-apps.folder"
    private val json = "application/json; charset=UTF-8".toMediaType()

    private fun bearer(): String = token()
        ?: throw failure(StorageErrorType.AUTH, "Google authorization is required. Open Storage and reconnect the account.")

    private fun errorFor(code: Int, body: String): StorageException = when {
        code == 401 -> failure(StorageErrorType.AUTH, "Google sign-in expired. Reconnect the account.")
        code == 403 && body.contains("storageQuotaExceeded") -> failure(StorageErrorType.STORAGE_FULL, "Your Google Drive storage is full.")
        code == 403 && (body.contains("rateLimit") || body.contains("RateLimit")) -> failure(StorageErrorType.SERVER, "Google rate limit reached.", retryable = true)
        code == 403 -> failure(StorageErrorType.PERMISSION, "Google denied access (HTTP 403).")
        code == 404 -> failure(StorageErrorType.NOT_FOUND, "Not found on Google Drive.")
        code == 429 || code in 500..599 -> failure(StorageErrorType.SERVER, "Google Drive server error (HTTP $code).", retryable = true)
        else -> failure(StorageErrorType.UNKNOWN, "Google Drive error (HTTP $code).")
    }

    private fun <T> call(builder: Request.Builder, handle: (Response) -> T): T {
        val request = builder.header("Authorization", "Bearer ${bearer()}").build()
        return client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw errorFor(r.code, r.body?.string().orEmpty())
            handle(r)
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun query(q: String): JSONArray {
        val url = "$api/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id,name,mimeType,size,modifiedTime)")
            .addQueryParameter("pageSize", "1000")
            .build()
        return call(Request.Builder().url(url)) { JSONObject(it.body!!.string()).getJSONArray("files") }
    }

    private fun createFolder(name: String, parent: String): String {
        val meta = JSONObject().put("name", name).put("mimeType", folderMime).put("parents", JSONArray().put(parent))
        return call(Request.Builder().url("$api/files?fields=id").post(meta.toString().toRequestBody(json))) {
            JSONObject(it.body!!.string()).getString("id")
        }
    }

    /** Resolves a path below the app root to a folder id, optionally creating missing folders. */
    private fun folderId(path: String, create: Boolean): String? {
        var parent = "root"
        for (seg in listOf(rootFolderName) + path.segments()) {
            val hits = query("name='${esc(seg)}' and '$parent' in parents and mimeType='$folderMime' and trashed=false")
            parent = if (hits.length() > 0) hits.getJSONObject(0).getString("id")
            else if (create) createFolder(seg, parent)
            else return null
        }
        return parent
    }

    private fun fileId(path: String): Pair<String, String>? {
        val folder = folderId(parentOf(path), false) ?: return null
        val hits = query("name='${esc(nameOf(path))}' and '$folder' in parents and trashed=false")
        return if (hits.length() == 0) null else hits.getJSONObject(0).getString("id") to folder
    }

    override suspend fun testConnection(): StorageResult<Unit> = safeIo {
        call(Request.Builder().url("$api/about?fields=user")) {}
    }

    override suspend fun listFiles(path: String): StorageResult<List<RemoteEntry>> = safeIo {
        val folder = folderId(path, false) ?: return@safeIo emptyList()
        val arr = query("'$folder' in parents and trashed=false")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteEntry(
                joinPath(path, o.getString("name")), o.getString("name"), o.getString("mimeType") == folderMime,
                o.optString("size").toLongOrNull() ?: 0L, 0L,
            )
        }
    }

    override suspend fun createDirectory(path: String): StorageResult<Unit> = safeIo { folderId(path, true); Unit }

    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        val parent = folderId(parentOf(remotePath), true)!!
        val name = nameOf(remotePath)
        if (query("name='${esc(name)}' and '$parent' in parents and trashed=false").length() > 0) {
            throw failure(StorageErrorType.PERMISSION, "A file with this name already exists in Drive")
        }
        val total = source.length()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
        val session = call(
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,size")
                .header("X-Upload-Content-Type", "application/octet-stream")
                .header("X-Upload-Content-Length", total.toString())
                .post(meta.toString().toRequestBody(json)),
        ) { it.header("Location") ?: throw failure(StorageErrorType.SERVER, "Drive did not return an upload session", retryable = true) }

        var finalBody = ""
        if (total == 0L) {
            finalBody = client.newCall(Request.Builder().url(session).put(ByteArray(0).toRequestBody(null)).build()).execute().use {
                if (!it.isSuccessful) throw errorFor(it.code, it.body?.string().orEmpty())
                it.body!!.string()
            }
        } else {
            var offset = 0L
            RandomAccessFile(source, "r").use { raf ->
                while (offset < total) {
                    val len = minOf(CHUNK, total - offset).toInt()
                    val buf = ByteArray(len)
                    raf.seek(offset)
                    raf.readFully(buf)
                    val put = Request.Builder().url(session).put(buf.toRequestBody(null))
                        .header("Content-Range", "bytes $offset-${offset + len - 1}/$total").build()
                    client.newCall(put).execute().use { r ->
                        when {
                            r.code == 308 -> offset = r.header("Range")?.substringAfter('-')?.toLongOrNull()?.plus(1) ?: 0L
                            r.isSuccessful -> { finalBody = r.body!!.string(); offset = total }
                            else -> throw errorFor(r.code, r.body?.string().orEmpty())
                        }
                    }
                    onProgress(offset, total)
                }
            }
        }
        val o = JSONObject(finalBody)
        if (o.optString("size").toLongOrNull() != total) {
            runCatching { call(Request.Builder().url("$api/files/${o.getString("id")}").delete()) {} }
            throw failure(StorageErrorType.SERVER, "Upload size mismatch on Drive", retryable = true)
        }
    }

    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        val (id, _) = fileId(remotePath) ?: throw failure(StorageErrorType.NOT_FOUND, "No such file on Drive")
        val part = File(target.parentFile, ".${target.name}.part")
        try {
            call(Request.Builder().url("$api/files/$id?alt=media")) { r ->
                val total = r.body!!.contentLength()
                r.body!!.byteStream().use { i ->
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
                if (total >= 0 && part.length() != total) throw IOException("Download was cut short")
            }
            if (!part.renameTo(target)) throw IOException("Could not finalize ${target.name}")
        } finally {
            if (part.exists()) part.delete()
        }
    }

    override suspend fun renameFile(fromPath: String, toPath: String): StorageResult<Unit> = safeIo {
        if (parentOf(fromPath) != parentOf(toPath)) throw failure(StorageErrorType.UNSUPPORTED, "Moving between Drive folders is not supported yet; only renaming in place.")
        val (id, _) = fileId(fromPath) ?: throw failure(StorageErrorType.NOT_FOUND, "No such file on Drive")
        val body = JSONObject().put("name", nameOf(toPath)).toString().toRequestBody(json)
        call(Request.Builder().url("$api/files/$id").patch(body)) {}
    }

    override suspend fun deleteFile(path: String): StorageResult<Unit> = safeIo {
        val (id, _) = fileId(path) ?: return@safeIo
        call(Request.Builder().url("$api/files/$id").delete()) {}
    }

    override fun getAvailableCapabilities() = setOf(
        StorageCapability.LIST, StorageCapability.CREATE_DIRECTORY, StorageCapability.UPLOAD,
        StorageCapability.DOWNLOAD, StorageCapability.RENAME, StorageCapability.DELETE, StorageCapability.RESUME,
    )

    private companion object {
        const val CHUNK = 8L * 1024 * 1024 // must be a multiple of 256 KiB
    }
}
