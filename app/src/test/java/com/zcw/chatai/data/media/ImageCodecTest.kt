package com.zcw.chatai.data.media

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCodecTest {

    @Test
    fun scalesDownKeepingAspectRatio() {
        val size = ImageCodec.computeTargetSize(4000, 3000, maxEdge = 1568)
        assertEquals(1568, size.width)
        assertEquals(1176, size.height)
    }

    @Test
    fun neverUpscalesAndHandlesPortrait() {
        assertEquals(ImageCodec.ImageSize(800, 600), ImageCodec.computeTargetSize(800, 600))
        val portrait = ImageCodec.computeTargetSize(600, 4000, maxEdge = 1568)
        assertEquals(235, portrait.width)
        assertEquals(1568, portrait.height)
    }

    @Test
    fun degenerateSizesAreClamped() {
        assertEquals(ImageCodec.ImageSize(1, 1), ImageCodec.computeTargetSize(0, -5))
    }

    @Test
    fun sniffMimeUsesFileContent() {
        assertEquals(ImageCodec.MIME_JPEG, ImageCodec.sniffMime(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(
            ImageCodec.MIME_PNG,
            ImageCodec.sniffMime(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)),
        )
        assertEquals("image/gif", ImageCodec.sniffMime(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0)))
        assertEquals(
            "image/webp",
            ImageCodec.sniffMime(
                bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50),
            ),
        )
        assertNull(ImageCodec.sniffMime(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
        assertNull(ImageCodec.sniffMime(bytes(1, 2, 3)))
    }

    @Test
    fun buildsInlineDataUrl() {
        val url = ImageCodec.toDataUrl(ImageCodec.MIME_JPEG, byteArrayOf(1, 2, 3))
        assertTrue(url, url.startsWith("data:image/jpeg;base64,"))
        assertEquals("data:image/jpeg;base64,AQID", url)
    }

    @Test
    fun estimatesBase64Size() {
        assertEquals(4L, ImageCodec.base64Length(3))
        assertEquals(8L, ImageCodec.base64Length(4))
        assertEquals(8L, ImageCodec.base64Length(6))
    }

    @Test
    fun derivesThumbnailPath() {
        assertEquals(
            "attachments/abc/def.thumb.jpg",
            ImageCodec.thumbRelativePath("attachments/abc/def.jpg"),
        )
        assertEquals(
            "attachments/a.b/def.thumb.jpg",
            ImageCodec.thumbRelativePath("attachments/a.b/def.jpg"),
        )
        assertEquals("attachments/abc/noext.thumb", ImageCodec.thumbRelativePath("attachments/abc/noext"))
    }

    @Test
    fun limitsRejectTooManyOrTooLarge() {
        assertNull(AttachmentLimits.validate(listOf(attachment("1", 1000))))
        assertEquals(
            "最多只能发送 8 张图片",
            AttachmentLimits.validate((1..9).map { attachment("$it", 1000) }),
        )
        assertTrue(
            AttachmentLimits.validate(listOf(attachment("1", AttachmentLimits.MAX_TOTAL_BYTES + 1)))
                .orEmpty()
                .contains("总大小"),
        )
    }

    private fun attachment(id: String, size: Long) = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        relativePath = "attachments/c/$id.jpg",
        mimeType = ImageCodec.MIME_JPEG,
        width = 100,
        height = 100,
        sizeBytes = size,
    )

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
