package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.SearchedImage
import com.zcw.chatai.data.model.ToolCall
import com.zcw.chatai.data.model.ToolKind
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolSource
import com.zcw.chatai.data.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallCodecTest {

    @Test
    fun roundTripsToolCalls() {
        val calls = listOf(ToolCall("call_1", "web_search", "{\"query\":\"x\"}"))
        val encoded = ToolCallCodec.encodeCalls(calls)
        assertEquals(calls, ToolCallCodec.decodeCalls(encoded))
    }

    @Test
    fun emptyCallsEncodeToNull() {
        assertNull(ToolCallCodec.encodeCalls(emptyList()))
        assertTrue(ToolCallCodec.decodeCalls(null).isEmpty())
        assertTrue(ToolCallCodec.decodeCalls("not json").isEmpty())
    }

    @Test
    fun roundTripsToolResultWithSources() {
        val result = ToolResult(
            status = ToolStatus.OK,
            detail = "kotlin",
            sources = listOf(ToolSource("https://a", "A", "snippet", "2026-01-01")),
            text = "Search results",
        )
        assertEquals(result, ToolCallCodec.decodeResult(ToolCallCodec.encodeResult(result)))
    }

    @Test
    fun nullResultStaysNull() {
        assertNull(ToolCallCodec.encodeResult(null))
        assertNull(ToolCallCodec.decodeResult(null))
        assertNull(ToolCallCodec.decodeResult("{"))
    }

    @Test
    fun dropsInvalidCallButKeepsValidOne() {
        val raw = """[{"id":"","name":"web_search","arguments":"{}"},{"id":"call_1","name":"web_search","arguments":"{\"query\":\"x\"}"}]"""
        val decoded = ToolCallCodec.decodeCalls(raw)
        assertEquals(1, decoded.size)
        assertEquals("call_1", decoded.single().id)
    }

    @Test
    fun unknownStatusFallsBackToFailed() {
        val decoded = ToolCallCodec.decodeResult("""{"status":"BOGUS","detail":"d"}""")
        assertEquals(ToolStatus.FAILED, decoded?.status)
    }

    @Test
    fun missingRequiredResultFieldsDecodeToNull() {
        // status 与 detail 都是必填字段，缺失即整条非法（不同于「未知 status」的语义回退）。
        assertNull(ToolCallCodec.decodeResult("""{"detail":"d"}"""))
        assertNull(ToolCallCodec.decodeResult("""{"status":"OK"}"""))
        assertNull(ToolCallCodec.decodeResult("{}"))
    }

    @Test
    fun validJsonWrongShapeIsRejected() {
        assertTrue(ToolCallCodec.decodeCalls("{}").isEmpty())
        assertNull(ToolCallCodec.decodeResult("""{"status":"OK"}"""))
    }

    @Test
    fun blankStringsDecodeToEmptyAndNull() {
        assertTrue(ToolCallCodec.decodeCalls("").isEmpty())
        assertTrue(ToolCallCodec.decodeCalls("   ").isEmpty())
        assertNull(ToolCallCodec.decodeResult(""))
        assertNull(ToolCallCodec.decodeResult("   "))
    }

    @Test
    fun blankUrlSourceIsDropped() {
        val result = ToolResult(
            status = ToolStatus.OK,
            detail = "d",
            sources = listOf(ToolSource(""), ToolSource("https://b", "B")),
            text = "t",
        )
        val decoded = ToolCallCodec.decodeResult(ToolCallCodec.encodeResult(result))
        assertEquals(listOf("https://b"), decoded?.sources?.map { it.url })
    }

    @Test
    fun unknownFutureFieldsAreIgnored() {
        val calls = ToolCallCodec.decodeCalls(
            """[{"id":"a","name":"web_search","arguments":"{}","future":"x"}]""",
        )
        assertEquals(1, calls.size)
        assertEquals("a", calls.single().id)

        val result = ToolCallCodec.decodeResult("""{"status":"OK","detail":"d","future":{"nested":true}}""")
        assertEquals(ToolStatus.OK, result?.status)
        assertEquals("d", result?.detail)
    }

    @Test
    fun pinsEncodedCallJsonFieldNames() {
        val encoded = ToolCallCodec.encodeCalls(listOf(ToolCall("call_1", "web_search", "{\"query\":\"x\"}")))
        assertEquals(
            """[{"id":"call_1","name":"web_search","arguments":"{\"query\":\"x\"}"}]""",
            encoded,
        )
    }

    @Test
    fun pinsEncodedResultJsonFieldNames() {
        val encoded = ToolCallCodec.encodeResult(
            ToolResult(
                status = ToolStatus.OK,
                detail = "kotlin",
                sources = listOf(ToolSource("https://a", "A", "snippet", "2026-01-01")),
                text = "Search results",
            ),
        )
        assertEquals(
            """{"status":"OK","detail":"kotlin","sources":[{"url":"https://a","title":"A","snippet":"snippet","publishedAt":"2026-01-01"}],"text":"Search results","kind":"SEARCH","images":[],"modelNote":null,"backendId":null}""",
            encoded,
        )
    }

    @Test
    fun roundTripsModelNote() {
        val result = ToolResult(
            status = ToolStatus.OK,
            detail = "d",
            text = "t",
            modelNote = "[Tool budget] 1 more tool round(s) available this turn.",
        )
        assertEquals(result, ToolCallCodec.decodeResult(ToolCallCodec.encodeResult(result)))
    }

    @Test
    fun legacyResultWithoutModelNoteDecodesToNull() {
        val decoded = ToolCallCodec.decodeResult("""{"status":"OK","detail":"d","text":"t"}""")
        assertEquals(ToolStatus.OK, decoded?.status)
        assertNull(decoded?.modelNote)
        assertNull(decoded?.backendId)
    }

    @Test
    fun roundTripsBackendId() {
        val result = ToolResult(
            status = ToolStatus.OK,
            detail = "d",
            text = "t",
            backendId = "opencode-go",
        )
        assertEquals(result, ToolCallCodec.decodeResult(ToolCallCodec.encodeResult(result)))
    }

    @Test
    fun roundTripsImageSearchResult() {
        val result = ToolResult(
            status = ToolStatus.OK,
            detail = "科技感封面",
            text = "Image search results",
            kind = ToolKind.IMAGE_SEARCH,
            images = listOf(
                SearchedImage(1, "封面 A", "https://img.example/a.jpg"),
                SearchedImage(2, "封面 B", "https://img.example/b.jpg"),
            ),
        )
        assertEquals(result, ToolCallCodec.decodeResult(ToolCallCodec.encodeResult(result)))
    }

    @Test
    fun legacyResultWithoutKindAndImagesDecodesWithDefaults() {
        // v4 及以前落库的行没有这两个字段：必须解出 SEARCH + 空列表，而不是整条丢弃。
        val decoded = ToolCallCodec.decodeResult(
            """{"status":"OK","detail":"https://a","sources":[{"url":"https://a"}],"text":"page"}""",
        )
        assertEquals(ToolStatus.OK, decoded?.status)
        assertEquals(ToolKind.SEARCH, decoded?.kind)
        assertTrue(decoded!!.images.isEmpty())
    }

    @Test
    fun unknownKindFallsBackToSearchAndBlankImageUrlsAreDropped() {
        val decoded = ToolCallCodec.decodeResult(
            """{"status":"OK","detail":"d","kind":"FUTURE_KIND","images":[{"index":1,"title":"t","url":""},{"index":2,"title":"ok","url":"https://x"}]}""",
        )
        assertEquals(ToolKind.SEARCH, decoded?.kind)
        assertEquals(listOf("https://x"), decoded?.images?.map { it.url })
    }
}
