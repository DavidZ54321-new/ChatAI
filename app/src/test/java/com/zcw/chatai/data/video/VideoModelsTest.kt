package com.zcw.chatai.data.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoModelsTest {

    @Test
    fun resolveFallsBackToDefaultWhenBlank() {
        assertEquals(VideoModels.DEFAULT, VideoModels.resolve(""))
        assertEquals(VideoModels.DEFAULT, VideoModels.resolve("   "))
    }

    @Test
    fun resolveTrimsOverride() {
        assertEquals("wan2.6-t2v", VideoModels.resolve("  wan2.6-t2v "))
    }

    @Test
    fun defaultIsAllInOneModel() {
        assertEquals("wan3.0-video", VideoModels.DEFAULT)
    }
}
