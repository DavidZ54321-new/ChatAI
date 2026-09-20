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
    /** 该图所在 user 消息的绝对轮次（全会话 1-based，只增不改，见 [ToolImageInventory.turnNumbers]）。 */
    val turnNumber: Int,
    val indexInTurn: Int,
    val countInTurn: Int,
)

/**
 * 模型可见图片清单（纯函数，JVM 可测）：图片编号与来源标注的**唯一真相源**。
 *
 * 与 [AttachmentRetention] 共用保留规则，所以「模型能看到的图」与
 * 「以图搜图能索引到的图」永远一致。
 *
 * 缓存友好：轮次是**绝对轮次**（全会话 user 消息的 1-based 序号），纯追加新消息时
 * 历史标注的字节原样保留。相对口径（this/previous turn）会让每轮请求都改写全部历史标注，
 * 直接打断服务端的 KV 前缀缓存。调用方务必用**全量历史**算 [turnNumbers]，
 * 再用出站窗口调 [visibleImages]。
 *
 * 精确起见：只有 turn 部分是追加稳定的；[VisibleImage.globalIndex] 仍按可见集从 1 编号，
 * 早图被保留规则/窗口截断淘汰时 survivors 会重排——但那是可见集真的变了，
 * 对应的前缀 token 本来也不存在了，不属于"可保住的缓存"。
 */
object ToolImageInventory {

    /**
     * 全会话 user 轮次表：message id → 1-based 绝对轮次（按传入顺序给 user 消息编号）。
     *
     * 故意按原始 user 行计数（不考虑出站窗口的过滤/截断）：行一旦落库就不可变，
     * 编号永远稳定；被窗口过滤掉的空白 user 行只会留下可忽略的断号，
     * 不影响排序与稳定性。
     */
    fun turnNumbers(messages: List<Message>): Map<String, Int> {
        val numbers = HashMap<String, Int>(messages.size)
        var turn = 0
        for (message in messages) {
            if (message.role == Role.USER) {
                turn++
                numbers[message.id] = turn
            }
        }
        return numbers
    }

    /**
     * @param messages 出站窗口（决定保留/可见范围）。
     * @param turnNumbers 绝对轮次表，务必按**全量历史**算（见 [turnNumbers]）；
     *   查不到的 id 回落到窗口内序号（防御性兜底，生产调用不应触发）。
     */
    fun visibleImages(
        messages: List<Message>,
        retainTurns: Int,
        turnNumbers: Map<String, Int>,
    ): List<VisibleImage> {
        val kept = AttachmentRetention.keptMessageIds(messages, retainTurns)
        val userMessages = messages.filter { it.role == Role.USER }
        val result = ArrayList<VisibleImage>()
        var global = 0
        userMessages.forEachIndexed { index, message ->
            if (message.id !in kept) return@forEachIndexed
            val images = message.attachments.filter { it.kind == AttachmentKind.IMAGE }
            if (images.isEmpty()) return@forEachIndexed
            val turn = turnNumbers[message.id] ?: (index + 1)
            images.forEachIndexed { imageIndex, attachment ->
                global++
                result += VisibleImage(
                    globalIndex = global,
                    attachment = attachment,
                    turnNumber = turn,
                    indexInTurn = imageIndex + 1,
                    countInTurn = images.size,
                )
            }
        }
        return result
    }

    /** 插在图片块前面的来源标注（英文，和工具文本语言一致）。 */
    fun label(image: VisibleImage): String =
        "[Image ${image.globalIndex} | turn ${image.turnNumber} ${image.indexInTurn}/${image.countInTurn}]"
}
