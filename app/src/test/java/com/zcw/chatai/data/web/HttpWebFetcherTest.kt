package com.zcw.chatai.data.web

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpWebFetcherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher() = HttpWebFetcher(hostPolicy = { true })

    @Test
    fun convertsHtmlToText() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<html><body><p>你好</p></body></html>"),
        )
        val result = fetcher().fetch(server.url("/page").toString())
        assertEquals(200, result.statusCode)
        assertTrue(result.text, result.text.contains("你好"))
    }

    @Test
    fun non2xxIsAResultNotAnError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("nope"))
        val result = fetcher().fetch(server.url("/missing").toString())
        assertEquals(404, result.statusCode)
        assertTrue(result.text, result.text.contains("nope"))
    }

    @Test
    fun rejectsNonHttpScheme() = runBlocking {
        val result = fetcher().fetch("file:///etc/passwd")
        assertEquals(0, result.statusCode)
        assertTrue(result.text, result.text.contains("http"))
    }

    @Test
    fun rejectsPrivateHostByDefault() = runBlocking {
        val result = HttpWebFetcher().fetch(server.url("/page").toString())
        assertEquals(0, result.statusCode)
    }

    @Test
    fun rejectsBlockedContentType() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("bin"))
        val result = fetcher().fetch(server.url("/bin").toString())
        assertEquals(0, result.statusCode)
    }
}
