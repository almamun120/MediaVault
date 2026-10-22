package com.mediavault.storage.webdav

import com.mediavault.storage.api.*
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV over HTTP or HTTPS (TLS certificates are validated by the platform; nothing is disabled).
 * Authentication: HTTP Basic. Digest/NTLM/OAuth are not implemented. Basic over plain http:// sends the
 * password in clear text, so the UI warns about it.
 */
class WebDavStorageProvider(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build(),
) : StorageProvider {

    private val base = baseUrl.trim().trimEnd('/')
    private val authHeader = if (username.isNotEmpty()) Credentials.basic(username, password, Charsets.UTF_8) else null
    private val xml = "application/xml; charset=utf-8".toMediaType()

    private fun url(path: String): String {
        val enc = path.segments().joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return if (enc.isEmpty()) base else "$base/$enc"
    }

    private fun req(path: String, method: String, body: RequestBody? = null, vararg headers: Pair<String, String>): Request =
        Request.Builder().url(url(path)).method(method, body).apply {
            authHeader?.let { header("Authorization", it) }
            headers.forEach { header(it.first, it.second) }
        }.build()

    private fun errorFor(code: Int, message: String): StorageException = when (code) {
        401 -> failure(StorageErrorType.AUTH, "Authentication failed (HTTP 401). Check username and password.")
        403 -> failure(StorageErrorType.PERMISSION, "Access denied (HTTP 403).")
        404 -> failure(StorageErrorType.NOT_FOUND, "Not found (HTTP 404).")
        409 -> failure(StorageErrorType.NOT_FOUND, "A parent folder does not exist (HTTP 409).")
        507 -> failure(StorageErrorType.STORAGE_FULL, "The server has no space left (HTTP 507).")
        408, 429, in 500..599 -> failure(StorageErrorType.SERVER, "Server error (HTTP $code $message).", retryable = true)
        else -> failure(StorageErrorType.UNKNOWN, "Unexpected response (HTTP $code $message).")
    }

    private fun <T> exec(request: Request, allow: Set<Int> = emptySet(), handle: (Response) -> T): T =
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful && r.code !in allow) throw errorFor(r.code, r.message)
            handle(r)
        }

    private fun mkcols(path: String) {
        var cur = ""
        for (seg in path.segments()) {
            cur += "/$seg"
            exec(req(cur, "MKCOL"), allow = setOf(405)) {} // 405 = already exists
        }
    }

    private fun headSize(path: String): Long? =
        exec(req(path, "HEAD"), allow = setOf(405, 501)) { it.header("Content-Length")?.toLongOrNull() }

    override suspend fun testConnection(): StorageResult<Unit> = safeIo {
        exec(req("/", "PROPFIND", "".toRequestBody(xml), "Depth" to "0")) {}
    }

    override suspend fun listFiles(path: String): StorageResult<List<RemoteEntry>> = safeIo {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val text = exec(req(path, "PROPFIND", body.toRequestBody(xml), "Depth" to "1")) { it.body?.string().orEmpty() }
        parseMultiStatus(text, path)
    }

    override suspend fun createDirectory(path: String): StorageResult<Unit> = safeIo { mkcols(path) }

    override suspend fun uploadFile(source: File, remotePath: String, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        mkcols(parentOf(remotePath))
        val total = source.length()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = total
            override fun writeTo(sink: BufferedSink) {
                source.source().use { src ->
                    val buf = Buffer()
                    var done = 0L
                    while (true) {
                        val n = src.read(buf, 64 * 1024)
                        if (n < 0) break
                        sink.write(buf, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        exec(req(remotePath, "PUT", body)) {}
        val remote = headSize(remotePath)
        if (remote != null && remote != total) {
            throw failure(StorageErrorType.SERVER, "Upload size mismatch (server has $remote bytes, expected $total)", retryable = true)
        }
    }

    override suspend fun downloadFile(remotePath: String, target: File, onProgress: ProgressCallback): StorageResult<Unit> = safeIo {
        exec(req(remotePath, "GET")) { r ->
            val total = r.body?.contentLength() ?: -1L
            val part = File(target.parentFile, ".${target.name}.part")
            try {
                r.body!!.byteStream().use { input ->
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
                if (total >= 0 && part.length() != total) throw IOException("Download was cut short")
                if (!part.renameTo(target)) throw IOException("Could not finalize ${target.name}")
            } finally {
                if (part.exists()) part.delete()
            }
        }
    }

    override suspend fun renameFile(fromPath: String, toPath: String): StorageResult<Unit> = safeIo {
        mkcols(parentOf(toPath))
        exec(req(fromPath, "MOVE", null, "Destination" to url(toPath), "Overwrite" to "F")) {}
    }

    override suspend fun deleteFile(path: String): StorageResult<Unit> = safeIo {
        exec(req(path, "DELETE")) {}
    }

    override fun getAvailableCapabilities() = setOf(
        StorageCapability.LIST, StorageCapability.CREATE_DIRECTORY, StorageCapability.UPLOAD,
        StorageCapability.DOWNLOAD, StorageCapability.RENAME, StorageCapability.DELETE,
    )

    private fun parseMultiStatus(text: String, requestedPath: String): List<RemoteEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
        val selfPath = runCatching { URLDecoder.decode(URI(url(requestedPath)).rawPath.replace("+", "%2B"), "UTF-8").trimEnd('/') }.getOrDefault("")
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        val out = mutableListOf<RemoteEntry>()
        val nodes = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            fun tag(name: String) = el.getElementsByTagNameNS("DAV:", name).item(0)?.textContent?.trim().orEmpty()
            val href = tag("href")
            val rawPath = runCatching { URI(href).rawPath ?: href }.getOrDefault(href)
            val decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
            if (decoded.trimEnd('/') == selfPath) continue
            val name = decoded.trimEnd('/').substringAfterLast('/')
            if (name.isEmpty()) continue
            out += RemoteEntry(
                path = joinPath(requestedPath, name),
                name = name,
                isDirectory = el.getElementsByTagNameNS("DAV:", "collection").length > 0,
                sizeBytes = tag("getcontentlength").toLongOrNull() ?: 0L,
                modifiedAt = runCatching { dateFormat.parse(tag("getlastmodified"))?.time ?: 0L }.getOrDefault(0L),
            )
        }
        return out
    }
}
