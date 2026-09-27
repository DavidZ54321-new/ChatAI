package com.zcw.chatai.data.image

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageInputsTest {

    private fun attachment(id: String, kind: AttachmentKind = AttachmentKind.IMAGE) = Attachment(
        id = id,
        kind = kind,
        relativePath = "attachments/c/$id.png",
        mimeType = "image/png",
        width = 1024,
        height = 1024,
        sizeBytes = 10,
    )

    private fun message(id: String, role: Role, attachments: List<Attachment>) = Message(
        id = id,
        conversationId = "c",
        role = role,
        content = "",
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        reasoningContent = null,
        seq = 1,
        model = null,
        promptTokens = null,
        completionTokens = null,
        attachments = attachments,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun previousImageIsTheLastAssistantImage() {
        val messages = listOf(
            message("u1", Role.USER, listOf(attachment("u-img"))),
            message("a1", Role.ASSISTANT, listOf(attachment("gen-1"))),
            message("u2", Role.USER, emptyList()),
            message("a2", Role.ASSISTANT, listOf(attachment("gen-2"))),
        )
        assertEquals("gen-2", ImageInputs.previousImage(messages)?.id)
    }

    @Test
    fun previousImageIgnoresUserAttachmentsAndImageLessTurns() {
        val messages = listOf(
            message("u1", Role.USER, listOf(attachment("u-img"))),
            message("a1", Role.ASSISTANT, emptyList()),
        )
        assertNull(ImageInputs.previousImage(messages))
    }

    @Test
    fun previousImageTakesTheLastImageOfThatMessage() {
        val messages = listOf(
            message("a1", Role.ASSISTANT, listOf(attachment("gen-1"), attachment("gen-2"))),
        )
        assertEquals("gen-2", ImageInputs.previousImage(messages)?.id)
    }

    @Test
    fun previousImageIgnoresNonImageAttachments() {
        val messages = listOf(
            message("a1", Role.ASSISTANT, listOf(attachment("doc", AttachmentKind.DOCUMENT))),
        )
        assertNull(ImageInputs.previousImage(messages))
    }

    @Test
    fun wireInputsPutsPreviousFirstThenUserOrder() {
        val prev = attachment("gen-1")
        val user = listOf(attachment("u1"), attachment("u2"))
        assertEquals(
            listOf("gen-1", "u1", "u2"),
            ImageInputs.wireInputs(prev, user).map { it.id },
        )
    }

    @Test
    fun wireInputsWithoutPreviousIsJustUserImages() {
        assertEquals(
            listOf("u1"),
            ImageInputs.wireInputs(null, listOf(attachment("u1"))).map { it.id },
        )
    }

    @Test
    fun wireInputsCapsAtThreeKeepingPrevious() {
        val prev = attachment("gen-1")
        val user = listOf(attachment("u1"), attachment("u2"), attachment("u3"))
        assertEquals(
            listOf("gen-1", "u1", "u2"),
            ImageInputs.wireInputs(prev, user).map { it.id },
        )
    }

    @Test
    fun userImageLimitReservesOneSlotForPrevious() {
        assertEquals(3, ImageInputs.userImageLimit(null))
        assertEquals(2, ImageInputs.userImageLimit(attachment("gen-1")))
    }
}
