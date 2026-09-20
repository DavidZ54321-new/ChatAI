package com.zcw.chatai.data.web

import com.zcw.chatai.data.model.SearchedImage
import com.zcw.chatai.data.model.ToolSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsTest {

    @Test
    fun specsExposeAllFunctions() {
        assertEquals(
            listOf("web_search", "web_fetch", "search_images", "find_similar_images"),
            WebTools.specs().map { it.function.name },
        )
    }

    @Test
    fun specsForSelectsByEnabledToolNames() {
        assertEquals(
            listOf("web_search", "web_fetch"),
            WebTools.specsFor(listOf(WebTools.FETCH, WebTools.SEARCH)).map { it.function.name },
        )
        assertEquals(
            listOf("search_images", "find_similar_images"),
            WebTools.specsFor(listOf(WebTools.SEARCH_IMAGES, WebTools.FIND_SIMILAR_IMAGES))
                .map { it.function.name },
        )
        assertTrue(WebTools.specsFor(emptyList()).isEmpty())
        assertTrue(WebTools.specsFor(listOf("unknown_tool")).isEmpty())
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
    fun parsesArgumentsWithEdgeCaseInputs() {
        // 非字符串的 JSON 标量按字面量取用（JsonPrimitive.contentOrNull 会给出 "123"）。
        assertEquals("123", WebTools.queryOf("{\"query\":123}"))
        assertNull(WebTools.queryOf("[1]"))
        assertNull(WebTools.queryOf("{\"query\":null}"))
        assertNull(WebTools.queryOf("{\"query\":\"   \"}"))
        assertNull(WebTools.urlOf("{\"url\":{\"x\":1}}"))
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
    fun formatsEmptySearchResultWithoutCitationHint() {
        val text = WebTools.formatSearchResult("x", WebSearchResult())
        assertTrue(text, text.contains("No results found."))
        assertFalse(text, text.contains("markdown"))
    }

    @Test
    fun capsProviderAnswer() {
        val long = "a".repeat(WebTools.MAX_SEARCH_ANSWER_CHARS + 100)
        val text = WebTools.formatSearchResult("x", WebSearchResult(answer = long))
        assertTrue(text, text.contains("(answer truncated)"))
        assertTrue(text.length <= WebTools.MAX_SEARCH_ANSWER_CHARS + 200)
    }

    @Test
    fun formatsFetchResultAndTruncates() {
        val long = "a".repeat(WebTools.MAX_FETCH_CHARS + 100)
        val text = WebTools.formatFetchResult(WebFetchResult("https://a", 200, long, truncated = false))
        val header = "Fetched https://a (HTTP 200):\n"
        assertTrue(text, text.startsWith(header))
        assertTrue(text, text.contains("[content truncated]"))
        val body = text.removePrefix(header).removeSuffix("\n[content truncated]")
        assertEquals(WebTools.MAX_FETCH_CHARS, body.length)
        assertTrue(body, body.all { it == 'a' })
    }

    @Test
    fun marksShortTextWhenFetcherReportsTruncation() {
        val text = WebTools.formatFetchResult(WebFetchResult("https://a", 200, "short", truncated = true))
        assertTrue(text, text.contains("[content truncated]"))
    }

    @Test
    fun formatsImageSearchResultWithMarkdownHint() {
        val text = WebTools.formatImageSearchResult(
            "科技感封面",
            listOf(
                SearchedImage(1, "封面 A", "https://img.example/a.jpg"),
                SearchedImage(2, "", "https://img.example/b.jpg"),
            ),
        )
        assertTrue(text, text.contains("科技感封面"))
        assertTrue(text, text.contains("https://img.example/a.jpg"))
        assertTrue(text, text.contains("markdown"))
        assertTrue("无标题时退回 image 占位", text.contains("- image — https://img.example/b.jpg"))
    }

    @Test
    fun formatsEmptyImageSearchResult() {
        val text = WebTools.formatImageSearchResult("x", emptyList())
        assertTrue(text, text.contains("No images found"))
    }

    @Test
    fun parsesImageIndexAndRejectsInvalidValues() {
        assertEquals(2, WebTools.imageIndexOf("{\"image_index\":2}"))
        assertEquals(1, WebTools.imageIndexOf("{\"image_index\":\"1\"}"))
        assertNull("缺省 = 用最近一张", WebTools.imageIndexOf("{}"))
        assertNull(WebTools.imageIndexOf(""))
        assertNull(WebTools.imageIndexOf("not json"))
        assertNull("0 不是合法 1-based 序号", WebTools.imageIndexOf("{\"image_index\":0}"))
        assertNull(WebTools.imageIndexOf("{\"image_index\":-1}"))
    }

    @Test
    fun emptyResultNotesTellTheModelHowToRecover() {
        assertTrue(WebTools.emptySearchNote("x").contains("Retry at most once"))
        assertTrue(WebTools.emptyImageSearchNote("x").contains("Do not fabricate image URLs"))
        assertTrue(WebTools.emptyImageSimilarNote().contains("Do not fabricate image URLs"))
    }

    @Test
    fun findSimilarImagesSpecExposesOptionalImageIndex() {
        val spec = WebTools.specs().single { it.function.name == WebTools.FIND_SIMILAR_IMAGES }
        val params = spec.function.parameters.toString()
        assertTrue(params, params.contains("image_index"))
        assertTrue(spec.function.description.contains("[Image 2 | turn 3 1/2]"))
    }
}
