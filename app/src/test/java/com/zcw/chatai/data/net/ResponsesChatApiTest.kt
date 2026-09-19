package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.web.WebTools
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Responses 主对话的 SSE 翻译（事件向量来自 2026-09 真网关 `grok-4.6` 探针，已脱敏）。
 */
class ResponsesChatApiTest {

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
    fun streamsTextReasoningUsageAndStop() = runBlocking {
        server.enqueue(eventStream(TEXT_STREAM))
        val events = collect(config())

        val text = events.filterIsInstance<ChatStreamEvent.Delta>().joinToString("") { it.content.orEmpty() }
        assertEquals("STREAM_OK", text)
        val reasoning = events.filterIsInstance<ChatStreamEvent.Delta>().joinToString("") { it.reasoning.orEmpty() }
        assertTrue(reasoning, reasoning.contains("thinking"))
        val usage = events.filterIsInstance<ChatStreamEvent.Usage>().single()
        assertEquals(213, usage.promptTokens)
        assertEquals(110, usage.completionTokens)
        assertEquals(107, usage.reasoningTokens)
        assertEquals(128, usage.cachedTokens)
        assertEquals("stop", events.filterIsInstance<ChatStreamEvent.Finished>().single().reason)
        assertTrue(events.last() is ChatStreamEvent.Completed)
    }

    @Test
    fun mapsFunctionCallItemsToToolCallDeltaWithCallId() = runBlocking {
        server.enqueue(eventStream(FUNCTOOL_STREAM))
        val events = collect(config())

        val deltas = events.filterIsInstance<ChatStreamEvent.ToolCallDelta>()
        assertTrue(deltas.isNotEmpty())
        // 配对键必须是 call_id（call-…），不能是条目 id（fc_…），否则下一轮配不上。
        assertEquals("call-abc-0", deltas.first().id)
        assertEquals("get_time", deltas.first().name)
        assertTrue(deltas.all { it.id == null || it.id == "call-abc-0" })
        val assembled = deltas.joinToString("") { it.arguments.orEmpty() }
        assertEquals("{}", assembled)
        assertEquals("tool_calls", events.filterIsInstance<ChatStreamEvent.Finished>().single().reason)
        assertTrue(events.last() is ChatStreamEvent.Completed)
    }

    @Test
    fun postsResponsesPayloadWithoutChatOnlyFields() = runBlocking {
        server.enqueue(eventStream(TEXT_STREAM))
        collect(config())

        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/responses", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertEquals("ChatAI/1.0", recorded.getHeader("User-Agent"))
        val payload = recorded.body.readUtf8()
        assertTrue(payload, payload.contains("\"stream\":true"))
        assertTrue(payload, payload.contains("\"model\":\"grok-4.6\""))
        assertTrue(payload, payload.contains("\"type\":\"input_text\""))
        // chat 面专有字段不得出现（reasoning 发了会 400，max_tokens 会被思维链吃光）。
        assertTrue(payload, !payload.contains("reasoning"))
        assertTrue(payload, !payload.contains("max_tokens"))
        assertTrue(payload, !payload.contains("stream_options"))
    }

    @Test
    fun sendsGatewayHeadersWhenRequired() = runBlocking {
        server.enqueue(eventStream(TEXT_STREAM))
        collect(config().copy(sendSessionHeader = true, sessionId = "conv-9"))
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("conv-9", recorded.getHeader("x-opencode-session"))
    }

    @Test
    fun mapsEndpointUnavailableToSwitchModelMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"type":"server_error","message":"Upstream request failed: Endpoint is unavailable."}}"""),
        )
        try {
            collect(config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("换个模型"))
        }
    }

    @Test
    fun failsWhenConnectionClosesBeforeCompleted() = runBlocking {
        // Responses 契约保证 response.completed；没它就关流是掐断，必须报错不能静默收尾。
        server.enqueue(eventStream(textDelta("half")))
        try {
            collect(config())
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("中断"))
        }
    }

    @Test
    fun refusesVideoTurnWithoutRequest() = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) {
                ResponsesChatApi().stream(
                    config(),
                    listOf(ChatRequestMessage("user", "watch", videos = listOf(ChatRequestVideo("oss://x", true)))),
                ).toList()
            }
            fail("Expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("视频"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun encodesEnabledToolsAsFunctionEnvelope() = runBlocking {
        server.enqueue(eventStream(TEXT_STREAM))
        collect(config().copy(enabledTools = listOf(WebTools.SEARCH, WebTools.FETCH)))
        val payload = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue(payload, payload.contains("\"type\":\"function\""))
        assertTrue(payload, payload.contains("\"name\":\"web_search\""))
        assertTrue(payload, payload.contains("\"tool_choice\":\"auto\""))
    }

    @Test
    fun coalescedDoneItemSuppliesArgumentsWhenNoDeltas() = runBlocking {
        server.enqueue(
            eventStream(
                "event: response.output_item.added\n" +
                    "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"in_progress\",\"name\":\"web_search\",\"call_id\":\"call-x\",\"arguments\":\"\"}}\n\n" +
                    "event: response.output_item.done\n" +
                    "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\",\"name\":\"web_search\",\"call_id\":\"call-x\",\"arguments\":\"{\\\"query\\\":\\\"hi\\\"}\"}}\n\n" +
                    "event: response.completed\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}\n\n",
            ),
        )
        val events = collect(config())
        val deltas = events.filterIsInstance<ChatStreamEvent.ToolCallDelta>()
        assertEquals("call-x", deltas.first().id)
        assertEquals("""{"query":"hi"}""", deltas.joinToString("") { it.arguments.orEmpty() })
        assertEquals("tool_calls", events.filterIsInstance<ChatStreamEvent.Finished>().single().reason)
    }

    @Test
    fun coalescedDoneItemSuppliesTextWhenNoDeltas() = runBlocking {
        server.enqueue(
            eventStream(
                "event: response.output_item.done\n" +
                    "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"full answer\",\"annotations\":[]}]}}\n\n" +
                    "event: response.completed\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}\n\n",
            ),
        )
        val events = collect(config())
        val text = events.filterIsInstance<ChatStreamEvent.Delta>().joinToString("") { it.content.orEmpty() }
        assertEquals("full answer", text)
    }

    private fun config(): ChatConfig = ChatConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "grok-4.6",
        systemPrompt = "",
        temperature = null,
    )

    private suspend fun collect(config: ChatConfig): List<ChatStreamEvent> =
        withTimeout(TIMEOUT_MS) {
            ResponsesChatApi().stream(config, listOf(ChatRequestMessage("user", "hi"))).toList()
        }

    private fun eventStream(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun textDelta(text: String): String =
        "event: response.output_text.delta\n" +
            "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"item_id\":\"msg_1\",\"delta\":${jsonString(text)}}\n\n"

    private fun jsonString(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

    private companion object {
        const val TIMEOUT_MS = 10_000L

        // 脱敏自真网关：reasoning 摘要 + 三段正文 + completed 用量。
        const val TEXT_STREAM =
            "event: response.created\n" +
                "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"status\":\"in_progress\"}}\n\n" +
                "event: response.reasoning_summary_text.delta\n" +
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\",\"delta\":\"thinking: \"}\n\n" +
                "event: response.output_item.added\n" +
                "data: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}\n\n" +
                "event: response.output_text.delta\n" +
                "data: {\"type\":\"response.output_text.delta\",\"output_index\":1,\"content_index\":0,\"item_id\":\"msg_1\",\"delta\":\"STREAM\"}\n\n" +
                "event: response.output_text.delta\n" +
                "data: {\"type\":\"response.output_text.delta\",\"output_index\":1,\"content_index\":0,\"item_id\":\"msg_1\",\"delta\":\"_OK\"}\n\n" +
                "event: response.completed\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":213,\"output_tokens\":110,\"total_tokens\":323,\"input_tokens_details\":{\"cached_tokens\":128},\"output_tokens_details\":{\"reasoning_tokens\":107}}}}\n\n"

        // 脱敏自真网关：function_call 项 + 参数增量 + completed。
        const val FUNCTOOL_STREAM =
            "event: response.created\n" +
                "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_2\",\"status\":\"in_progress\"}}\n\n" +
                "event: response.output_item.added\n" +
                "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_1_0\",\"type\":\"function_call\",\"status\":\"in_progress\",\"name\":\"get_time\",\"call_id\":\"call-abc-0\",\"arguments\":\"\"}}\n\n" +
                "event: response.function_call_arguments.delta\n" +
                "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"item_id\":\"fc_1_0\",\"delta\":\"{}\"}\n\n" +
                "event: response.output_item.done\n" +
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"fc_1_0\",\"type\":\"function_call\",\"status\":\"completed\",\"name\":\"get_time\",\"call_id\":\"call-abc-0\",\"arguments\":\"{}\"}}\n\n" +
                "event: response.completed\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_2\",\"status\":\"completed\",\"usage\":{\"input_tokens\":292,\"output_tokens\":262,\"total_tokens\":554}}}\n\n"
    }
}
