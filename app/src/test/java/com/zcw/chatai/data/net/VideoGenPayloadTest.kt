package com.zcw.chatai.data.net

import com.zcw.chatai.data.video.VideoGenRequest
import com.zcw.chatai.data.video.VideoInputRef
import com.zcw.chatai.data.video.VideoMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGenPayloadTest {

    private fun ref(id: String) = VideoInputRef(id = id, relativePath = "attachments/c/$id.png", mimeType = "image/png")

    private fun request(
        mode: VideoMode,
        prompt: String = "一只猫在草地上跑",
        ratio: String = "adaptive",
        first: VideoInputRef? = null,
        last: VideoInputRef? = null,
        references: List<VideoInputRef> = emptyList(),
    ) = VideoGenRequest(
        mode = mode,
        prompt = prompt,
        model = "wan3.0-video",
        resolution = "720P",
        ratio = ratio,
        duration = 5,
        audio = true,
        watermark = false,
        promptExtend = true,
        firstFrame = first,
        lastFrame = last,
        references = references,
    )

    private fun media(payload: JsonObject): JsonArray =
        payload["input"]!!.jsonObject["media"]!!.jsonArray

    @Test
    fun textToVideoHasNoMediaAndSendsParameters() {
        val payload = VideoGenPayload.build(request(VideoMode.T2V, ratio = "16:9")) { "unused" }
        assertEquals("wan3.0-video", payload["model"]!!.jsonPrimitive.content)
        assertEquals("一只猫在草地上跑", payload["input"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertFalse(payload["input"]!!.jsonObject.containsKey("media"))
        val params = payload["parameters"]!!.jsonObject
        assertEquals("720P", params["resolution"]!!.jsonPrimitive.content)
        assertEquals("16:9", params["ratio"]!!.jsonPrimitive.content)
        assertEquals("5", params["duration"]!!.jsonPrimitive.content)
        assertTrue(params["prompt_extend"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(params["watermark"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(params["audio"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun textToVideoOmitsAdaptiveRatio() {
        val payload = VideoGenPayload.build(request(VideoMode.T2V, ratio = "adaptive")) { "unused" }
        val params = payload["parameters"]!!.jsonObject
        assertFalse(params.containsKey("ratio"))
    }

    @Test
    fun imageToVideoKeepsAdaptiveRatio() {
        val payload = VideoGenPayload.build(request(VideoMode.I2V, ratio = "adaptive", first = ref("a"))) { "u" }
        val params = payload["parameters"]!!.jsonObject
        assertEquals("adaptive", params["ratio"]!!.jsonPrimitive.content)
    }

    @Test
    fun imageToVideoOrdersFirstFrameThenLastFrame() {
        val payload = VideoGenPayload.build(
            request(VideoMode.I2V, first = ref("a"), last = ref("b")),
        ) { r -> "data:image/png;base64,${r.id}" }
        val media = media(payload)
        assertEquals(2, media.size)
        assertEquals("first_frame", media[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,a", media[0].jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("last_frame", media[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun imageToVideoWithOnlyFirstFrame() {
        val payload = VideoGenPayload.build(request(VideoMode.I2V, first = ref("a"))) { "u" }
        val media = media(payload)
        assertEquals(1, media.size)
        assertEquals("first_frame", media[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun referenceVideoUsesReferenceImageForEachInput() {
        val payload = VideoGenPayload.build(
            request(VideoMode.R2V, references = listOf(ref("a"), ref("b"), ref("c"))),
        ) { r -> "url-${r.id}" }
        val media = media(payload)
        assertEquals(3, media.size)
        assertTrue(media.all { it.jsonObject["type"]!!.jsonPrimitive.content == "reference_image" })
        assertEquals("url-a", media[0].jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun unresolvedMediaIsDropped() {
        val payload = VideoGenPayload.build(
            request(VideoMode.I2V, first = ref("a"), last = ref("b")),
        ) { r -> if (r.id == "a") "url-a" else null }
        val media = media(payload)
        assertEquals(1, media.size)
        assertEquals("first_frame", media[0].jsonObject["type"]!!.jsonPrimitive.content)
    }
}
