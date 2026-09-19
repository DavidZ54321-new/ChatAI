package com.zcw.chatai.data.ai

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.Role

/**
 * 模型可见的一张图（纯数据）。[globalIndex] 就是 `find_similar_images` 的 `image_index`。
 */
data class VisibleImage(
    val globalIndex: Int,
    val attachment: Attachment,
    /** 来源轮次标注（英文）。 */
    val turnLabel: String,
    val indexInTurn: Int,
    val countInTurn: Int,
)

/**
 * 模型可见图片清单（纯函数，JVM 可测）：图片编号与来源标注的**唯一真相源**。
 *
 * 与 [AttachmentRetention] 共用保留规则，所以「模型能看到的图」与
 * 「以图搜图能索引到的图」永远一致。轮次按 **user 消息**计数（不是按带图消息），
 * 这样「previous turn」就是字面上的上一轮用户消息。
 */
object ToolImageInventory {

    fun visibleImages(messages: List<Message>, retainTurns: Int): List<VisibleImage> {
        val kept = AttachmentRetention.keptMessageIds(messages, retainTurns)
        val userMessages = messages.filter { it.role == Role.USER }
        val lastIndex = userMessages.lastIndex
        val result = ArrayList<VisibleImage>()
        var global = 0
        userMessages.forEachIndexed { index, message ->
            if (message.id !in kept) return@forEachIndexed
            val images = message.attachments.filter { it.kind == AttachmentKind.IMAGE }
            if (images.isEmpty()) return@forEachIndexed
            val turnOrdinal = lastIndex - index
            images.forEachIndexed { imageIndex, attachment ->
                global++
                result += VisibleImage(
                    globalIndex = global,
                    attachment = attachment,
                    turnLabel = turnLabel(turnOrdinal),
                    indexInTurn = imageIndex + 1,
                    countInTurn = images.size,
                )
            }
        }
        return result
    }

    /** 插在图片块前面的来源标注（英文，和工具文本语言一致）。 */
    fun label(image: VisibleImage): String =
        "[Image ${image.globalIndex} | ${image.turnLabel} ${image.indexInTurn}/${image.countInTurn}]"

    fun turnLabel(turnOrdinal: Int): String = when (turnOrdinal) {
        0 -> "this turn"
        1 -> "previous turn"
        else -> "$turnOrdinal turns back"
    }
}
