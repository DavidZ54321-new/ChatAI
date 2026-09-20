package com.zcw.chatai.data.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesHistoryTest {

    private fun user(text: String) = ChatRequestMessage(role = "user", content = text)

    @Test
    fun mapsSystemAndPlainTurnsToInputItems() {
        val result = ResponsesHistory.translate(
            systemPrompt = "Be brief.",
            messages = listOf(
                user("hi"),
                ChatRequestMessage(role = "assistant", content = "hello"),
            ),
        ) as ResponsesHistory.Result.Ok
        val input = result.input
        assertEquals("system", input[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user", input[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("assistant", input[2].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        val text = input[2].jsonObject["content"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("output_text", text?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("hello", text?.get("text")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun mapsAssistantToolCallsToFunctionCallItemsAndToolResultToOutput() {
        val result = ResponsesHistory.translate(
            systemPrompt = "",
            messages = listOf(
                user("search x"),
                ChatRequestMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        com.zcw.chatai.data.model.ToolCall(id = "call-1", name = "web_search", arguments = """{"query":"x"}"""),
                    ),
                ),
                ChatRequestMessage(role = "tool", content = "result text", toolCallId = "call-1"),
            ),
        ) as ResponsesHistory.Result.Ok
        val types = result.input.map {
            val obj = it.jsonObject
            obj["type"]?.jsonPrimitive?.contentOrNull ?: obj["role"]?.jsonPrimitive?.contentOrNull
        }
        assertEquals(listOf("user", "function_call", "function_call_output"), types)
        val call = result.input[1].jsonObject
        assertEquals("call-1", call["call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("web_search", call["name"]?.jsonPrimitive?.contentOrNull)
        val output = result.input[2].jsonObject
        assertEquals("call-1", output["call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("result text", output["output"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun mapsImagesWithLabelsToInputImageParts() {
        val result = ResponsesHistory.translate(
            systemPrompt = "",
            messages = listOf(
                ChatRequestMessage(
                    role = "user",
                    content = "look",
                    images = listOf(
                        ChatRequestImage(
                            dataUrl = "data:image/jpeg;base64,AAA",
                            detail = "low",
                            label = "[Image 1 | turn 1 1/1]",
                        ),
                    ),
                ),
            ),
        ) as ResponsesHistory.Result.Ok
        val parts = result.input[0].jsonObject["content"]?.jsonArray ?: JsonArray(emptyList())
        val kinds = parts.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf("input_text", "input_text", "input_image"), kinds)
        val image = parts[2].jsonObject
        assertEquals("data:image/jpeg;base64,AAA", image["image_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("low", image["detail"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun refusesVideoTurns() {
        val result = ResponsesHistory.translate(
            systemPrompt = "",
            messages = listOf(
                ChatRequestMessage(
                    role = "user",
                    content = "watch",
                    videos = listOf(ChatRequestVideo(url = "oss://x", isOss = true)),
                ),
            ),
        )
        assertTrue(result is ResponsesHistory.Result.HasVideo)
    }

    @Test
    fun keepsTextAssistantContentAlongsideFunctionCalls() {
        val result = ResponsesHistory.translate(
            systemPrompt = "",
            messages = listOf(
                ChatRequestMessage(
                    role = "assistant",
                    content = "let me check",
                    toolCalls = listOf(
                        com.zcw.chatai.data.model.ToolCall(id = "c1", name = "web_fetch", arguments = "{}"),
                    ),
                ),
            ),
        ) as ResponsesHistory.Result.Ok
        assertTrue(result.input.size == 2)
        assertTrue(result.input[0].jsonObject["content"] is JsonArray)
        assertEquals("function_call", result.input[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }
}
