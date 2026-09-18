package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DeepSeekNativeSearchProviderTest {

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
    fun postsAnthropicRequestAndParsesSources() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"content":[{"type":"text","text":"OK"},{"type":"web_search_tool_result","tool_use_id":"s","content":[{"type":"web_search_result","url":"https://a","title":"A"}]}]}""",
                ),
        )
        val provider = DeepSeekNativeSearchProvider()
        val result = provider.search("kotlin", 5, config())
        assertEquals("OK", result.answer)
        assertEquals("https://a", result.sources.single().url)

        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/anthropic/v1/messages", recorded.path)
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val payload = recorded.body.readUtf8()
        assertTrue(payload, payload.contains("\"web_search_20250305\""))
        assertTrue(payload, payload.contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"web_search\"}"))
    }

    @Test
    fun coercesMaxUsesIntoRange() = runBlocking {
        server.enqueue(okResponse())
        DeepSeekNativeSearchProvider().search("kotlin", 99, config())
        val high = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue(high, high.contains("\"max_uses\":5"))

        server.enqueue(okResponse())
        DeepSeekNativeSearchProvider().search("kotlin", 0, config())
        val low = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue(low, low.contains("\"max_uses\":1"))
    }

    @Test
    fun readsBodyAcrossMultipleNetworkChunks() = runBlocking {
        // 回归：`source.read(buffer, n)` 一次只返回一个分片，直接收手会把 JSON 从中间截断。
        val payload = buildString {
            append("""{"content":[{"type":"text","text":"OK"},{"type":"web_search_tool_result","tool_use_id":"s","content":[""")
            repeat(400) { append("""{"type":"web_search_result","url":"https://example.com/$it","title":"t"}, """) }
            append("""{"type":"web_search_result","url":"https://last.example.com","title":"last"}]}]}""")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(payload)
                .throttleBody(256, 1, TimeUnit.MILLISECONDS),
        )

        val result = DeepSeekNativeSearchProvider().search("x", 5, config())

        assertEquals("OK", result.answer)
        assertEquals("https://last.example.com", result.sources.last().url)
        assertTrue(result.sources.size > 100)
    }

    @Test
    fun cancellationStopsTheInFlightCall() = runBlocking {
        server.enqueue(okResponse().setBodyDelay(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val job = launch(Dispatchers.IO) {
            DeepSeekNativeSearchProvider().search("x", 5, config())
        }
        delay(200)
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancellation took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun retriesTransientDisconnectThenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(okResponse())
        val result = DeepSeekNativeSearchProvider().search("x", 5, config())
        assertEquals("OK", result.answer)
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
            DeepSeekNativeSearchProvider().search("x", 5, config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("API Key"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun availabilityRequiresKeyAndValidUrl() {
        val provider = DeepSeekNativeSearchProvider()
        assertTrue(provider.available("https://api.deepseek.com/v1", "k"))
        assertTrue(!provider.available("https://api.deepseek.com/v1", ""))
        assertTrue(!provider.available("", "k"))
    }

    private fun okResponse() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"content":[{"type":"text","text":"OK"},{"type":"web_search_tool_result","tool_use_id":"s","content":[{"type":"web_search_result","url":"https://a","title":"A"}]}]}""",
        )

    private fun config() = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "deepseek-flash",
        systemPrompt = "",
        temperature = null,
    )
}
