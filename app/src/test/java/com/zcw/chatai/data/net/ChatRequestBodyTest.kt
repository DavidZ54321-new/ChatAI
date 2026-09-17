package com.zcw.chatai.data.net

import com.zcw.chatai.data.net.dto.ChatCompletionRequest
import com.zcw.chatai.data.net.dto.ChatRequestBody
import com.zcw.chatai.data.net.dto.RequestMessage
import com.zcw.chatai.data.net.dto.chatJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestBodyTest {

    private val json = chatJson

    private fun encode(extra: String? = null, images: List<ChatRequestImage> = emptyList()): String {
        val payload = ChatCompletionRequest(
            model = "deepseek-flash",
            messages = listOf(
                RequestMessage("user", ChatRequestBody.content("你好", images)),
            ),
        )
        return ChatRequestBody.encode(json, payload, extra)
    }

    private fun bodyOf(encoded: String): JsonObject = Json.parseToJsonElement(encoded).jsonObject

    @Test
    fun plainTextIsEncodedAsJsonStringNotArray() {
        val content = bodyOf(encode()).getValue("messages").jsonArray[0].jsonObject.getValue("content")
        assertTrue(content.toString(), content is kotlinx.serialization.json.JsonPrimitive)
        assertEquals("你好", content.jsonPrimitive.content)
    }

    @Test
    fun imagesBecomeStandardOpenAiContentParts() {
        val encoded = encode(
            images = listOf(ChatRequestImage(dataUrl = "data:image/jpeg;base64,AAAA", detail = "low")),
        )
        val parts = bodyOf(encoded).getValue("messages").jsonArray[0].jsonObject
            .getValue("content").jsonArray
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("你好", parts[0].jsonObject.getValue("text").jsonPrimitive.content)

        val image = parts[1].jsonObject
        assertEquals("image_url", image.getValue("type").jsonPrimitive.content)
        val imageUrl = image.getValue("image_url").jsonObject
        assertEquals("data:image/jpeg;base64,AAAA", imageUrl.getValue("url").jsonPrimitive.content)
        assertEquals("low", imageUrl.getValue("detail").jsonPrimitive.content)
    }

    @Test
    fun imageWithoutDetailOmitsTheField() {
        val encoded = encode(images = listOf(ChatRequestImage(dataUrl = "data:image/png;base64,BBBB")))
        val imageUrl = bodyOf(encoded).getValue("messages").jsonArray[0].jsonObject
            .getValue("content").jsonArray[1].jsonObject.getValue("image_url").jsonObject
        assertFalse(imageUrl.toString(), imageUrl.containsKey("detail"))
    }

    @Test
    fun imageOnlyMessageHasNoEmptyTextPart() {
        val payload = ChatCompletionRequest(
            model = "deepseek-flash",
            messages = listOf(
                RequestMessage(
                    "user",
                    ChatRequestBody.content("", listOf(ChatRequestImage(dataUrl = "data:image/png;base64,BBBB"))),
                ),
            ),
        )
        val parts = bodyOf(ChatRequestBody.encode(json, payload, null))
            .getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray
        assertEquals(1, parts.size)
        assertEquals("image_url", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun extraParamsAreMergedIntoTopLevelBody() {
        val body = bodyOf(encode(extra = """{"top_k":20,"thinking":{"type":"disabled"}}"""))
        assertEquals(20, body.getValue("top_k").jsonPrimitive.content.toInt())
        assertTrue(body.getValue("thinking").jsonObject.containsKey("type"))
    }

    @Test
    fun extraParamsCannotOverrideProtectedKeys() {
        val body = bodyOf(
            encode(extra = """{"model":"hacked","stream":false,"messages":[],"temperature":0.2}"""),
        )
        assertEquals("deepseek-flash", body.getValue("model").jsonPrimitive.content)
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
        assertEquals(1, body.getValue("messages").jsonArray.size)
        assertEquals("0.2", body.getValue("temperature").jsonPrimitive.content)
    }

    @Test
    fun invalidExtraParamsAreIgnored() {
        val body = bodyOf(encode(extra = "{not json"))
        assertEquals("deepseek-flash", body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun nonObjectExtraParamsAreIgnored() {
        val body = bodyOf(encode(extra = "[1,2,3]"))
        assertEquals("deepseek-flash", body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun nullFieldsAreOmitted() {
        val body = bodyOf(encode())
        assertFalse(body.toString(), body.containsKey("temperature"))
        assertFalse(body.toString(), body.containsKey("reasoning_effort"))
        assertFalse(body.toString(), body.containsKey("max_tokens"))
    }
}
