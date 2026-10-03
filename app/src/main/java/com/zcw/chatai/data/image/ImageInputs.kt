package com.zcw.chatai.data.image

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.Role

/**
 * 生图出站输入图的组装规则（纯函数，JVM 单测覆盖）。
 *
 * 「上一轮生成结果」是**静默强制带入**的：不写进用户消息附件、界面上也看不到，
 * 只在组请求时拼成图 1；用户自己传的图排在其后。
 */
object ImageInputs {

    /** 千问输入图上限（官方 1~3 张）。OpenAI 的上限在它自己的后端上。 */
    const val MAX_IMAGES = 3

    /**
     * 上一轮生成结果：会话里**最后一条带图片附件的 `ASSISTANT`** 消息的最后一张图。
     * 没有（首轮/一直报错）则为 null——首轮是纯文生图。
     */
    fun previousImage(messages: List<Message>): Attachment? =
        messages.lastOrNull { message ->
            message.role == Role.ASSISTANT && message.attachments.any { it.kind == AttachmentKind.IMAGE }
        }?.attachments?.lastOrNull { it.kind == AttachmentKind.IMAGE }

    /** 出站输入图：上一张永远在图 1 位，用户自己传的排在其后；合计不超过 [maxImages]。 */
    fun wireInputs(
        previous: Attachment?,
        user: List<Attachment>,
        maxImages: Int = MAX_IMAGES,
    ): List<Attachment> = (listOfNotNull(previous) + user).take(maxImages.coerceAtLeast(0))

    /** 用户本轮最多还能传几张（有上一张时它占掉一个名额）。 */
    fun userImageLimit(previous: Attachment?, maxImages: Int = MAX_IMAGES): Int =
        userSlotLimit(previous != null, maxImages, acceptsImageInput = true)

    /**
     * 用户还能传几张。[acceptsImageInput] 为 false 时是 0（模型不吃参考图）。
     * 有上一张时它占一个名额。
     */
    fun userSlotLimit(hasPrevious: Boolean, maxImages: Int, acceptsImageInput: Boolean): Int {
        if (!acceptsImageInput) return 0
        val slots = maxImages.coerceAtLeast(0)
        return slots - if (hasPrevious && slots > 0) 1 else 0
    }
}
