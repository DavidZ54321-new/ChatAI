package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCodecTest {

    @Test
    fun roundTripsAttachmentList() {
        val original = listOf(
            attachment("a1", "attachments/c/a1.jpg", 1200, 800, 34567),
            attachment("a2", "attachments/c/a2.png", 640, 640, 9999),
        )
        val encoded = AttachmentCodec.encode(original)
        assertEquals(original, AttachmentCodec.decode(encoded))
    }

    @Test
    fun emptyListEncodesToNullAndDecodesToEmpty() {
        assertNull(AttachmentCodec.encode(emptyList()))
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode(null))
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode(""))
    }

    @Test
    fun invalidJsonDecodesToEmptyList() {
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode("{not json"))
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode("{\"a\":1}"))
    }

    @Test
    fun unknownKindDropsOnlyThatItem() {
        val raw = """[{"id":"x","kind":"AUDIO","path":"attachments/c/x.m4a"},{"id":"y","kind":"IMAGE","path":"attachments/c/y.jpg"}]"""
        val decoded = AttachmentCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("y", decoded.single().id)
    }

    @Test
    fun missingKeyFieldsAreDropped() {
        val raw = """[{"id":"","kind":"IMAGE","path":"a.jpg"},{"id":"z","kind":"IMAGE","path":""}]"""
        assertEquals(emptyList<Attachment>(), AttachmentCodec.decode(raw))
    }

    @Test
    fun unknownExtraFieldsAreIgnored() {
        val raw = """[{"id":"a","kind":"IMAGE","path":"p.jpg","future":"field"}]"""
        val decoded = AttachmentCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("p.jpg", decoded.single().relativePath)
        assertTrue(decoded.single().mimeType.isNotBlank())
    }

    private fun attachment(
        id: String,
        path: String,
        width: Int,
        height: Int,
        size: Long,
    ) = Attachment(
        id = id,
        kind = AttachmentKind.IMAGE,
        relativePath = path,
        mimeType = "image/jpeg",
        width = width,
        height = height,
        sizeBytes = size,
    )
}
