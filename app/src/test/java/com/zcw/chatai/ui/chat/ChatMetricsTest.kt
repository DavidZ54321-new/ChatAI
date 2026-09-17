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
    fun dissolveBandIsOneTenthOfViewportHeight() {
        assertEquals(91.4f, ChatMetrics.bottomDissolve(914.dp).value, 0.001f)
        assertEquals(64f, ChatMetrics.bottomDissolve(640.dp).value, 0.001f)
    }

    @Test
    fun degenerateWindowStillGivesPositiveSize() {
        assertEquals(1.dp, ChatMetrics.thumbnailSide(0.dp))
        assertEquals(1.dp, ChatMetrics.bottomDissolve(0.dp))
    }
}
