package com.zcw.chatai.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoGenRequestCodecTest {

    @Test
    fun roundTripsAllFields() {
        val request = VideoGenRequest(
            mode = VideoMode.R2V,
            prompt = "视频1抱着图1",
            model = "wan3.0-video",
            resolution = "1080P",
            ratio = "16:9",
            duration = 10,
            audio = false,
            watermark = false,
            promptExtend = true,
            firstFrame = VideoInputRef("f", "attachments/c/f.png", "image/png"),
            lastFrame = null,
            references = listOf(
                VideoInputRef("r1", "attachments/c/r1.png", "image/png"),
                VideoInputRef("r2", "attachments/c/r2.png", "image/jpeg"),
            ),
        )
        val decoded = VideoGenRequestCodec.decode(VideoGenRequestCodec.encode(request))
        assertEquals(request, decoded)
    }

    @Test
    fun decodeTolerantOfGarbage() {
        assertNull(VideoGenRequestCodec.decode(null))
        assertNull(VideoGenRequestCodec.decode(""))
        assertNull(VideoGenRequestCodec.decode("not json"))
    }
}
