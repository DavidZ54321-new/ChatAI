package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailLayoutTest {

    private val saved = TailLayout(
        groupKey = "a1",
        heightPx = 1800,
        contentChars = 400,
        widthPx = 1080,
        fontScaleMilli = 1000,
        themeFamily = "CLAUDE",
        dark = true,
    )

    @Test
    fun matchRequiresKeyCharsWidthFontAndTheme() {
        assertTrue(saved.matches("a1", 400, 1080, 1000, "CLAUDE", true))
        assertFalse(saved.matches("a2", 400, 1080, 1000, "CLAUDE", true))
        assertFalse(saved.matches("a1", 401, 1080, 1000, "CLAUDE", true))
        assertFalse(saved.matches("a1", 400, 1440, 1000, "CLAUDE", true))
        assertFalse(saved.matches("a1", 400, 1080, 1100, "CLAUDE", true))
        assertFalse(saved.matches("a1", 400, 1080, 1000, "CHATGPT", true))
        assertFalse(saved.matches("a1", 400, 1080, 1000, "CLAUDE", false))
    }

    @Test
    fun shortMeasureKeepsTheReservationUntilContentCatchesUp() {
        assertTrue(reserveTailHeight(cachedPx = 1800, measuredPx = 0, streaming = false))
        assertTrue(reserveTailHeight(cachedPx = 1800, measuredPx = 200, streaming = false))
        assertFalse(reserveTailHeight(cachedPx = 1800, measuredPx = 1790, streaming = false))
        assertFalse(reserveTailHeight(cachedPx = 1800, measuredPx = 200, streaming = true))
        assertFalse(reserveTailHeight(cachedPx = null, measuredPx = 0, streaming = false))
    }

    @Test
    fun recordWaitsUntilTheMeasureCatchesTheReservation() {
        assertFalse(shouldRecordTailHeight(located = false, streaming = false, measuredPx = 1800, reservedPx = null))
        assertFalse(shouldRecordTailHeight(located = true, streaming = true, measuredPx = 1800, reservedPx = null))
        assertFalse(shouldRecordTailHeight(located = true, streaming = false, measuredPx = 200, reservedPx = 1800))
        assertTrue(shouldRecordTailHeight(located = true, streaming = false, measuredPx = 1800, reservedPx = 1800))
        assertTrue(shouldRecordTailHeight(located = true, streaming = false, measuredPx = 900, reservedPx = null))
    }

    @Test
    fun codecRoundTripDropsBrokenLines() {
        val text = encodeTailLayouts(mapOf("conv" to saved)) + "\nbroken\n"
        val decoded = decodeTailLayouts(text)
        assertEquals(saved, decoded["conv"])
        assertEquals(1, decoded.size)
    }

    @Test
    fun storeLookupMissesWhenTheTailChanged() {
        val dir = kotlin.io.path.createTempDirectory("tail").toFile()
        val store = TailLayoutStore(java.io.File(dir, "tail-layout.txt"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        store.record("conv", saved)
        assertEquals(
            1800,
            store.lookup("conv", "a1", 400, 1080, 1000, "CLAUDE", true),
        )
        assertNull(store.lookup("conv", "a1", 400, 1080, 1000, "CLAUDE", false))
        assertNull(store.lookup("other", "a1", 400, 1080, 1000, "CLAUDE", true))
    }

    @Test
    fun tailCharsCountContentAndReasoning() {
        val chars = tailContentChars(
            listOf(
                ChatMessageItem(id = "a", role = Role.ASSISTANT, content = "答案", reasoning = "想"),
                ChatMessageItem(id = "t", role = Role.TOOL, content = "结果"),
            ),
        )
        assertEquals("答案想结果".length, chars)
    }
}
