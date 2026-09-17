package com.zcw.chatai.data.ai

import com.zcw.chatai.data.net.ChatStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAccumulatorTest {

    private val start = 1_000_000L

    @Test
    fun accumulatesContentAndReasoningSeparately() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "想"), start)
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "了想"), start + 200)
        accumulator.accept(ChatStreamEvent.Delta(content = "答"), start + 400)
        accumulator.accept(ChatStreamEvent.Delta(content = "案"), start + 600)

        assertEquals("答案", accumulator.content)
        assertEquals("想了想", accumulator.reasoning)
    }

    @Test
    fun publishesAtMostEveryThrottleWindow() {
        val accumulator = StreamAccumulator(uiThrottleMs = 80, checkpointMs = 800)
        var publishes = 0
        repeat(10) { index ->
            val update = accumulator.accept(ChatStreamEvent.Delta(content = "$index"), start + index * 10L)
            if (update.publish) publishes++
        }
        assertTrue("publishes=$publishes", publishes in 1..3)
    }

    @Test
    fun checkpointsAreRarerThanPublishes() {
        val accumulator = StreamAccumulator(uiThrottleMs = 80, checkpointMs = 800)
        var publishes = 0
        var checkpoints = 0
        repeat(40) { index ->
            val update = accumulator.accept(ChatStreamEvent.Delta(content = "字"), start + index * 80L)
            if (update.publish) publishes++
            if (update.checkpoint) checkpoints++
        }
        assertTrue("publishes=$publishes checkpoints=$checkpoints", publishes > checkpoints)
    }

    @Test
    fun firstDeltaPublishesImmediately() {
        val accumulator = StreamAccumulator()
        assertTrue(accumulator.accept(ChatStreamEvent.Delta(content = "首"), start).publish)
    }

    @Test
    fun capturesFinishReasonAndUsage() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ChatStreamEvent.Finished("length"), start)
        accumulator.accept(
            ChatStreamEvent.Usage(
                promptTokens = 41,
                completionTokens = 24,
                reasoningTokens = 24,
                cachedTokens = 0,
            ),
            start + 10,
        )
        accumulator.accept(ChatStreamEvent.Completed, start + 20)

        assertEquals("length", accumulator.finishReason)
        assertEquals(41, accumulator.usage?.promptTokens)
        assertEquals(24, accumulator.usage?.reasoningTokens)
        assertEquals(0, accumulator.usage?.cachedTokens)
    }

    @Test
    fun ignoredEventsDoNotPublish() {
        val accumulator = StreamAccumulator()
        assertFalse(accumulator.accept(ChatStreamEvent.Completed, start).publish)
        assertEquals("", accumulator.content)
    }

    @Test
    fun nullContentDeltasAreIgnored() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ChatStreamEvent.Delta(content = null, reasoning = null), start)
        assertEquals("", accumulator.content)
        assertNull(accumulator.finishReason)
    }
}
