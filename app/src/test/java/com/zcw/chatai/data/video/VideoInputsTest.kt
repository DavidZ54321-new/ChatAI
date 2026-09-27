package com.zcw.chatai.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInputsTest {

    @Test
    fun textToVideoNeedsPromptOnly() {
        assertNotNull(VideoInputs.validate(VideoMode.T2V, "  ", false, false, 0))
        assertNull(VideoInputs.validate(VideoMode.T2V, "一只猫", false, false, 0))
        assertTrue(VideoInputs.mediaTypes(VideoMode.T2V, false, false, 0).isEmpty())
    }

    @Test
    fun imageToVideoNeedsFirstFrame() {
        assertNotNull(VideoInputs.validate(VideoMode.I2V, "", false, false, 0))
        // 只有尾帧也不合法：首尾帧模式必须有首帧。
        assertNotNull(VideoInputs.validate(VideoMode.I2V, "", false, true, 0))
        assertNull(VideoInputs.validate(VideoMode.I2V, "", true, false, 0))
        assertNull(VideoInputs.validate(VideoMode.I2V, "", true, true, 0))
    }

    @Test
    fun imageToVideoMediaTypesAreOrdered() {
        assertEquals(listOf("first_frame"), VideoInputs.mediaTypes(VideoMode.I2V, true, false, 0))
        assertEquals(
            listOf("first_frame", "last_frame"),
            VideoInputs.mediaTypes(VideoMode.I2V, true, true, 0),
        )
    }

    @Test
    fun referenceVideoNeedsAtLeastOneAndCapsAtLimit() {
        assertNotNull(VideoInputs.validate(VideoMode.R2V, "x", false, false, 0))
        assertNull(VideoInputs.validate(VideoMode.R2V, "x", false, false, 1))
        assertNotNull(VideoInputs.validate(VideoMode.R2V, "x", false, false, VideoInputs.MAX_REFERENCES + 1))
        assertEquals(
            List(VideoInputs.MAX_REFERENCES) { "reference_image" },
            VideoInputs.mediaTypes(VideoMode.R2V, false, false, VideoInputs.MAX_REFERENCES),
        )
    }
}
