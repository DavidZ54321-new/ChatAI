package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.OpenAiCompatibleChatApi
import com.zcw.chatai.data.provider.ProviderCatalog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ResponsesWebSearchProviderTest {

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
    fun postsResponsesRequestWithoutQwenOnlyFields() = runBlocking {
        server.enqueue(okResponse())
        val result = ResponsesWebSearchProvider().search("latest Bun release", 5, config())

        assertEquals("Bun v1.2 released.", result.answer)
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), result.sources.map { it.url })

        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/responses", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val payload = recorded.body.readUtf8()
        assertTrue(payload, payload.contains("\"model\":\"grok-4.6\""))
        assertTrue(payload, payload.contains("\"type\":\"web_search\""))
        assertTrue(payload, payload.contains("latest Bun release"))
        // Qwen 专有字段不得出现在通用实现里。
        assertTrue(payload, !payload.contains("enable_thinking"))
    }

    @Test
    fun sendsGoGatewayHeadersWhenSendSessionHeader() = runBlocking {
        server.enqueue(okResponse())
        ResponsesWebSearchProvider().search(
            "x",
            5,
            config().copy(
                providerId = ProviderCatalog.OPENCODE_GO,
                sendSessionHeader = true,
                sessionId = "conv-1",
            ),
        )
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("conv-1", recorded.getHeader("x-opencode-session"))
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertEquals(OpenAiCompatibleChatApi.USER_AGENT, recorded.getHeader("User-Agent"))
    }

    @Test
    fun doesNotSendSessionHeadersForQwenStyleConfig() = runBlocking {
        server.enqueue(okResponse())
        ResponsesWebSearchProvider().search("x", 5, config())
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(null, recorded.getHeader("x-api-key"))
        assertEquals(null, recorded.getHeader("x-opencode-session"))
    }

    @Test
    fun capsSourcesToMaxResults() = runBlocking {
        server.enqueue(okResponse())
        val result = ResponsesWebSearchProvider().search("x", 1, config())
        assertEquals(1, result.sources.size)
    }

    @Test
    fun emptyOutputParsesToEmptyResultForOuterFallback() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"output":[]}"""),
        )
        val result = ResponsesWebSearchProvider().search("x", 5, config())
        assertTrue(result.sources.isEmpty())
        assertEquals(null, result.answer)
    }

    @Test
    fun httpErrorPropagatesForRouterFallback() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Endpoint is unavailable"}}"""),
        )
        try {
            ResponsesWebSearchProvider().search("x", 5, config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun availabilityRequiresKeyAndValidUrl() {
        val provider = ResponsesWebSearchProvider()
        assertTrue(provider.available("https://opencode.ai/zen/go/v1", "k"))
        assertTrue(!provider.available("https://opencode.ai/zen/go/v1", ""))
        assertTrue(!provider.available("", "k"))
    }

    private fun okResponse() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"output":[
                {"type":"web_search_call","status":"completed","action":{"type":"search","sources":[
                    {"type":"url","url":"https://a.example/1"},
                    {"type":"url","url":"https://b.example/2"}
                ]}},
                {"type":"message","role":"assistant","status":"completed","content":[
                    {"type":"output_text","text":"Bun v1.2 released.","annotations":[]}
                ]}
            ]}""",
        )

    private fun config() = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "grok-4.6",
        systemPrompt = "",
        temperature = null,
    )
}
