package com.zcw.chatai.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImageParserTest {

    @Test
    fun parsesSuccessWithEditUsageKeys() {
        val raw = """
            {"output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant",
            "content":[{"image":"https://x/a.png"},{"image":"https://x/b.png"}]}}]},
            "usage":{"width":2048,"height":1024,"image_count":2},"request_id":"r"}
        """.trimIndent()
        val outcome = QwenImageParser.parse(raw)
        assertTrue(outcome is QwenImageOutcome.Success)
        val success = outcome as QwenImageOutcome.Success
        assertEquals(listOf("https://x/a.png", "https://x/b.png"), success.urls)
        assertEquals(2048, success.width)
        assertEquals(1024, success.height)
        assertEquals(2, success.imageCount)
    }

    /** 文生图同步接口用的是另一套 usage 键名（output_width/…），也要认。 */
    @Test
    fun parsesSuccessWithTextToImageUsageKeys() {
        val raw = """
            {"output":{"choices":[{"message":{"content":[{"image":"https://x/a.png"}]}}]},
            "usage":{"output_width":1024,"output_height":768,"output_image_count":1}}
        """.trimIndent()
        val success = QwenImageParser.parse(raw) as QwenImageOutcome.Success
        assertEquals(1024, success.width)
        assertEquals(768, success.height)
        assertEquals(1, success.imageCount)
    }

    @Test
    fun parsesTopLevelFailure() {
        val raw = """{"request_id":"r","code":"InvalidApiKey","message":"Invalid API-key provided."}"""
        val failure = QwenImageParser.parse(raw) as QwenImageOutcome.Failure
        assertEquals("InvalidApiKey", failure.code)
        assertEquals("Invalid API-key provided.", failure.message)
    }

    @Test
    fun malformedJsonIsMalformed() {
        assertEquals(QwenImageOutcome.Malformed, QwenImageParser.parse("<html>502 Bad Gateway</html>"))
        assertEquals(QwenImageOutcome.Malformed, QwenImageParser.parse(""))
    }

    @Test
    fun emptyContentIsFailure() {
        val raw = """{"output":{"choices":[{"message":{"role":"assistant","content":[]}}]},"usage":{}}"""
        val failure = QwenImageParser.parse(raw) as QwenImageOutcome.Failure
        assertNull(failure.code)
    }

    @Test
    fun skipsContentItemsWithoutImage() {
        val raw = """{"output":{"choices":[{"message":{"content":[{"text":"hi"},{"image":"https://x/a.png"}]}}]}}"""
        val success = QwenImageParser.parse(raw) as QwenImageOutcome.Success
        assertEquals(listOf("https://x/a.png"), success.urls)
        // 缺 usage 时张数按 URL 数补齐。
        assertEquals(1, success.imageCount)
        assertEquals(0, success.width)
    }
}
