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
        // kind 必须是枚举外的名字（AUDIO 已是合法 kind，不能再当未知样例）。
        val raw = """[{"id":"x","kind":"HOLOGRAM","path":"attachments/c/x.m4a"},{"id":"y","kind":"IMAGE","path":"attachments/c/y.jpg"}]"""
        val decoded = AttachmentCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("y", decoded.single().id)
    }

    @Test
    fun roundTripsAudioAttachment() {
        val original = listOf(
            Attachment(
                id = "au1",
                kind = AttachmentKind.AUDIO,
                relativePath = "attachments/c/au1.mp3",
                mimeType = "audio/mpeg",
                width = 0,
                height = 0,
                sizeBytes = 2_345_678,
                durationMs = 42_000,
                displayName = "recording.mp3",
            ),
        )
        assertEquals(original, AttachmentCodec.decode(AttachmentCodec.encode(original)))
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

    @Test
    fun roundTripsVideoAttachmentWithRemoteJournal() {
        val original = listOf(
            Attachment(
                id = "v1",
                kind = AttachmentKind.VIDEO,
                relativePath = "attachments/c/v1.mp4",
                mimeType = "video/mp4",
                width = 320,
                height = 240,
                sizeBytes = 12_345_678,
                durationMs = 9_500,
                remoteUrl = "oss://dashscope-instant/x/v1.mp4",
                remoteModel = "qwen3.8-max",
                remoteExpiresAt = 1_800_000_000_000L,
                pendingKey = "dashscope-instant/x/v1.mp4",
                pendingPolicy = "{\"policy\":\"p\"}",
            ),
        )
        assertEquals(original, AttachmentCodec.decode(AttachmentCodec.encode(original)))
    }

    @Test
    fun legacyImageJsonWithoutVideoFieldsDecodesWithNulls() {
        val raw = """[{"id":"a","kind":"IMAGE","path":"p.jpg","mime":"image/jpeg","width":1,"height":1,"size":2}]"""
        val decoded = AttachmentCodec.decode(raw).single()
        assertEquals(null, decoded.durationMs)
        assertEquals(null, decoded.remoteUrl)
        assertEquals(null, decoded.remoteModel)
        assertEquals(null, decoded.remoteExpiresAt)
        assertEquals(null, decoded.pendingKey)
        assertEquals(null, decoded.pendingPolicy)
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
