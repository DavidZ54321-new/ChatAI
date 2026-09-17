package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsTest {

    @Test
    fun specsExposeBothFunctions() {
        assertEquals(listOf("web_search", "web_fetch"), WebTools.specs().map { it.function.name })
    }

    @Test
    fun parsesQueryAndUrlArguments() {
        assertEquals("kotlin 协程", WebTools.queryOf("{\"query\":\"kotlin 协程\"}"))
        assertEquals("https://a.com", WebTools.urlOf("{\"url\":\"https://a.com\"}"))
        assertNull(WebTools.queryOf("{}"))
        assertNull(WebTools.urlOf("not json"))
        assertNull(WebTools.queryOf(""))
    }

    @Test
    fun formatsSearchResultWithHeadingAndSources() {
        val text = WebTools.formatSearchResult(
            "kotlin",
            WebSearchResult(
                answer = "Kotlin 是…",
                sources = listOf(ToolSource("https://a", "A", "快照")),
            ),
        )
        assertTrue(text, text.contains("kotlin"))
        assertTrue(text, text.contains("https://a"))
        assertTrue(text, text.contains("A"))
        assertTrue(text, text.contains("markdown"))
    }

    @Test
    fun formatsEmptySearchResult() {
        val text = WebTools.formatSearchResult("x", WebSearchResult())
        assertTrue(text, text.contains("No results found."))
    }

    @Test
    fun formatsFetchResultAndTruncates() {
        val long = "a".repeat(WebTools.MAX_FETCH_CHARS + 100)
        val text = WebTools.formatFetchResult(WebFetchResult("https://a", 200, long, truncated = false))
        assertTrue(text.length <= WebTools.MAX_FETCH_CHARS + 200)
        assertTrue(text, text.contains("https://a"))
    }
}
