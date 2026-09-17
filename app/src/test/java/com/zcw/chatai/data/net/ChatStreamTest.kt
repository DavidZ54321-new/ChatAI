package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChatStreamTest {

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
    fun streamsThrottledChineseContentWithoutMojibake() = runBlocking {
        val expected = "你好，世界！这是一段中文流式内容，用来验证多字节字符被 TCP 分片切断的情况。"
        val body = expected.chunked(2).joinToString("") { deltaEvent(it) } + DONE_EVENT
        server.enqueue(eventStream(body).throttleBody(7, 5, TimeUnit.MILLISECONDS))

        val events = collectEvents(config())

        assertEquals(expected, events.text())
        assertTrue(events.last() is ChatStreamEvent.Completed)
    }

    @Test
    fun completesWhenDoneMarkerArrives() = runBlocking {
        server.enqueue(eventStream(deltaEvent("第一段") + deltaEvent("第二段") + DONE_EVENT))

        val events = collectEvents(config())

        assertEquals("第一段第二段", events.text())
        assertEquals(1, events.filterIsInstance<ChatStreamEvent.Completed>().size)
    }

    @Test
    fun completesWhenServerClosesWithoutDoneMarker() = runBlocking {
        server.enqueue(eventStream(deltaEvent("没有结束标记")))

        val events = collectEvents(config())

        assertEquals("没有结束标记", events.text())
        assertTrue(events.last() is ChatStreamEvent.Completed)
    }

    @Test
    fun mapsHttpErrorBodyToFriendlyMessage() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Invalid API key","type":"authentication_error","code":"invalid_api_key"}}"""),
        )

        try {
            collectEvents(config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("API Key"))
        }
    }

    @Test
    fun keepsServerDetailForBadRequest() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"model not found"}}"""),
        )

        try {
            collectEvents(config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("model not found"))
        }
    }

    @Test
    fun cancelsStreamWithTakeWithoutCrashing() = runBlocking {
        val builder = StringBuilder()
        repeat(400) { builder.append(deltaEvent("一段比较长的内容")) }
        server.enqueue(eventStream(builder.toString()).throttleBody(16, 5, TimeUnit.MILLISECONDS))

        val received = withTimeout(TIMEOUT_MS) {
            OpenAiCompatibleChatApi()
                .stream(config(), requestMessages())
                .take(1)
                .toList()
        }

        assertEquals(1, received.size)
        assertTrue(received.single() is ChatStreamEvent.Delta)
    }

    @Test
    fun ignoresKeepAliveAndNonJsonData() = runBlocking {
        val body = buildString {
            append("data: ping\n\n")
            append(deltaEvent("你"))
            append("data: {}\n\n")
            append(": 注释行\n\n")
            append(deltaEvent("好"))
            append("data: [DONE]\n\n")
        }
        server.enqueue(eventStream(body))

        val events = collectEvents(config())

        assertEquals("你好", events.text())
        assertTrue(events.last() is ChatStreamEvent.Completed)
    }

    @Test
    fun mapsReasoningContentAndUsage() = runBlocking {
        val body = buildString {
            append("""data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null}}]}""")
            append("\n\n")
            append("""data: {"choices":[{"index":0,"delta":{"reasoning_content":"思考中"}}]}""")
            append("\n\n")
            append("""data: {"choices":[{"index":0,"delta":{"content":"答案"}}],"usage":{"prompt_tokens":11,"completion_tokens":22}}""")
            append("\n\n")
            append(DONE_EVENT)
        }
        server.enqueue(eventStream(body))

        val events = collectEvents(config())

        val deltas = events.filterIsInstance<ChatStreamEvent.Delta>()
        assertEquals(listOf<ChatStreamEvent>(ChatStreamEvent.Delta(reasoning = "思考中"), ChatStreamEvent.Delta(content = "答案")), deltas)
        assertEquals(listOf<ChatStreamEvent>(ChatStreamEvent.Usage(11, 22)), events.filterIsInstance<ChatStreamEvent.Usage>())
    }

    @Test
    fun sendsSystemPromptFirstAndOmitsNullTemperature() = runBlocking {
        server.enqueue(eventStream(deltaEvent("ok") + DONE_EVENT))

        collectEvents(config().copy(systemPrompt = "你是助手", temperature = null))

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        requireNotNull(recorded)
        val payload = recorded.body.readUtf8()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(payload, payload.contains("\"stream\":true"))
        assertFalse(payload, payload.contains("temperature"))
        val systemIndex = payload.indexOf("\"system\"")
        val userIndex = payload.indexOf("\"user\"")
        assertTrue(payload, systemIndex in 0 until userIndex)
        assertTrue(payload, payload.contains("你是助手"))
    }

    @Test
    fun emitsFinishedEventWithReason() = runBlocking {
        val body = buildString {
            append(deltaEvent("半"))
            append("""data: {"choices":[{"index":0,"delta":{"content":""},"finish_reason":"length"}]}""")
            append("\n\n")
            append("""data: {"choices":[{"index":0,"delta":{"content":""},"finish_reason":null}],"usage":{"prompt_tokens":41,"completion_tokens":24,"completion_tokens_details":{"reasoning_tokens":24},"prompt_cache_hit_tokens":7}}""")
            append("\n\n")
            append(DONE_EVENT)
        }
        server.enqueue(eventStream(body))

        val events = collectEvents(config())

        assertEquals(listOf<ChatStreamEvent>(ChatStreamEvent.Finished("length")), events.filterIsInstance<ChatStreamEvent.Finished>())
        val usage = events.filterIsInstance<ChatStreamEvent.Usage>().single()
        assertEquals(41, usage.promptTokens)
        assertEquals(24, usage.reasoningTokens)
        assertEquals(7, usage.cachedTokens)
    }

    @Test
    fun sendsImagesAsContentPartsOnlyForUserRole() = runBlocking {
        server.enqueue(eventStream(deltaEvent("ok") + DONE_EVENT))
        val messages = listOf(
            ChatRequestMessage(
                role = "user",
                content = "这是什么？",
                images = listOf(ChatRequestImage(dataUrl = "data:image/jpeg;base64,AAAA", detail = "low")),
            ),
            ChatRequestMessage(
                role = "assistant",
                content = "图片被塞进 assistant 也应被剥离",
                images = listOf(ChatRequestImage(dataUrl = "data:image/jpeg;base64,BBBB")),
            ),
        )

        withTimeout(TIMEOUT_MS) {
            OpenAiCompatibleChatApi().stream(config().copy(reasoningEffort = "low", maxTokens = 128), messages).toList()
        }

        val payload = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue(payload, payload.contains("\"image_url\""))
        assertTrue(payload, payload.contains("data:image/jpeg;base64,AAAA"))
        assertFalse(payload, payload.contains("BBBB"))
        assertTrue(payload, payload.contains("\"reasoning_effort\":\"low\""))
        assertTrue(payload, payload.contains("\"max_tokens\":128"))
        assertTrue(payload, payload.contains("\"stream_options\":{\"include_usage\":true}"))
        assertTrue(payload, payload.contains("\"temperature\""))
    }

    @Test
    fun mergesUserExtraParamsButKeepsProtectedKeys() = runBlocking {
        server.enqueue(eventStream(deltaEvent("ok") + DONE_EVENT))

        withTimeout(TIMEOUT_MS) {
            OpenAiCompatibleChatApi()
                .stream(
                    config().copy(extraParams = """{"top_k":20,"model":"hacked","stream":false}"""),
                    requestMessages(),
                )
                .toList()
        }

        val payload = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue(payload, payload.contains("\"top_k\":20"))
        assertTrue(payload, payload.contains("\"stream\":true"))
        assertFalse(payload, payload.contains("hacked"))
    }

    @Test
    fun mapsRateLimitErrorToFriendlyMessage() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}"""),
        )

        try {
            collectEvents(config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("稍后重试"))
        }
    }

    @Test
    fun listModelsParsesStandardResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"deepseek-flash","object":"model"},{"id":"deepseek-v4-pro","object":"model"}]}"""),
        )

        val models = OpenAiCompatibleChatApi().listModels(config())

        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models)
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/models", recorded.path)
        assertEquals("GET", recorded.method)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun listModelsSurfacesAuthFailure() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Authentication Fails"}}"""),
        )

        try {
            OpenAiCompatibleChatApi().listModels(config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("API Key"))
        }
    }

    private fun config(): ChatConfig = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "",
        temperature = 0.7,
    )

    private fun requestMessages(): List<ChatRequestMessage> = listOf(ChatRequestMessage("user", "你好"))

    private suspend fun collectEvents(config: ChatConfig): List<ChatStreamEvent> =
        withTimeout(TIMEOUT_MS) { OpenAiCompatibleChatApi().stream(config, requestMessages()).toList() }

    private fun List<ChatStreamEvent>.text(): String =
        filterIsInstance<ChatStreamEvent.Delta>().joinToString("") { it.content.orEmpty() }

    private fun eventStream(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun deltaEvent(content: String): String =
        "data: {\"id\":\"chunk\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":${jsonString(content)}},\"finish_reason\":null}]}\n\n"

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val DONE_EVENT = "data: [DONE]\n\n"
    }
}
