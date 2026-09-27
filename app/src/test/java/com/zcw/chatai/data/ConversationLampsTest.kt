package com.zcw.chatai.data

import com.zcw.chatai.data.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLampsTest {

    private val lamps = ConversationLamps()

    @Test
    fun completeWhileAwayLightsDone() {
        val token = lamps.begin("a")
        lamps.finish("a", token, MessageStatus.COMPLETE)

        assertEquals(ConversationLamp.UNSEEN_DONE, lamps.current.value["a"])
    }

    @Test
    fun errorWhileAwayLightsFailed() {
        val token = lamps.begin("a")
        lamps.finish("a", token, MessageStatus.ERROR)

        assertEquals(ConversationLamp.UNSEEN_FAILED, lamps.current.value["a"])
    }

    @Test
    fun runningShowsYellowUntilTheTurnEnds() {
        val token = lamps.begin("a")

        assertEquals(ConversationLamp.RUNNING, lamps.current.value["a"])

        lamps.finish("a", token, MessageStatus.COMPLETE)
        assertEquals(ConversationLamp.UNSEEN_DONE, lamps.current.value["a"])
    }

    @Test
    fun watchingIgnoresFinishAndClearsExisting() {
        val prior = lamps.begin("a")
        lamps.finish("a", prior, MessageStatus.ERROR)
        lamps.setWatching("a")

        assertNull(lamps.current.value["a"])

        val token = lamps.begin("a")
        assertEquals(ConversationLamp.RUNNING, lamps.current.value["a"])
        lamps.finish("a", token, MessageStatus.COMPLETE)

        assertTrue(lamps.current.value.isEmpty())
    }

    @Test
    fun cancelAndNullDoNotLight() {
        lamps.finish("a", lamps.begin("a"), MessageStatus.CANCELLED)
        lamps.finish("b", lamps.begin("b"), MessageStatus.STREAMING)
        lamps.finish("c", lamps.begin("c"), null)

        assertTrue(lamps.current.value.isEmpty())
    }

    @Test
    fun supersededTurnCannotClearOrRecolorTheNewOne() {
        val old = lamps.begin("a")
        val current = lamps.begin("a")

        lamps.finish("a", old, MessageStatus.ERROR)
        assertEquals(ConversationLamp.RUNNING, lamps.current.value["a"])

        lamps.finish("a", current, MessageStatus.COMPLETE)
        assertEquals(ConversationLamp.UNSEEN_DONE, lamps.current.value["a"])
    }

    @Test
    fun enteringClearsUnseenButNotARunningTurn() {
        val token = lamps.begin("a")
        lamps.finish("b", lamps.begin("b"), MessageStatus.COMPLETE)

        lamps.setWatching("a")
        assertEquals(ConversationLamp.RUNNING, lamps.current.value["a"])
        assertEquals(ConversationLamp.UNSEEN_DONE, lamps.current.value["b"])

        lamps.finish("a", token, MessageStatus.ERROR)
        assertNull(lamps.current.value["a"])
        assertEquals(ConversationLamp.UNSEEN_DONE, lamps.current.value["b"])
    }

    @Test
    fun leavingOneConversationDoesNotClearTheOther() {
        lamps.setWatching("a")
        lamps.finish("b", lamps.begin("b"), MessageStatus.COMPLETE)
        lamps.setWatching(null)
        lamps.finish("a", lamps.begin("a"), MessageStatus.ERROR)

        assertEquals(ConversationLamp.UNSEEN_DONE, lamps.current.value["b"])
        assertEquals(ConversationLamp.UNSEEN_FAILED, lamps.current.value["a"])
    }

    @Test
    fun outcomeTable() {
        assertNull(ConversationLamps.outcome(watching = true, status = MessageStatus.ERROR))
        assertNull(ConversationLamps.outcome(watching = true, status = MessageStatus.COMPLETE))
        assertNull(ConversationLamps.outcome(watching = false, status = null))
        assertEquals(
            UnseenOutcome.DONE,
            ConversationLamps.outcome(watching = false, status = MessageStatus.COMPLETE),
        )
        assertEquals(
            UnseenOutcome.FAILED,
            ConversationLamps.outcome(watching = false, status = MessageStatus.ERROR),
        )
        assertNull(ConversationLamps.outcome(watching = false, status = MessageStatus.CANCELLED))
    }
}
