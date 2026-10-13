package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role

/**
 * 带音频的用户回合：先关掉思考和工具把识别写进正文，再静默补一句让模型完成用户的要求。
 * 设置里的思考强度和联网开关不动，只影响这一轮的请求。
 */
object AudioTurn {

    const val FOLLOW_UP = "上面是音频识别结果。请完成用户的要求，把解读写在正文里。"

    /** 第一跳：原话和音频照常发，思考和工具由调用方关掉。 */
    val recognize = OutboundPlan()

    /** 第二跳：不再带图片、音频、视频，末尾加一句不入库的说明。 */
    val interpret = OutboundPlan(omitMedia = true, trailingUserNote = FOLLOW_UP)

    enum class Phase {
        /** 没有音频，走原来的 Agent 循环。 */
        Normal,

        /** 本轮用户消息带音频，后面还没有识别稿。 */
        RecognizeThenInterpret,

        /** 识别稿已经在上一条助手正文里（例如只重跑解读）。 */
        InterpretOnly,
    }

    fun phase(history: List<Message>): Phase {
        val lastUserIndex = history.indexOfLast { it.role == Role.USER && it.status != MessageStatus.STREAMING }
        if (lastUserIndex < 0) return Phase.Normal
        val lastUser = history[lastUserIndex]
        if (lastUser.attachments.none { it.kind == AttachmentKind.AUDIO }) return Phase.Normal
        val recognized = history.drop(lastUserIndex + 1).any { message ->
            message.role == Role.ASSISTANT &&
                message.status != MessageStatus.STREAMING &&
                (message.content.isNotBlank() || message.reasoningContent?.isNotBlank() == true)
        }
        return if (recognized) Phase.InterpretOnly else Phase.RecognizeThenInterpret
    }
}
