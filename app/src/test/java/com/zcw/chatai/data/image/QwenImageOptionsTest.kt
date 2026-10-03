package com.zcw.chatai.data.image

import com.zcw.chatai.data.image.qwen.QwenImageOptions
import com.zcw.chatai.data.net.QwenImagePayload
import com.zcw.chatai.data.net.QwenImageRequest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImageOptionsTest {

    @Test
    fun defaultsKeepCurrentSizeBehavior() {
        val options = QwenImageOptions().sanitized()
        assertEquals("1024*1024", options.wireSize(editing = false))
        assertNull(options.wireSize(editing = true))
        assertNull(options.wireExtendMode(editing = false, promptExtend = true))
        assertNull(options.wireThinking(promptExtend = true))
        assertEquals(1, options.count)
        assertFalse(options.watermark)
    }

    @Test
    fun normalizesSizeAndDropsAgentOnEdit() {
        val options = QwenImageOptions(
            size = "1536x1024",
            count = 9,
            negativePrompt = "模糊".repeat(300),
            seed = -1,
            promptExtendMode = "agent",
            enableThinking = true,
        ).sanitized()
        assertEquals("1536*1024", options.size)
        assertEquals(QwenImageOptions.MAX_COUNT, options.count)
        assertEquals(QwenImageOptions.MAX_NEGATIVE, options.negativePrompt.length)
        assertNull(options.seed)
        assertEquals("agent", options.wireExtendMode(editing = false, promptExtend = true))
        assertNull(options.wireExtendMode(editing = true, promptExtend = true))
        assertNull(options.wireExtendMode(editing = false, promptExtend = false))
        assertEquals(true, options.wireThinking(promptExtend = true))
        assertNull(options.wireThinking(promptExtend = false))
    }

    @Test
    fun sizeRulesMatchTheGuide() {
        assertNull(QwenImageOptions.sizeProblem(""))
        assertNull(QwenImageOptions.sizeProblem("2048*2048"))
        assertNull(QwenImageOptions.sizeProblem("2688*1536"))
        assertNull(QwenImageOptions.sizeProblem("1664*928"))
        assertTrue(QwenImageOptions.sizeProblem("256*256")!!.contains("像素"))
        assertTrue(QwenImageOptions.sizeProblem("4096*256")!!.contains("8:1"))
        assertTrue(QwenImageOptions.sizeProblem("abc")!!.contains("宽*高"))
    }

    @Test
    fun payloadWritesExtendModeThinkingAndSeed() {
        val payload = QwenImagePayload.build(
            QwenImageRequest(
                model = "qwen-image-3.0-pro",
                prompt = "一只猫",
                n = 2,
                size = "2048*2048",
                negativePrompt = "模糊",
                promptExtend = true,
                promptExtendMode = "direct",
                enableThinking = false,
                watermark = false,
                seed = 7,
            ),
        )
        val params = payload["parameters"]!!.jsonObject
        assertEquals("2", params["n"]!!.jsonPrimitive.content)
        assertEquals("2048*2048", params["size"]!!.jsonPrimitive.content)
        assertEquals("模糊", params["negative_prompt"]!!.jsonPrimitive.content)
        assertEquals("direct", params["prompt_extend_mode"]!!.jsonPrimitive.content)
        assertFalse(params["enable_thinking"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("7", params["seed"]!!.jsonPrimitive.content)
    }
}
