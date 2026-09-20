package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatApiException
import com.zcw.chatai.data.web.qwen.QwenImageSearchProvider
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

class QwenImageSearchProviderTest {

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
    fun textSearchUsesWebSearchImageTool() = runBlocking {
        server.enqueue(imageResponse("web_search_image_call"))
        val outcome = QwenImageSearchProvider().searchByText("科技感封面", 10, config())

        assertEquals(listOf("https://img.example/a", "https://img.example/b"), outcome.images.map { it.url })
        assertEquals("找到了两张。", outcome.answer)

        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/responses", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val payload = recorded.body.readUtf8()
        assertTrue(payload, payload.contains("\"type\":\"web_search_image\""))
        assertTrue(payload, payload.contains("科技感封面"))
        assertTrue(payload, payload.contains("\"enable_thinking\":false"))
    }

    @Test
    fun imageSearchSendsBase64InputImage() = runBlocking {
        server.enqueue(imageResponse("image_search_call"))
        val dataUrl = "data:image/jpeg;base64,QUJD"
        val outcome = QwenImageSearchProvider().searchByImage(dataUrl, "找相似风格", 10, config())

        assertEquals(2, outcome.images.size)

        val payload = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue(payload, payload.contains("\"type\":\"image_search\""))
        assertTrue(payload, payload.contains("\"type\":\"input_image\""))
        assertTrue(payload, payload.contains(dataUrl))
        assertTrue(payload, payload.contains("找相似风格"))
    }

    @Test
    fun capsImagesToMaxResults() = runBlocking {
        server.enqueue(imageResponse("web_search_image_call"))
        val outcome = QwenImageSearchProvider().searchByText("x", 1, config())
        assertEquals(listOf("https://img.example/a"), outcome.images.map { it.url })
    }

    @Test
    fun mapsHttpErrorsToReadableException() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"model does not support image_search"}}"""),
        )
        try {
            QwenImageSearchProvider().searchByText("x", 10, config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("image_search"))
        }
    }

    @Test
    fun availabilityRequiresKeyAndValidUrl() {
        val provider = QwenImageSearchProvider()
        assertTrue(provider.available("https://dashscope.aliyuncs.com/compatible-mode/v1", "k"))
        assertTrue(!provider.available("https://dashscope.aliyuncs.com/compatible-mode/v1", ""))
        assertTrue(!provider.available("", "k"))
    }

    private fun imageResponse(callType: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"output":[
                {"type":"$callType","status":"completed","output":"[{\"index\":1,\"title\":\"A\",\"url\":\"https://img.example/a\"},{\"index\":2,\"title\":\"B\",\"url\":\"https://img.example/b\"}]"},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"找到了两张。"}]}
            ]}""",
        )

    private fun config() = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "qwen3.8-max",
        systemPrompt = "",
        temperature = null,
    )
}
