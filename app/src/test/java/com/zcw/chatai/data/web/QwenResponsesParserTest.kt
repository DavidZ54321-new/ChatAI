package com.zcw.chatai.data.web

import com.zcw.chatai.data.web.qwen.QwenResponsesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenResponsesParserTest {

    private val realistic = """
        {
          "id": "resp_1",
          "output": [
            {"type": "reasoning", "summary": []},
            {
              "type": "web_search_call",
              "id": "ws_1",
              "status": "completed",
              "action": {
                "type": "search",
                "query": "杭州天气",
                "queries": ["杭州天气"],
                "sources": [
                  {"type": "url", "url": "https://a.example/1"},
                  {"type": "url", "url": "https://b.example/2"},
                  {"type": "url", "url": ""}
                ]
              }
            },
            {"type": "reasoning", "summary": []},
            {
              "type": "web_search_call",
              "id": "ws_2",
              "status": "completed",
              "action": {
                "type": "search",
                "query": "杭州 明天",
                "sources": [
                  {"type": "url", "url": "https://a.example/1"},
                  {"type": "url", "url": "https://c.example/3", "title": "C"}
                ]
              }
            },
            {
              "type": "message",
              "id": "msg_1",
              "role": "assistant",
              "status": "completed",
              "content": [
                {"type": "output_text", "text": "杭州明天多云转晴。", "annotations": []}
              ]
            }
          ],
          "usage": {
            "input_tokens": 100,
            "output_tokens": 20,
            "x_tools": {"web_search": {"count": 2}}
          }
        }
    """.trimIndent()

    @Test
    fun extractsAnswerSourcesAndDeduplicates() {
        val result = QwenResponsesParser.parseTextSearch(realistic)
        assertEquals("杭州明天多云转晴。", result.answer)
        assertEquals(listOf("https://a.example/1", "https://b.example/2", "https://c.example/3"), result.sources.map { it.url })
        assertNull(result.sources[0].title)
        assertEquals("C", result.sources[2].title)
    }

    @Test
    fun readsToolCallCountFromUsage() {
        assertEquals(2, QwenResponsesParser.toolCallCount(realistic, "web_search"))
        assertNull(QwenResponsesParser.toolCallCount(realistic, "image_search"))
        assertNull(QwenResponsesParser.toolCallCount("{}", "web_search"))
    }

    @Test
    fun takesTheLastMessageWhenOutputOrderIsNotGuaranteed() {
        val raw = """
            {"output":[
              {"type":"message","content":[{"type":"output_text","text":"first"}]},
              {"type":"image_search_call","output":"[]"},
              {"type":"message","content":[{"type":"output_text","text":"final answer"}]}
            ]}
        """.trimIndent()
        assertEquals("final answer", QwenResponsesParser.parseTextSearch(raw).answer)
    }

    @Test
    fun malformedStructuresDegradeToEmpty() {
        assertEquals(WebSearchResult(), QwenResponsesParser.parseTextSearch("not json"))
        assertEquals(WebSearchResult(), QwenResponsesParser.parseTextSearch("{}"))
        assertEquals(WebSearchResult(), QwenResponsesParser.parseTextSearch("""{"output":"nope"}"""))
        assertEquals(WebSearchResult(), QwenResponsesParser.parseTextSearch("""{"output":[1,"x",null]}"""))
        assertEquals(
            WebSearchResult(),
            QwenResponsesParser.parseTextSearch("""{"output":[{"type":"web_search_call","action":{"sources":"nope"}}]}"""),
        )
    }

    @Test
    fun ignoresBlankAnnotationsAndNonTextParts() {
        val raw = """
            {"output":[
              {"type":"message","content":[
                {"type":"output_text","text":"   "},
                {"type":"refusal","text":"no"},
                {"type":"output_text","text":"ok"}
              ]},
              {"type":"web_search_call","action":{"type":"search","sources":{"not":"array"}}}
            ]}
        """.trimIndent()
        val result = QwenResponsesParser.parseTextSearch(raw)
        assertEquals("ok", result.answer)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun parsesImageCallOutputJsonString() {
        val raw = """
            {"output":[
              {"type":"web_search_image_call","status":"completed","output":"[{\"index\":1,\"title\":\"封面 A\",\"url\":\"https://img.example/a\"},{\"index\":2,\"title\":\"封面 B\",\"url\":\"https://img.example/b\"}]"},
              {"type":"message","content":[{"type":"output_text","text":"找到了两张。"}]}
            ]}
        """.trimIndent()
        val images = QwenResponsesParser.parseImages(
            raw,
            callTypes = listOf("web_search_image_call", "image_search_call"),
        )
        assertEquals(listOf("https://img.example/a", "https://img.example/b"), images.map { it.url })
        assertEquals(listOf(1, 2), images.map { it.index })
        assertEquals("封面 A", images.first().title)
        assertEquals("找到了两张。", QwenResponsesParser.lastMessageText(raw))
    }

    @Test
    fun mergesBothImageCallTypesAndDeduplicatesByUrl() {
        val raw = """
            {"output":[
              {"type":"message","content":[{"type":"output_text","text":"first"}]},
              {"type":"image_search_call","output":"[{\"index\":1,\"title\":\"A\",\"url\":\"https://a\"}]"},
              {"type":"web_search_image_call","output":"[{\"index\":1,\"title\":\"A again\",\"url\":\"https://a\"},{\"index\":2,\"title\":\"B\",\"url\":\"https://b\"}]"},
              {"type":"message","content":[{"type":"output_text","text":"last"}]}
            ]}
        """.trimIndent()
        val images = QwenResponsesParser.parseImages(
            raw,
            callTypes = listOf("web_search_image_call", "image_search_call"),
        )
        assertEquals(listOf("https://a", "https://b"), images.map { it.url })
        assertEquals("last", QwenResponsesParser.lastMessageText(raw))
    }

    @Test
    fun malformedImageOutputsDegradeToEmpty() {
        val types = listOf("web_search_image_call", "image_search_call")
        assertTrue(QwenResponsesParser.parseImages("not json", types).isEmpty())
        assertTrue(
            QwenResponsesParser.parseImages(
                """{"output":[{"type":"image_search_call","output":"not json"}]}""",
                types,
            ).isEmpty(),
        )
        assertTrue(
            QwenResponsesParser.parseImages(
                """{"output":[{"type":"image_search_call","output":"{\"not\":\"array\"}"}]}""",
                types,
            ).isEmpty(),
        )
        val partial = QwenResponsesParser.parseImages(
            """{"output":[{"type":"image_search_call","output":"[{\"title\":\"no url\"},{\"url\":\"https://ok\"},1]"}]}""",
            types,
        )
        assertEquals(listOf("https://ok"), partial.map { it.url })
        assertEquals(1, partial.single().index)
    }

    @Test
    fun lastMessageTextIgnoresToolCallsAndReasoning() {
        assertNull(QwenResponsesParser.lastMessageText("not json"))
        assertNull(QwenResponsesParser.lastMessageText("""{"output":[{"type":"reasoning"}]}"""))
    }
}
