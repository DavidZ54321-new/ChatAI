package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import java.io.File
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
    fun pendingDocumentMapsPrivateFileAndExtractedPaths() {
        val pending = PendingAttachment(
            id = "doc",
            thumbnailPath = "",
            attachment = Attachment(
                id = "doc",
                kind = AttachmentKind.DOCUMENT,
                relativePath = "attachments/c/doc.pdf",
                mimeType = "application/pdf",
                width = 0,
                height = 0,
                sizeBytes = 42,
                extractedPath = "attachments/c/doc.extracted.txt",
                displayName = "report.pdf",
            ),
        )

        val image = pending.toMessageImage(File("/private"))

        assertEquals(File(File("/private"), "attachments/c/doc.pdf").absolutePath, image.fullPath)
        assertEquals(
            File(File("/private"), "attachments/c/doc.extracted.txt").absolutePath,
            image.extractedPath,
        )
        assertEquals("report.pdf", image.displayName)
        assertEquals("application/pdf", image.mimeType)
        assertEquals("PDF", image.label)
        assertEquals(AttachmentKind.DOCUMENT, image.kind)
    }

    @Test
    fun pendingMediaMapsTypeAndPlaybackMetadata() {
        val pending = PendingAttachment(
            id = "video",
            thumbnailPath = "/private/video.thumb.jpg",
            attachment = Attachment(
                id = "video",
                kind = AttachmentKind.VIDEO,
                relativePath = "attachments/c/video.mp4",
                mimeType = "video/mp4",
                width = 640,
                height = 480,
                sizeBytes = 512,
                durationMs = 12_000,
            ),
        )

        val image = pending.toMessageImage(File("/private"))

        assertEquals(File(File("/private"), "attachments/c/video.mp4").absolutePath, image.fullPath)
        assertEquals("/private/video.thumb.jpg", image.thumbnailPath)
        assertEquals(AttachmentKind.VIDEO, image.kind)
        assertEquals(12_000L, image.durationMs)
        assertEquals(640, image.width)
        assertEquals(480, image.height)
    }

    @Test
    fun pendingAttachmentsUseFilteredIndexForMediaPreview() {
        val images = listOf(
            image("doc", AttachmentKind.DOCUMENT),
            image("i1", AttachmentKind.IMAGE),
            image("audio", AttachmentKind.AUDIO),
        )

        val previewable = AttachmentPreview.previewable(images)

        assertEquals(1, AttachmentPreview.indexOf(previewable, "audio"))
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
