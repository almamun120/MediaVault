package com.mediavault.storage.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class StorageException(val error: StorageError) : Exception(error.message)

fun String.segments(): List<String> = split('/', '\\').filter { it.isNotEmpty() }

/** Absolute, normalised path: joinPath("/a/", "b", "c/") == "/a/b/c". Drops "." and ".." parts. */
fun joinPath(vararg parts: String): String =
    parts.flatMap { it.segments() }.filter { it != "." && it != ".." }.joinToString("/", prefix = "/")

fun parentOf(path: String): String = path.segments().dropLast(1).joinToString("/", prefix = "/")
fun nameOf(path: String): String = path.segments().lastOrNull().orEmpty()

fun failure(type: StorageErrorType, msg: String, retryable: Boolean = false) =
    StorageException(StorageError(type, msg, retryable))

/** Maps any exception to a typed error. Messages never include credentials. */
fun Throwable.toStorageError(): StorageError = when (this) {
    is StorageException -> error
    is UnknownHostException -> StorageError(StorageErrorType.NETWORK, "Server not found: $message", true)
    is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
        StorageError(StorageErrorType.NETWORK, "Cannot reach the server: $message", true)
    is SSLException -> StorageError(StorageErrorType.AUTH, "Secure connection failed (certificate not trusted or TLS error): $message", false)
    is IllegalArgumentException -> StorageError(StorageErrorType.UNKNOWN, "Invalid address or setting: $message", false)
    is IOException ->
        if (message.orEmpty().contains("ENOSPC") || message.orEmpty().contains("No space", true))
            StorageError(StorageErrorType.STORAGE_FULL, "Storage is full", false)
        else StorageError(StorageErrorType.NETWORK, message ?: "I/O error", true)
    else -> StorageError(StorageErrorType.UNKNOWN, message ?: javaClass.simpleName, false)
}

/** Runs blocking protocol code on the IO dispatcher and converts exceptions to [StorageResult.Failure]. */
suspend fun <T> safeIo(block: () -> T): StorageResult<T> = withContext(Dispatchers.IO) {
    try {
        StorageResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StorageResult.Failure(e.toStorageError())
    }
}

class ProgressInputStream(inner: InputStream, private val total: Long, private val onProgress: ProgressCallback) : FilterInputStream(inner) {
    private var done = 0L
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) { done += n; onProgress(done, total) }
        return n
    }
}

class ProgressOutputStream(inner: OutputStream, private val total: Long, private val onProgress: ProgressCallback) : FilterOutputStream(inner) {
    private var done = 0L
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        done += len
        onProgress(done, total)
    }
}
