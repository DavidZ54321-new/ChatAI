package com.zcw.chatai.data.web

import com.zcw.chatai.data.web.opencode.GoSearchFace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoSearchFaceTest {

    @Test
    fun lunaGrokMusePreferResponses() {
        assertTrue(GoSearchFace.prefersResponses("gpt-5.6-luna"))
        assertTrue(GoSearchFace.prefersResponses("GROK-4.6"))
        assertTrue(GoSearchFace.prefersResponses("muse-spark-1.2-contributor"))
        assertTrue(GoSearchFace.prefersResponses("muse-spark-1.3-contributor"))
        assertTrue(GoSearchFace.prefersResponses("gpt-5.6"))
    }

    @Test
    fun deepseekAndOthersPreferMessages() {
        assertFalse(GoSearchFace.prefersResponses("deepseek-v4.1-flash"))
        assertFalse(GoSearchFace.prefersResponses("deepseek-flash"))
        assertFalse(GoSearchFace.prefersResponses("deepseek-v4-pro"))
        assertFalse(GoSearchFace.prefersResponses("minimax-m3"))
        assertFalse(GoSearchFace.prefersResponses("qwen3.8-max"))
        assertFalse(GoSearchFace.prefersResponses(""))
    }

    @Test
    fun substringLookalikesDoNotMatch() {
        assertFalse(GoSearchFace.prefersResponses("amuse-x"))
        assertFalse(GoSearchFace.prefersResponses("lunalab-1"))
        assertFalse(GoSearchFace.prefersResponses("agrok-1"))
        // 路由前缀不影响判定：只看最后一个 `/` 之后。
        assertTrue(GoSearchFace.prefersResponses("go/grok-4.6"))
    }
}
