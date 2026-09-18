package com.zcw.chatai.data

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRegistryTest {

    private val turns = TurnRegistry()

    @Test
    fun startsTurnWhenConversationIsIdle() {
        val job = turns.startIfIdle("a") { Job() }

        assertNotNull(job)
        assertEquals(setOf("a"), turns.busy.value)
    }

    @Test
    fun rejectsSecondStartForBusyConversation() {
        turns.startIfIdle("a") { Job() }

        assertNull(turns.startIfIdle("a") { Job() })
    }

    @Test
    fun differentConversationsRunConcurrently() {
        assertNotNull(turns.startIfIdle("a") { Job() })
        assertNotNull(turns.startIfIdle("b") { Job() })

        assertEquals(setOf("a", "b"), turns.busy.value)
    }

    @Test
    fun completionFreesConversation() {
        val job = Job()
        turns.startIfIdle("a") { job }

        job.complete()

        assertFalse(turns.busy.value.contains("a"))
        assertNotNull(turns.startIfIdle("a") { Job() })
    }

    @Test
    fun restartCancelsPreviousTurnOfSameConversation() {
        val old = Job()
        turns.startIfIdle("a") { old }

        val new = turns.restart("a") { Job() }

        assertTrue(old.isCancelled)
        assertFalse(new.isCancelled)
        assertNull(turns.startIfIdle("a") { Job() })
    }

    @Test
    fun staleCompletionDoesNotEvictReplacement() {
        val old = Job()
        turns.startIfIdle("a") { old }
        turns.restart("a") { Job() }

        old.complete()

        assertTrue(turns.busy.value.contains("a"))
        assertNull(turns.startIfIdle("a") { Job() })
    }

    @Test
    fun cancelStopsOnlyTargetConversation() {
        val a = turns.startIfIdle("a") { Job() }
        val b = turns.startIfIdle("b") { Job() }
        assertNotNull(a)
        assertNotNull(b)

        turns.cancel("a")

        assertTrue(a!!.isCancelled)
        assertFalse(b!!.isCancelled)
        assertEquals(setOf("b"), turns.busy.value)
    }

    @Test
    fun cancelAllStopsEveryConversation() {
        val a = turns.startIfIdle("a") { Job() }
        val b = turns.startIfIdle("b") { Job() }
        assertNotNull(a)
        assertNotNull(b)

        turns.cancelAll()

        assertTrue(a!!.isCancelled)
        assertTrue(b!!.isCancelled)
        assertTrue(turns.busy.value.isEmpty())
    }
}
