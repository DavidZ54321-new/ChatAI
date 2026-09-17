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

    @Test
    fun reasoningDurationRunsFromTurnStartToLastReasoningDelta() {
        val accumulator = StreamAccumulator(startedAt = start)
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "想"), start + 400)
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "想"), start + 2_000)
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "想"), start + 9_000)
        // 正文开始后不再涨
        accumulator.accept(ChatStreamEvent.Delta(content = "答"), start + 12_000)
        accumulator.accept(ChatStreamEvent.Delta(content = "案"), start + 20_000)

        assertEquals(9_000L, accumulator.reasoningMs)
    }

    @Test
    fun reasoningDurationIsNullWithoutReasoning() {
        val accumulator = StreamAccumulator(startedAt = start)
        accumulator.accept(ChatStreamEvent.Delta(content = "答"), start + 5_000)
        assertNull(accumulator.reasoningMs)
    }

    @Test
    fun reasoningDurationFallsBackToFirstEventWhenStartUnknown() {
        val accumulator = StreamAccumulator()
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "想"), start + 1_000)
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "想"), start + 5_000)
        assertEquals(4_000L, accumulator.reasoningMs)
    }

    /** 输出顺序不保证：正文先到、思考后到也要算得出来（否则会得到 0）。 */
    @Test
    fun reasoningAfterContentIsStillMeasured() {
        val accumulator = StreamAccumulator(startedAt = start)
        accumulator.accept(ChatStreamEvent.Delta(content = "答"), start + 300)
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "想"), start + 3_000)
        assertEquals(3_000L, accumulator.reasoningMs)
    }

    @Test
    fun reasoningDurationNeverGoesNegative() {
        val accumulator = StreamAccumulator(startedAt = start + 5_000)
        accumulator.accept(ChatStreamEvent.Delta(reasoning = "想"), start + 1_000)
        assertEquals(0L, accumulator.reasoningMs)
    }
}
