package com.zcw.chatai.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoDialogueFaceTest {

    @Test
    fun lunaGrokMusePreferResponses() {
        assertTrue(GoDialogueFace.prefersResponses("gpt-5.6-luna"))
        assertTrue(GoDialogueFace.prefersResponses("GROK-4.6"))
        assertTrue(GoDialogueFace.prefersResponses("muse-spark-1.2-contributor"))
        assertTrue(GoDialogueFace.prefersResponses("muse-spark-1.3-contributor"))
    }

    @Test
    fun chatMountedModelsStayOnChat() {
        assertFalse(GoDialogueFace.prefersResponses("deepseek-v4.1-flash"))
        assertFalse(GoDialogueFace.prefersResponses("deepseek-flash"))
        assertFalse(GoDialogueFace.prefersResponses("minimax-m3"))
        assertFalse(GoDialogueFace.prefersResponses("qwen3.8-max"))
        assertFalse(GoDialogueFace.prefersResponses(""))
        assertFalse(GoDialogueFace.prefersResponses("amuse-x"))
        assertFalse(GoDialogueFace.prefersResponses("lunalab-1"))
    }
}
