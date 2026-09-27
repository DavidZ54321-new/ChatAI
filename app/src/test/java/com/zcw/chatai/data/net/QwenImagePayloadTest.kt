package com.zcw.chatai.data.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImagePayloadTest {

    private fun content(payload: JsonObject): JsonArray =
        payload["input"]!!.jsonObject["messages"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonArray

    @Test
    fun textToImageHasOnlyTextAndSendsParameters() {
        val payload = QwenImagePayload.build(
            QwenImageRequest(
                model = "qwen-image-3.0-pro",
                prompt = "一只猫",
                size = "1024*1024",
                promptExtend = true,
                watermark = false,
            ),
        )
        assertEquals("qwen-image-3.0-pro", payload["model"]!!.jsonPrimitive.content)
        val content = content(payload)
        assertEquals(1, content.size)
        assertEquals("一只猫", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        val params = payload["parameters"]!!.jsonObject
        assertEquals("1024*1024", params["size"]!!.jsonPrimitive.content)
        assertTrue(params["prompt_extend"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(params["watermark"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(params.containsKey("n"))
        assertFalse(params.containsKey("negative_prompt"))
    }

    @Test
    fun editPutsImagesBeforeTextAndOmitsEmptyParameters() {
        val payload = QwenImagePayload.build(
            QwenImageRequest(
                model = "qwen-image-2.0-pro",
                prompt = "把图1改成夜景",
                images = listOf("data:image/png;base64,AAA", "data:image/png;base64,BBB"),
            ),
        )
        val content = content(payload)
        assertEquals(3, content.size)
        assertEquals("data:image/png;base64,AAA", content[0].jsonObject["image"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,BBB", content[1].jsonObject["image"]!!.jsonPrimitive.content)
        assertEquals("把图1改成夜景", content[2].jsonObject["text"]!!.jsonPrimitive.content)
        // 一个可选参数都没给 → 不下发 parameters。
        assertFalse(payload.containsKey("parameters"))
    }

    @Test
    fun omitsBlankOptionalValues() {
        val payload = QwenImagePayload.build(
            QwenImageRequest(model = "m", prompt = "p", n = 1, size = "  ", negativePrompt = ""),
        )
        val params = payload["parameters"]!!.jsonObject
        assertEquals("1", params["n"]!!.jsonPrimitive.content)
        assertFalse(params.containsKey("size"))
        assertFalse(params.containsKey("negative_prompt"))
    }
}
