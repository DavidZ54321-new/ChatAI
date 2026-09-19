package com.zcw.chatai.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ToolFallbackChainTest {

    @Test
    fun returnsFirstNonEmptyAndStops() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ToolFallbackChain.firstUsable(
            candidates = listOf("a", "b", "c"),
            isEmpty = { it == "empty" },
        ) { candidate ->
            calls += candidate
            if (candidate == "b") "ok" else "empty"
        }
        assertEquals("ok", result)
        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun fallsBackToNextWhenCandidateThrows() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ToolFallbackChain.firstUsable(
            candidates = listOf("a", "b"),
            isEmpty = { false },
        ) { candidate ->
            calls += candidate
            if (candidate == "a") throw IllegalStateException("boom") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun allEmptyReturnsLastSuccessSoCallerCanReportNoResults() = runBlocking {
        val result = ToolFallbackChain.firstUsable(
            candidates = listOf("a", "b"),
            isEmpty = { true },
        ) { candidate -> "empty-$candidate" }
        assertEquals("empty-b", result)
    }

    @Test
    fun allErrorsThrowsTheLastOne() = runBlocking {
        try {
            ToolFallbackChain.firstUsable(candidates = listOf("a", "b"), isEmpty = { false }) { candidate ->
                throw IllegalStateException("err-$candidate")
            }
            fail("expected the last error to be rethrown")
        } catch (t: IllegalStateException) {
            assertEquals("err-b", t.message)
        }
    }

    @Test
    fun singleCandidateIsCalledExactlyOnce() = runBlocking {
        var calls = 0
        val result = ToolFallbackChain.firstUsable(candidates = listOf("only"), isEmpty = { false }) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun cancellationIsNotSwallowed() = runBlocking {
        try {
            ToolFallbackChain.firstUsable(candidates = listOf("a", "b"), isEmpty = { false }) {
                throw CancellationException("stop")
            }
            fail("cancellation must propagate")
        } catch (c: CancellationException) {
            assertEquals("stop", c.message)
        }
    }
}
