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
    fun keepsParallelCallsOrderedByIndex() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(1, id = "b", name = "web_fetch", arguments = "{}"))
        acc.accept(ChatStreamEvent.ToolCallDelta(0, id = "a", name = "web_search", arguments = "{}"))
        assertEquals(listOf("a", "b"), acc.assemble().map { it.id })
    }

    @Test
    fun synthesizesIdWhenMissing() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, name = "web_search", arguments = "{}"))
        assertEquals("call_0", acc.assemble().single().id)
    }

    @Test
    fun ignoresNamelessPartials() {
        val acc = ToolCallAccumulator()
        acc.accept(ChatStreamEvent.ToolCallDelta(0, arguments = "{}"))
        assertTrue(acc.assemble().isEmpty())
        assertTrue(acc.isEmpty)
    }
}
