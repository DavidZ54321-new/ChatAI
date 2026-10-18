package com.zcw.chatai.data.web

import com.zcw.chatai.data.web.deepseek.DeepSeekFetchProvider
import com.zcw.chatai.data.web.deepseek.DeepSeekTools
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepSeekFetchProviderTest {

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

    private fun fetcher() = DeepSeekFetchProvider(hostPolicy = { true })

    @Test
    fun postsUrlAndPrefixesTitle() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"title":"Article","content":"# Hello"}"""),
        )
        val page = "https://example.com/article"
        val result = fetcher().fetch(page, server.url("/v1").toString(), "sk-test")
        assertEquals(200, result.statusCode)
        assertTrue(result.text, result.text.startsWith("Article\n\n# Hello"))
        val recorded = server.takeRequest()
        assertEquals("/v1/tools/fetch", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains(page))
    }

    @Test
    fun httpErrorIsAFailedResultAndDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"blocked"}}"""))
        val result = fetcher().fetch("https://example.com/x", server.url("/v1").toString(), "sk-test")
        assertEquals(404, result.statusCode)
        assertTrue(result.text, result.text.contains("blocked"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsUserInfoAndOverlongUrlsBeforeTheNetwork() = runBlocking {
        val userInfo = fetcher().fetch(
            "https://user:pass@example.com/a",
            "https://api.deepseek.com/v1",
            "sk-test",
        )
        assertEquals(0, userInfo.statusCode)
        assertTrue(userInfo.text, userInfo.text.contains("用户名"))
        val huge = "https://example.com/" + "a".repeat(DeepSeekTools.MAX_URL_CHARS)
        val tooLong = fetcher().fetch(huge, "https://api.deepseek.com/v1", "sk-test")
        assertEquals(0, tooLong.statusCode)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun rejectsPrivateHostByDefault() = runBlocking {
        val result = DeepSeekFetchProvider().fetch(
            server.url("/page").toString(),
            server.url("/v1").toString(),
            "sk-test",
        )
        assertEquals(0, result.statusCode)
        assertEquals(0, server.requestCount)
    }
}
