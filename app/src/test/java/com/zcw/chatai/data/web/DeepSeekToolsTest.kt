package com.zcw.chatai.data.web

import com.zcw.chatai.data.web.deepseek.DeepSeekTools
import com.zcw.chatai.data.web.deepseek.DeepSeekToolsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekToolsTest {

    @Test
    fun urlsHangOffTheChatOrigin() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/search",
            DeepSeekTools.searchUrl("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://proxy.example/v1/tools/fetch",
            DeepSeekTools.fetchUrl("https://proxy.example/v1/chat/completions"),
        )
        assertNull(DeepSeekTools.searchUrl("not a url"))
    }

    @Test
    fun codePointCountDoesNotSplitEmoji() {
        val emoji = "\uD83D\uDE00"
        assertEquals(1, DeepSeekTools.codePointCount(emoji))
        assertEquals(2, emoji.length)
    }

    @Test
    fun errorMessageReadsOpenAiEnvelope() {
        val message = DeepSeekToolsParser.errorMessage(
            """{"error":{"message":"Request timeout","type":"gateway_timeout_error","param":null}}""",
        )
        assertEquals("Request timeout", message)
        assertNull(DeepSeekToolsParser.errorMessage("not-json"))
    }

    @Test
    fun parseSearchPrefersSummaryAndDropsBlankUrls() {
        val raw = """
            {
              "results": [
                {
                  "title": "DeepSeek V4",
                  "url": "https://example.com/a",
                  "snippet": "short",
                  "summary": "long summary for the model",
                  "content": "full page that must not leak",
                  "date": "2026-06-01T00:00:00+00:00"
                },
                {"title": "dup", "url": "https://example.com/a", "summary": "again"},
                {"title": "no url", "summary": "skip"}
              ]
            }
        """.trimIndent()
        val result = DeepSeekToolsParser.parseSearch(raw)
        assertEquals(1, result.sources.size)
        val source = result.sources.single()
        assertEquals("https://example.com/a", source.url)
        assertEquals("DeepSeek V4", source.title)
        assertEquals("long summary for the model", source.snippet)
        assertEquals("2026-06-01T00:00:00+00:00", source.publishedAt)
        assertTrue(result.answer == null)
    }

    @Test
    fun emptyOrBrokenSearchIsAnEmptyResult() {
        assertTrue(DeepSeekToolsParser.parseSearch("""{"results":[]}""").sources.isEmpty())
        assertTrue(DeepSeekToolsParser.parseSearch("not-json").sources.isEmpty())
    }

    @Test
    fun parseFetchKeepsTitleAndAllowsEmptyContent() {
        val page = DeepSeekToolsParser.parseFetch("""{"title":"Hello","content":"# Body"}""")
        assertEquals("Hello", page?.title)
        assertEquals("# Body", page?.content)
        assertEquals("", DeepSeekToolsParser.parseFetch("""{"title":"","content":""}""")?.content)
        assertNull(DeepSeekToolsParser.parseFetch("""{"title":"only"}"""))
        assertNull(DeepSeekToolsParser.parseFetch("{"))
    }
}
