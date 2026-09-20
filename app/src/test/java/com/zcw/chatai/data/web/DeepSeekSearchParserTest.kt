package com.zcw.chatai.data.web

import com.zcw.chatai.data.web.deepseek.DeepSeekSearchParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekSearchParserTest {

    @Test
    fun extractsAnswerAndSources() {
        val body = """
            {"content":[
              {"type":"text","text":"答案是 42。"},
              {"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"x"}},
              {"type":"web_search_tool_result","tool_use_id":"s1","content":[
                 {"type":"web_search_result","url":"https://a","title":"A","page_age":"2026-01-01"},
                 {"type":"web_search_result","url":"https://a","title":"A dup"},
                 {"type":"web_search_result","url":"https://b","title":"B"}
              ]}
            ]}
        """.trimIndent()
        val result = DeepSeekSearchParser.parse(body)
        assertEquals("答案是 42。", result.answer)
        assertEquals(listOf("https://a", "https://b"), result.sources.map { it.url })
        assertEquals("2026-01-01", result.sources[0].publishedAt)
    }

    @Test
    fun stripsDsmlMarkup() {
        val body = """{"content":[{"type":"text","text":"前 <\uFF5C\uFF5CDSML\uFF5C\uFF5Ctool_calls>{\"a\":1}<\uFF5C\uFF5C/DSML\uFF5C\uFF5Ctool_calls> 后"}]}"""
        val result = DeepSeekSearchParser.parse(body)
        assertFalse(result.answer.orEmpty(), result.answer.orEmpty().contains("DSML"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("前"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("后"))
    }

    @Test
    fun stripsLoneCloseMarker() {
        val body = """{"content":[{"type":"text","text":"前 <\uFF5C\uFF5C/DSML\uFF5C\uFF5Ctool_calls> 后"}]}"""
        val result = DeepSeekSearchParser.parse(body)
        assertFalse(result.answer.orEmpty(), result.answer.orEmpty().contains("DSML"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("前"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("后"))
    }

    @Test
    fun stripsMultipleDsmlPairs() {
        val body = """{"content":[{"type":"text","text":"A <\uFF5C\uFF5CDSML\uFF5C\uFF5Ctool_calls>x<\uFF5C\uFF5C/DSML\uFF5C\uFF5Ctool_calls> B <\uFF5C\uFF5CDSML\uFF5C\uFF5Ctool_calls>y<\uFF5C\uFF5C/DSML\uFF5C\uFF5Ctool_calls> C"}]}"""
        val result = DeepSeekSearchParser.parse(body)
        assertFalse(result.answer.orEmpty(), result.answer.orEmpty().contains("DSML"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("A"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("B"))
        assertTrue(result.answer.orEmpty(), result.answer.orEmpty().contains("C"))
    }

    @Test
    fun extractsSourcesFromRealAnthropicBodyWithThinkingAndToolUse() {
        val body = """
            {"id":"x","type":"message","role":"assistant","model":"deepseek-flash","content":[
              {"type":"thinking","thinking":"The user wants a web search.","signature":"abc123"},
              {"type":"server_tool_use","id":"call_00_1","name":"web_search","input":{"query":"DeepSeek V4 Flash"},"caller":{"type":"direct"}},
              {"type":"web_search_tool_result","tool_use_id":"call_00_1","content":[
                 {"type":"web_search_result","title":"DeepSeek-V4 Preview","url":"https://www.deepseek.com/en/news/v4-preview/","encrypted_content":"LONG_BASE64_STRING"}
              ]}
            ]}
        """.trimIndent()
        val result = DeepSeekSearchParser.parse(body)
        assertEquals(1, result.sources.size)
        assertEquals("https://www.deepseek.com/en/news/v4-preview/", result.sources[0].url)
        assertEquals("DeepSeek-V4 Preview", result.sources[0].title)
    }

    @Test
    fun emptyContentGivesEmptyResult() {
        val result = DeepSeekSearchParser.parse("""{"content":[]}""")
        assertEquals(null, result.answer)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun malformedBodyDoesNotThrow() {
        val result = DeepSeekSearchParser.parse("not json")
        assertTrue(result.sources.isEmpty())
    }
}
