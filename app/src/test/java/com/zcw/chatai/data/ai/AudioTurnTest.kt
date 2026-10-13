package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTurnTest {

    @Test
    fun textTurnStaysOnTheNormalPath() {
        assertEquals(AudioTurn.Phase.Normal, AudioTurn.phase(listOf(user("看看"))))
    }

    @Test
    fun audioTurnRecognizesThenInterprets() {
        val history = listOf(user("帮我分别解读这些文件", audio()))
        assertEquals(AudioTurn.Phase.RecognizeThenInterpret, AudioTurn.phase(history))
    }

    @Test
    fun existingRecognitionSkipsToInterpretation() {
        val history = listOf(
            user("帮我分别解读这些文件", audio()),
            assistant("识别稿"),
        )
        assertEquals(AudioTurn.Phase.InterpretOnly, AudioTurn.phase(history))
    }

    @Test
    fun laterTextTurnDoesNotReuseAnOlderAudio() {
        val history = listOf(
            user("听这个", audio()),
            assistant("识别稿"),
            user("再聊聊别的"),
        )
        assertEquals(AudioTurn.Phase.Normal, AudioTurn.phase(history))
    }

    @Test
    fun followUpNoteIsStable() {
        assertEquals(
            "上面是音频识别结果。请完成用户的要求，把解读写在正文里。",
            AudioTurn.FOLLOW_UP,
        )
    }

    private fun audio() = Attachment(
        id = "au",
        kind = AttachmentKind.AUDIO,
        relativePath = "attachments/c/au.wav",
        mimeType = "audio/wav",
        width = 0,
        height = 0,
        sizeBytes = 1000,
    )

    private fun user(text: String, attachment: Attachment? = null) = Message(
        id = text,
        conversationId = "c",
        role = Role.USER,
        content = text,
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        reasoningContent = null,
        seq = 1,
        model = null,
        promptTokens = null,
        completionTokens = null,
        attachments = listOfNotNull(attachment),
        createdAt = 0,
        updatedAt = 0,
    )

    private fun assistant(text: String) = Message(
        id = "a",
        conversationId = "c",
        role = Role.ASSISTANT,
        content = text,
        status = MessageStatus.COMPLETE,
        errorMessage = null,
        reasoningContent = null,
        seq = 2,
        model = null,
        promptTokens = null,
        completionTokens = null,
        createdAt = 0,
        updatedAt = 0,
    )
}
