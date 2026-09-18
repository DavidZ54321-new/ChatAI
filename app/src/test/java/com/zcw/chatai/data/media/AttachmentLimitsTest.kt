package com.zcw.chatai.data.media

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentLimitsTest {

    @Test
    fun acceptsAReasonableMixedMessage() {
        assertNull(AttachmentLimits.validate(listOf(image(), video(size = 10 * 1024 * 1024))))
    }

    @Test
    fun rejectsTooManyImages() {
        val images = (1..AttachmentLimits.MAX_IMAGES + 1).map { image(id = "i$it") }
        assertTrue(AttachmentLimits.validate(images)!!.contains("图片"))
    }

    @Test
    fun rejectsOversizedImageTotals() {
        val big = image(size = AttachmentLimits.MAX_TOTAL_BYTES + 1)
        assertTrue(AttachmentLimits.validate(listOf(big))!!.contains("总大小"))
    }

    @Test
    fun rejectsTooManyVideos() {
        val videos = (1..AttachmentLimits.MAX_VIDEOS + 1).map { video(id = "v$it", size = 1024) }
        assertTrue(AttachmentLimits.validate(videos)!!.contains("视频"))
    }

    @Test
    fun rejectsOversizedVideo() {
        val huge = video(size = AttachmentLimits.MAX_VIDEO_BYTES + 1)
        assertTrue(AttachmentLimits.validate(listOf(huge))!!.contains("MB"))
    }

    @Test
    fun requestEstimateCountsInlineVideoButNotUploadedVideo() {
        val inline = video(size = AttachmentLimits.VIDEO_INLINE_MAX_BYTES)
        val uploaded = video(id = "v2", size = AttachmentLimits.VIDEO_INLINE_MAX_BYTES + 1)
        val inlineBytes = AttachmentLimits.estimatedRequestBytes(listOf(inline))
        assertTrue(inlineBytes > inline.sizeBytes)

        val withUploaded = AttachmentLimits.estimatedRequestBytes(listOf(inline, uploaded))
        assertEquals(inlineBytes, withUploaded)
    }

    private fun image(
        id: String = "i1",
        size: Long = 1000,
    ) = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        relativePath = "attachments/c/$id.jpg",
        mimeType = "image/jpeg",
        width = 10,
        height = 10,
        sizeBytes = size,
    )

    private fun video(
        id: String = "v1",
        size: Long,
    ) = Attachment(
        id = id,
        kind = AttachmentKind.VIDEO,
        relativePath = "attachments/c/$id.mp4",
        mimeType = "video/mp4",
        width = 10,
        height = 10,
        sizeBytes = size,
        durationMs = 1000,
    )
}
