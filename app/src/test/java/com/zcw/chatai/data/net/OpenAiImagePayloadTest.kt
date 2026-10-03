package com.zcw.chatai.data.net

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAiImagePayloadTest {

    @Test
    fun generationsSendsSquareSizeAndOmitsLegacyFormat() {
        val payload = OpenAiImagePayload.generations("gpt-image-2.5-flare", "一只猫")
        assertEquals("gpt-image-2.5-flare", payload["model"]!!.jsonPrimitive.content)
        assertEquals("一只猫", payload["prompt"]!!.jsonPrimitive.content)
        assertEquals(1, payload["n"]!!.jsonPrimitive.content.toInt())
        assertEquals("1024x1024", payload["size"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("images"))
        assertFalse(payload.containsKey("response_format"))
        assertFalse(payload.containsKey("quality"))
        assertFalse(payload.containsKey("output_format"))
    }

    @Test
    fun editsKeepsImageOrderAndUsesAutoSize() {
        val payload = OpenAiImagePayload.edits(
            model = "gpt-image-2.5-sunburst",
            prompt = "换成夜景",
            images = listOf("data:image/png;base64,YQ==", "data:image/png;base64,Yg=="),
        )
        assertEquals("auto", payload["size"]!!.jsonPrimitive.content)
        val images = payload["images"]!!.jsonArray
        assertEquals(2, images.size)
        assertEquals("data:image/png;base64,YQ==", images[0].jsonObject["image_url"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,Yg==", images[1].jsonObject["image_url"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("mask"))
        assertFalse(payload.containsKey("response_format"))
    }
}
