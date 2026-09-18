package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class QwenWebSearchProviderTest {

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
    fun postsResponsesRequestAndParsesSources() = runBlocking {
        server.enqueue(okResponse())
        val provider = QwenWebSearchProvider()
        val result = provider.search("杭州天气", 5, config())

        assertEquals("杭州明天多云。", result.answer)
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), result.sources.map { it.url })

        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/responses", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val payload = recorded.body.readUtf8()
        assertTrue(payload, payload.contains("\"model\":\"qwen3.8-max\""))
        assertTrue(payload, payload.contains("\"type\":\"web_search\""))
        assertTrue(payload, payload.contains("\"enable_thinking\":false"))
        assertTrue(payload, payload.contains("杭州天气"))
    }

    @Test
    fun capsSourcesToMaxResults() = runBlocking {
        server.enqueue(okResponse())
        val result = QwenWebSearchProvider().search("x", 1, config())
        assertEquals(listOf("https://a.example/1"), result.sources.map { it.url })
    }

    @Test
    fun readsBodyAcrossMultipleNetworkChunks() = runBlocking {
        // 回归：body 必须循环读到 EOF，否则长 JSON 会被截断成「没有结果」。
        val payload = buildString {
            append("""{"output":[{"type":"web_search_call","action":{"type":"search","sources":[""")
            repeat(400) { append("""{"type":"url","url":"https://example.com/$it"}, """) }
            append("""{"type":"url","url":"https://last.example.com"}]}}]}""")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(payload)
                .throttleBody(256, 1, TimeUnit.MILLISECONDS),
        )
        val result = QwenWebSearchProvider().search("x", 10_000, config())
        assertEquals("https://last.example.com", result.sources.last().url)
        assertTrue(result.sources.size > 300)
    }

    @Test
    fun retriesTransientDisconnectThenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(okResponse())
        val result = QwenWebSearchProvider().search("x", 5, config())
        assertEquals("杭州明天多云。", result.answer)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun doesNotRetryHttpUnauthorized() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"bad key"}}"""),
        )
        try {
            QwenWebSearchProvider().search("x", 5, config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("API Key"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun availabilityRequiresKeyAndValidUrl() {
        val provider = QwenWebSearchProvider()
        assertTrue(provider.available("https://dashscope.aliyuncs.com/compatible-mode/v1", "k"))
        assertTrue(!provider.available("https://dashscope.aliyuncs.com/compatible-mode/v1", ""))
        assertTrue(!provider.available("", "k"))
    }

    private fun okResponse() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"output":[
                {"type":"reasoning","summary":[]},
                {"type":"web_search_call","status":"completed","action":{"type":"search","sources":[
                    {"type":"url","url":"https://a.example/1"},
                    {"type":"url","url":"https://b.example/2"}
                ]}},
                {"type":"message","role":"assistant","status":"completed","content":[
                    {"type":"output_text","text":"杭州明天多云。","annotations":[]}
                ]}
            ],"usage":{"x_tools":{"web_search":{"count":1}}}}""",
        )

    private fun config() = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "qwen3.8-max",
        systemPrompt = "",
        temperature = null,
    )
}
