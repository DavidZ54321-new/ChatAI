package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiImageClientTest {

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
    fun generationsPostsToImagesEndpoint() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"YQ=="}]}"""))
        val images = OpenAiImageClient().generate(config(), "gpt-image-2.5-flare", "一只猫", emptyList())
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(recorded.path!!.endsWith("/images/generations"))
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("1024x1024"))
        assertEquals("a", images.single().decodeToString())
    }

    @Test
    fun editsPostsReferenceImages() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"Yg=="}]}"""))
        OpenAiImageClient().generate(
            config(),
            "gpt-image-2.5-sunburst",
            "夜景",
            listOf("data:image/png;base64,YQ=="),
        )
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(recorded.path!!.endsWith("/images/edits"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"size\":\"auto\""))
        assertTrue(body.contains("data:image/png;base64,YQ=="))
    }

    @Test
    fun httpErrorUsesServerMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":{"message":"organization must be verified"}}"""),
        )
        try {
            OpenAiImageClient().generate(config(), "gpt-image-2.5-flare", "猫", emptyList())
            throw AssertionError("expected ChatApiException")
        } catch (error: ChatApiException) {
            assertTrue(error.message!!.contains("organization must be verified"))
        }
    }

    private fun config(): ChatConfig = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "gpt-6-astra",
        systemPrompt = "",
        temperature = null,
    )
}
