package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.ToolCall
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
}
