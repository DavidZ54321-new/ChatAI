package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.web.deepseek.DeepSeekSearchApiProvider
import com.zcw.chatai.data.web.deepseek.DeepSeekTools
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DeepSeekSearchApiProviderTest {

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

    @Test
    fun postsQueryWithoutPageContent() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"results":[{"title":"A","url":"https://a.example","summary":"sum","snippet":"snip"}]}""",
            ),
        )
        val result = DeepSeekSearchApiProvider().search("kotlin news", 5, config())
        assertEquals("sum", result.sources.single().snippet)
        val recorded = server.takeRequest()
        assertEquals("/v1/tools/search", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body, body.contains("\"query\":\"kotlin news\""))
        assertTrue(body, body.contains("\"count\":5"))
        assertTrue(body, body.contains("\"need_content\":false"))
        assertTrue(body, !body.contains("localization"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun overlongQueryFailsBeforeTheNetwork() = runBlocking {
        val query = "q".repeat(DeepSeekTools.MAX_QUERY_CHARS + 1)
        try {
            DeepSeekSearchApiProvider().search(query, 5, config())
            fail("expected failure")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("128"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun http400DoesNotRetry() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"'count' must be between 1 and 50","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}""",
            ),
        )
        try {
            DeepSeekSearchApiProvider().search("kotlin", 5, config())
            fail("expected failure")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("count"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun emptyResultsStayEmpty() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val result = DeepSeekSearchApiProvider().search("nothing", 5, config())
        assertTrue(result.sources.isEmpty())
    }

    private fun config() = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "deepseek-flash",
        systemPrompt = "",
        temperature = null,
    )
}
