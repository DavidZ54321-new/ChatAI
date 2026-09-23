package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.net.OpenAiCompatibleChatApi
import com.zcw.chatai.data.web.mimo.MiMoSearchParser
import com.zcw.chatai.data.web.mimo.MiMoWebSearchProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

class MiMoWebSearchProviderTest {

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
    fun postsChatCompletionsRequestWithWebSearchPlugin() = runBlocking {
        server.enqueue(okResponse())
        val result = MiMoWebSearchProvider().search("kotlin coroutines", 5, config())
        assertEquals("总结正文", result.answer)
        assertEquals("https://a.example", result.sources.single().url)

        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertEquals(OpenAiCompatibleChatApi.USER_AGENT, recorded.getHeader("User-Agent"))
        val payload = recorded.body.readUtf8()
        assertTrue(payload, payload.contains("\"type\":\"web_search\""))
        assertTrue(payload, payload.contains("\"force_search\":true"))
        assertTrue(payload, payload.contains("\"max_keyword\":3"))
        assertTrue(payload, payload.contains("\"tool_choice\":\"auto\""))
        assertTrue("纯搜索关思考", payload.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(payload, payload.contains("kotlin coroutines"))
        assertTrue(payload, payload.contains("\"stream\":false"))
    }

    @Test
    fun parsesAnnotationsIntoSourcesWithSnippetAndDate() {
        val raw = """
            {"choices":[{"message":{
              "content":"答案",
              "annotations":[
                {"type":"url_citation","url":"https://a","title":"A","summary":"摘要A","publish_time":"2026-09-01"},
                {"type":"url_citation","url":"https://b","title":"B","summary":"摘要B"},
                {"type":"url_citation","url":"https://a","title":"重复忽略"},
                {"type":"other","url":"https://ignored","title":"非引用类型"}
              ]
            }}]}
        """.trimIndent()
        val result = MiMoSearchParser.parse(raw, 5)
        assertEquals("答案", result.answer)
        assertEquals(2, result.sources.size)
        assertEquals("https://a", result.sources[0].url)
        assertEquals("摘要A", result.sources[0].snippet)
        assertEquals("2026-09-01", result.sources[0].publishedAt)
        assertEquals("https://b", result.sources[1].url)
    }

    @Test
    fun capsSourcesToMaxResults() {
        val annotations = (1..10).joinToString(",") {
            """{"type":"url_citation","url":"https://s$it","title":"t$it"}"""
        }
        val raw = """{"choices":[{"message":{"content":"x","annotations":[$annotations]}}]}"""
        assertEquals(3, MiMoSearchParser.parse(raw, 3).sources.size)
    }

    @Test
    fun malformedOrEmptyBodyDegradesToEmptyResult() {
        assertTrue(MiMoSearchParser.parse("{not json", 5).sources.isEmpty())
        assertTrue(MiMoSearchParser.parse("{}", 5).sources.isEmpty())
        assertTrue(MiMoSearchParser.parse("""{"choices":[]}""", 5).sources.isEmpty())
        assertTrue(
            MiMoSearchParser.parse("""{"choices":[{"message":{"annotations":[]}}]}""", 5)
                .let { it.sources.isEmpty() && it.answer == null },
        )
    }

    @Test
    fun emptySearchResultStaysEmptySoFallbackChainBorrowsNext() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":null,"annotations":[]}}]}"""),
        )
        val result = MiMoWebSearchProvider().search("x", 5, config())
        assertTrue(result.sources.isEmpty())
        assertTrue(result.answer == null)
    }

    @Test
    fun readsBodyAcrossMultipleNetworkChunks() = runBlocking {
        // 回归：body 必须读到 EOF，截断的 JSON 会让解析永远得到空结果。
        val annotations = (0..300).joinToString(",") {
            """{"type":"url_citation","url":"https://example.com/$it","title":"t$it"}"""
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"OK","annotations":[$annotations]}}]}""")
                .throttleBody(256, 1, TimeUnit.MILLISECONDS),
        )
        val result = MiMoWebSearchProvider().search("x", 999, config())
        assertEquals("OK", result.answer)
        assertEquals(301, result.sources.size)
    }

    @Test
    fun doesNotRetryHttpUnauthorized() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"bad key"}}"""),
        )
        try {
            MiMoWebSearchProvider().search("x", 5, config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("API Key"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesTransientDisconnectThenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(okResponse())
        val result = MiMoWebSearchProvider().search("x", 5, config())
        assertEquals("总结正文", result.answer)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cancellationStopsTheInFlightCall() = runBlocking {
        server.enqueue(okResponse().setBodyDelay(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val job = launch(Dispatchers.IO) {
            MiMoWebSearchProvider().search("x", 5, config())
        }
        delay(200)
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancellation took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun availabilityRequiresKeyAndValidUrl() {
        val provider = MiMoWebSearchProvider()
        assertTrue(provider.available("https://api.xiaomimimo.com/v1", "sk-x"))
        assertTrue(!provider.available("https://api.xiaomimimo.com/v1", ""))
        assertTrue(!provider.available("", "sk-x"))
    }

    private fun okResponse() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"choices":[{"message":{"content":"总结正文","annotations":[{"type":"url_citation","url":"https://a.example","title":"A"}]}}]}""",
        )

    private fun config() = ChatConfig(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key",
        model = "mimo-v2.6-flash",
        systemPrompt = "",
        temperature = null,
    )
}
