package com.zcw.chatai.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleTextTest {

    @Test
    fun finishesImmediatelyWhenTheFlowEnds() = runBlocking {
        val seen = mutableListOf<String>()
        val outcome = flow {
            emit("一")
            emit("棵树")
        }.collectUntilQuiet(idleMillis = 5_000) { seen += it }
        assertEquals("一棵树", outcome.text)
        assertFalse(outcome.stoppedForIdle)
        assertEquals(listOf("一", "一棵树"), seen)
    }

    @Test
    fun stopsWhenNoChunkArrives() = runBlocking {
        val outcome = flow {
            emit("已有")
            delay(1_000)
            emit("太晚")
        }.collectUntilQuiet(idleMillis = 80) { }
        assertEquals("已有", outcome.text)
        assertTrue(outcome.stoppedForIdle)
    }

    @Test
    fun emptySilenceKeepsAnEmptyBuffer() = runBlocking {
        val outcome = flow<String> { delay(1_000) }.collectUntilQuiet(idleMillis = 40) { }
        assertEquals("", outcome.text)
        assertTrue(outcome.stoppedForIdle)
    }
}
