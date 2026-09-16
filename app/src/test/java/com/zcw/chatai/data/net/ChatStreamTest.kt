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
    fun surfacesHttpErrorBody() = runBlocking {
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
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("Invalid API key"))
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
