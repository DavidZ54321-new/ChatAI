package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentPreviewTest {

    private fun image(id: String, kind: AttachmentKind) = MessageImage(
        id = id,
        thumbnailPath = "attachments/c/$id.thumb.jpg",
        fullPath = "attachments/c/$id.bin",
        width = 10,
        height = 10,
        kind = kind,
    )

    @Test
    fun previewableKeepsImagesVideoAndAudioButDropsDocuments() {
        val images = listOf(
            image("i", AttachmentKind.IMAGE),
            image("d", AttachmentKind.DOCUMENT),
            image("v", AttachmentKind.VIDEO),
            image("a", AttachmentKind.AUDIO),
        )

        assertEquals(listOf("i", "v", "a"), AttachmentPreview.previewable(images).map { it.id })
    }

    /** 下标必须是「可预览列表」里的位置，而不是原始列表的位置。 */
    @Test
    fun indexOfUsesThePreviewableList() {
        val images = listOf(
            image("d", AttachmentKind.DOCUMENT),
            image("i1", AttachmentKind.IMAGE),
            image("i2", AttachmentKind.IMAGE),
        )

        assertEquals(0, AttachmentPreview.indexOf(images, "i1"))
        assertEquals(1, AttachmentPreview.indexOf(images, "i2"))
    }

    @Test
    fun indexOfIsMinusOneForDocumentsOrUnknownIds() {
        val images = listOf(image("d", AttachmentKind.DOCUMENT), image("i", AttachmentKind.IMAGE))

        assertEquals(-1, AttachmentPreview.indexOf(images, "d"))
        assertEquals(-1, AttachmentPreview.indexOf(images, "nope"))
    }

    @Test
    fun fileNameExtensionParsesUrl() {
        assertEquals("png", fileNameExtension("https://a.example/b/c.png"))
        assertEquals("jpeg", fileNameExtension("https://a.example/x.jpeg?w=1&h=2"))
        assertEquals("webp", fileNameExtension("https://a.example/x.webp#frag"))
        assertEquals("jpg", fileNameExtension("https://a.example/no-extension"))
        assertEquals("jpg", fileNameExtension("https://a.example/trailing."))
        assertEquals("jpg", fileNameExtension("https://a.example/weird.verylongext"))
    }
}
