package com.zcw.chatai.data.ai

import com.zcw.chatai.data.net.ChatStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallAccumulatorTest {

    @Test
    fun assemblesFragmentedArgumentsAcrossChunks() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, id = "call_1", name = "web_search", arguments = "{\"qu"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "ery\":\"kot"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "lin\"}"))
        val calls = acc.assemble()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("web_search", calls[0].name)
        assertEquals("{\"query\":\"kotlin\"}", calls[0].arguments)
    }

    @Test
    fun keepsParallelCallsOrderedByIndexWithOwnArguments() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(1, id = "b", name = "web_fetch", arguments = "{\"url\":\"h"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, id = "a", name = "web_search", arguments = "{\"query\":\"k"))
        acc.accept(ChatStreamEvent.ToolCallDelta(1, arguments = "ttps://a\"}"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "otlin\"}"))
        val calls = acc.assemble()
        assertEquals(listOf("a", "b"), calls.map { it.id })
        assertEquals("{\"query\":\"kotlin\"}", calls[0].arguments)
        assertEquals("{\"url\":\"https://a\"}", calls[1].arguments)
    }

    @Test
    fun keepsIdAndNameArrivingAfterArguments() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "{\"query\":\"kot"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "lin\"}"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, id = "call_9", name = "web_search"))
        val call = acc.assemble().single()
        assertEquals("call_9", call.id)
        assertEquals("web_search", call.name)
        assertEquals("{\"query\":\"kotlin\"}", call.arguments)
    }

    @Test
    fun synthesizesIdWhenMissing() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, name = "web_search", arguments = "{}"))
        assertEquals("synth_call_0", acc.assemble().single().id)
    }

    @Test
    fun assembleIsIdempotent() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, id = "call_1", name = "web_search", arguments = "{\"query\":\"x\"}"))
        acc.accept(ChatStreamEvent.ToolCallDelta(1, id = "call_2", name = "web_fetch", arguments = "{}"))
        assertEquals(acc.assemble(), acc.assemble())
    }

    @Test
    fun ignoresNamelessPartials() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "{}"))
        assertTrue(acc.assemble().isEmpty())
        assertTrue(acc.isEmpty)
    }
}
