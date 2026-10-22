package com.mediavault.storage

import com.mediavault.storage.api.StorageErrorType
import com.mediavault.storage.api.StorageResult
import com.mediavault.storage.webdav.WebDavStorageProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class WebDavStorageProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: WebDavStorageProvider

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        provider = WebDavStorageProvider(server.url("/dav").toString(), "user", "pw")
    }
    @After fun tearDown() = server.shutdown()

    private fun tmp(text: String) = Files.createTempFile("up", ".bin").toFile().apply { writeText(text) }

    @Test fun unauthorizedMapsToAuthError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val r = provider.testConnection()
        assertEquals(StorageErrorType.AUTH, (r as StorageResult.Failure).error.type)
    }

    @Test fun listParsesMultiStatusAndSkipsSelf() = runBlocking {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/dav/Photos/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/dav/a%20b.jpg</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength><d:getlastmodified>Sat, 11 Oct 2026 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
            </d:multistatus>"""
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
        val list = (provider.listFiles("/") as StorageResult.Success).value
        assertEquals(listOf("Photos", "a b.jpg"), list.map { it.name })
        assertTrue(list[0].isDirectory)
        assertEquals(1234L, list[1].sizeBytes)
        assertEquals("PROPFIND", server.takeRequest().method)
    }

    @Test fun serverFullMapsToStorageFull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(507))
        val r = provider.uploadFile(tmp("abc"), "/x.bin")
        assertEquals(StorageErrorType.STORAGE_FULL, (r as StorageResult.Failure).error.type)
    }

    @Test fun uploadSendsBasicAuthAndVerifiesSize() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "3"))
        val r = provider.uploadFile(tmp("abc"), "/x.bin")
        assertTrue(r is StorageResult.Success)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/dav/x.bin", put.path)
        assertTrue(put.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun sizeMismatchIsRetryableServerError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "5"))
        val err = (provider.uploadFile(tmp("abc"), "/x.bin") as StorageResult.Failure).error
        assertEquals(StorageErrorType.SERVER, err.type)
        assertTrue(err.retryable)
    }

    @Test fun serverErrorIsRetryable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val err = (provider.testConnection() as StorageResult.Failure).error
        assertTrue(err.retryable)
    }
}
