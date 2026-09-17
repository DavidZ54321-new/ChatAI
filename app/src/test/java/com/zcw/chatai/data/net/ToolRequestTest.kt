package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.net.dto.chatJson
import com.zcw.chatai.data.net.dto.ChatCompletionRequest
import com.zcw.chatai.data.net.dto.ChatRequestBody
import com.zcw.chatai.data.net.dto.ChatTool
import com.zcw.chatai.data.net.dto.FunctionSpec
import com.zcw.chatai.data.net.dto.RequestFunctionCall
import com.zcw.chatai.data.net.dto.RequestMessage
import com.zcw.chatai.data.net.dto.RequestToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRequestTest {

    @Test
    fun encodesToolSpecsAndToolChoice() {
        val payload = ChatCompletionRequest(
            model = "m",
            messages = listOf(RequestMessage("user", ChatRequestBody.content("hi", emptyList()))),
            tools = listOf(
                ChatTool(
                    function = FunctionSpec(
                        name = "web_search",
                        description = "search",
                        parameters = buildJsonObject {
                            put("type", "object")
                        },
                    ),
                ),
            ),
            toolChoice = JsonPrimitive("auto"),
        )
        val body = ChatRequestBody.encode(chatJson, payload, null)
        assertTrue(body, body.contains("\"tools\""))
        assertTrue(body, body.contains("\"web_search\""))
        assertTrue(body, body.contains("\"tool_choice\":\"auto\""))
    }

    @Test
    fun encodesAssistantToolCallsAndToolResultMessage() {
        val payload = ChatCompletionRequest(
            model = "m",
            messages = listOf(
                RequestMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        RequestToolCall(
                            id = "call_1",
                            function = RequestFunctionCall("web_search", "{\"query\":\"x\"}"),
                        ),
                    ),
                ),
                RequestMessage(
                    role = "tool",
                    content = ChatRequestBody.content("结果", emptyList()),
                    toolCallId = "call_1",
                ),
            ),
        )
        val body = ChatRequestBody.encode(chatJson, payload, null)
        assertTrue(body, body.contains("\"tool_calls\""))
        assertTrue(body, body.contains("\"call_1\""))
        assertTrue(body, body.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(body, body.contains("\"role\":\"tool\""))
    }

    @Test
    fun carriesReasoningContentOnlyWhenPresent() {
        val payload = ChatCompletionRequest(
            model = "m",
            messages = listOf(RequestMessage("assistant", null, reasoningContent = "想了一下")),
        )
        val body = ChatRequestBody.encode(chatJson, payload, null)
        assertTrue(body, body.contains("\"reasoning_content\":\"想了一下\""))
    }

    @Test
    fun appendedToolsAreNotBlockedByExtraParamsProtection() {
        val payload = ChatCompletionRequest(
            model = "m",
            messages = emptyList(),
            tools = listOf(ChatTool(function = FunctionSpec("web_fetch", "fetch", buildJsonObject { put("type", "object") }))),
        )
        val body = ChatRequestBody.encode(chatJson, payload, """{"temperature":0.5}""")
        assertTrue(body, body.contains("\"web_fetch\""))
        assertTrue(body, body.contains("\"temperature\":0.5"))
        assertFalse(body, body.contains("\"temperature\":null"))
    }
}
