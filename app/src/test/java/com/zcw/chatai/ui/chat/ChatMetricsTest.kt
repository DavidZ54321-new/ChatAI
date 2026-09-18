package com.zcw.chatai.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMetricsTest {

    @Test
    fun thumbnailIsOneFifthOfViewportWidth() {
        // Pixel 6a：411dp 视窗 → 82.2dp。单图多图同一个尺寸。
        assertEquals(82.2f, ChatMetrics.thumbnailSide(411.dp).value, 0.001f)
        assertEquals(72f, ChatMetrics.thumbnailSide(360.dp).value, 0.001f)
        assertEquals(120f, ChatMetrics.thumbnailSide(600.dp).value, 0.001f)
    }

    @Test
    fun topDissolveKeepsBarOpaqueThenTails() {
        val band = ChatMetrics.topDissolve(914.dp, statusBar = 48.dp)
        // 48 + 56 + 914 * 0.04 = 140.56
        assertEquals(140.56f, band.height.value, 0.001f)
        assertEquals((48f + 56f) / 140.56f, band.opaqueStop, 0.001f)
    }

    @Test
    fun bottomDissolveLeadIsThreePercentThenCoversComposer() {
        val band = ChatMetrics.bottomDissolve(914.dp, composerStack = 80.dp)
        val lead = 914f * 0.03f
        assertEquals(80f + lead, band.height.value, 0.001f)
        assertEquals(lead / (80f + lead), band.opaqueStop, 0.001f)
    }

    @Test
    fun markdownImageRowSizesFollowViewportWidth() {
        // Pixel 6a：411dp 视窗 → 高 172.62dp、单张最大宽 328.8dp。
        assertEquals(172.62f, ChatMetrics.markdownImageHeight(411.dp).value, 0.001f)
        assertEquals(328.8f, ChatMetrics.markdownImageMaxWidth(411.dp).value, 0.001f)
        assertEquals(1.dp, ChatMetrics.markdownImageHeight(0.dp))
        assertEquals(1.dp, ChatMetrics.markdownImageMaxWidth(0.dp))
    }

    @Test
    fun degenerateWindowStillGivesPositiveSize() {
        assertEquals(1.dp, ChatMetrics.thumbnailSide(0.dp))
        val tinyTop = ChatMetrics.topDissolve(0.dp, 0.dp)
        assertEquals(57.dp, tinyTop.height) // 56 + 1dp tail
        assertEquals(56f / 57f, tinyTop.opaqueStop, 0.001f)
        val tinyBottom = ChatMetrics.bottomDissolve(0.dp, 0.dp)
        assertEquals(1.dp, tinyBottom.height)
        assertEquals(1f, tinyBottom.opaqueStop, 0.001f)
    }
}
