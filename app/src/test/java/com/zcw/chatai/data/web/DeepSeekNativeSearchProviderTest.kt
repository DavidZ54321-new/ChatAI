package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
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
    fun mapsHttpErrorToFriendlyMessage() = runBlocking {
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
    }

    @Test
    fun availabilityRequiresKeyAndValidUrl() {
        val provider = DeepSeekNativeSearchProvider()
        assertTrue(provider.available("https://api.deepseek.com/v1", "k"))
        assertTrue(!provider.available("https://api.deepseek.com/v1", ""))
        assertTrue(!provider.available("", "k"))
    }

    private fun config() = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "deepseek-flash",
        systemPrompt = "",
        temperature = null,
    )
}
