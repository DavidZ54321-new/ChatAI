package com.zcw.chatai.ui.chat

import com.zcw.chatai.data.ai.REASONING_PREVIEW_CHARS
import com.zcw.chatai.data.ai.collapseReasoningWhitespace
import com.zcw.chatai.data.ai.formatReasoningDuration
import com.zcw.chatai.data.ai.reasoningTickerText
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus

/**
 * 一个助手回合里要收成一行的活动：按发生顺序交错的思考段和工具调用。
 * 纯数据，JVM 单测覆盖。思考还在进行时收起行跟当前步骤；思考结束后统一写「已思考完成」。
 * 秒数留在摘要时间线的思考条目上。
 */
sealed interface ActivityStep {
    val messageId: String

    data class Thinking(
        override val messageId: String,
        val text: String,
        val reasoningMs: Long?,
        /** 这条助手消息正在流式输出。 */
        val streaming: Boolean,
        /** 正文已经开始，收起行不再跟着思维链句尾跑。 */
        val answerStarted: Boolean,
    ) : ActivityStep

    data class Tool(
        override val messageId: String,
        val result: ToolResult,
    ) : ActivityStep
}

/**
 * 一个助手回合拆开后的展示块。正文和活动按发生顺序交替，
 * 先写出的正文留在对应活动上方，后面的正文把活动分成多段。
 */
sealed interface TurnPiece {
    data class Body(val messageId: String, val hasText: Boolean) : TurnPiece

    data class Activity(val key: String, val steps: List<ActivityStep>) : TurnPiece
}

/** 收起那一行的文案。[followEnd] 为真时界面跟到句尾，而不是停在开头。 */
data class ActivityRowLabel(
    val text: String,
    val followEnd: Boolean,
)

object ActivityLog {

    /** 全部活动步骤，按发生顺序。测试和调用方不用再自己走一遍消息。 */
    fun steps(messages: List<ChatMessageItem>, streamingMessageId: String?): List<ActivityStep> =
        pieces(messages, streamingMessageId)
            .filterIsInstance<TurnPiece.Activity>()
            .flatMap { it.steps }

    /**
     * 按发生顺序拆开展示。正文是唯一的分隔：
     * 写出一段正文时，把它前面积累的思考和工具收成一个气泡，放在这段正文上面。
     * 两段正文之间紧挨着的一长串不再各占一行。
     */
    fun pieces(messages: List<ChatMessageItem>, streamingMessageId: String?): List<TurnPiece> {
        val pieces = mutableListOf<TurnPiece>()
        val activity = mutableListOf<ActivityStep>()
        fun flush() {
            if (activity.isEmpty()) return
            pieces += TurnPiece.Activity(key = activity.first().messageId, steps = activity.toList())
            activity.clear()
        }
        for (message in messages) {
            when (message.role) {
                Role.TOOL -> {
                    val result = message.toolResult ?: continue
                    activity += ActivityStep.Tool(message.id, result)
                }
                Role.ASSISTANT -> {
                    val reasoning = message.reasoning?.takeIf { it.isNotBlank() }
                    if (reasoning != null) {
                        val streaming = message.id == streamingMessageId
                        activity += ActivityStep.Thinking(
                            messageId = message.id,
                            text = reasoning,
                            reasoningMs = message.reasoningMs,
                            streaming = streaming,
                            answerStarted = message.content.isNotEmpty(),
                        )
                    }
                    if (message.content.isNotEmpty() || message.hasStatusRow()) {
                        flush()
                        pieces += TurnPiece.Body(message.id, hasText = message.content.isNotEmpty())
                    }
                }
                Role.USER, Role.SYSTEM -> Unit
            }
        }
        flush()
        return pieces
    }

    /** 这一段气泡的步骤。没有这段时返回 null。 */
    fun stepsOf(pieces: List<TurnPiece>, key: String): List<ActivityStep>? =
        pieces.firstNotNullOfOrNull { piece ->
            (piece as? TurnPiece.Activity)?.takeIf { it.key == key }?.steps
        }

    /**
     * 只有最后一段活动、而且后面还没有开始写正文时，收起行才跟着当前步骤。
     * 更早的气泡保持「已思考完成」，不受后面那段影响。
     */
    fun isLive(
        pieces: List<TurnPiece>,
        key: String,
        turnActive: Boolean,
        streamingMessageId: String?,
    ): Boolean {
        val index = pieces.indexOfFirst { it is TurnPiece.Activity && it.key == key }
        return isLive(pieces, index, turnActive, streamingMessageId)
    }

    fun isLive(
        pieces: List<TurnPiece>,
        index: Int,
        turnActive: Boolean,
        streamingMessageId: String?,
    ): Boolean {
        if (!turnActive || pieces.getOrNull(index) !is TurnPiece.Activity) return false
        if (index != pieces.indexOfLast { it is TurnPiece.Activity }) return false
        return pieces.drop(index + 1).none { later ->
            later is TurnPiece.Body && later.hasText && later.messageId == streamingMessageId
        }
    }

    fun rowLabel(
        pieces: List<TurnPiece>,
        index: Int,
        turnActive: Boolean,
        streamingMessageId: String?,
    ): ActivityRowLabel? {
        val piece = pieces.getOrNull(index) as? TurnPiece.Activity ?: return null
        return latestLabel(piece.steps, isLive(pieces, index, turnActive, streamingMessageId))
    }

    private fun ChatMessageItem.hasStatusRow(): Boolean =
        status == MessageStatus.ERROR ||
            status == MessageStatus.CANCELLED ||
            errorMessage != null ||
            citations.isNotEmpty()

    /**
     * 收起行。正在流式、正文还没开始的思考优先；否则进行中的工具。
     * 思考已经结束（回合停了，或正文已经开始）统一是「已思考完成」。
     * 回合还在跑、但下一步还没写出来时是「思考中…」。没有任何步骤且回合已停则不画行。
     */
    fun latestLabel(steps: List<ActivityStep>, turnActive: Boolean): ActivityRowLabel? {
        for (step in steps.asReversed()) {
            if (step is ActivityStep.Thinking && step.streaming && !step.answerStarted) {
                val collapsed = collapseReasoningWhitespace(step.text)
                return if (collapsed == null) {
                    ActivityRowLabel("思考中…", followEnd = false)
                } else {
                    ActivityRowLabel(collapsed, followEnd = true)
                }
            }
        }
        for (step in steps.asReversed()) {
            if (step is ActivityStep.Tool && step.result.status == ToolStatus.RUNNING) {
                return ActivityRowLabel(toolLine(step.result), followEnd = false)
            }
        }
        if (steps.isEmpty()) {
            return if (turnActive) ActivityRowLabel("思考中…", followEnd = false) else null
        }
        val writingAnswer = steps.any { step ->
            step is ActivityStep.Thinking && step.streaming && step.answerStarted
        }
        return if (turnActive && !writingAnswer) {
            ActivityRowLabel("思考中…", followEnd = false)
        } else {
            ActivityRowLabel("已思考完成", followEnd = false)
        }
    }

    /** 卡片时间线上的一行。思考带秒数；收起行不走这里。 */
    fun line(step: ActivityStep): String = when (step) {
        is ActivityStep.Thinking -> thinkingLine(step)
        is ActivityStep.Tool -> toolLine(step.result)
    }

    fun isFailed(step: ActivityStep): Boolean =
        step is ActivityStep.Tool && step.result.status == ToolStatus.FAILED

    /** 这一步的正文还在涨，详情页才允许贴底跟随。思考要等正文开始才停，工具要等跑完。 */
    fun followsTail(step: ActivityStep): Boolean = when (step) {
        is ActivityStep.Thinking -> step.streaming && !step.answerStarted
        is ActivityStep.Tool -> step.result.status == ToolStatus.RUNNING
    }

    fun toolLine(result: ToolResult): String {
        val title = toolCallTitle(result)
        val head = if (result.status == ToolStatus.RUNNING) "$title…" else title
        return if (result.detail.isBlank()) head else "$head · ${result.detail}"
    }

    private fun thinkingLine(step: ActivityStep.Thinking): String {
        val collapsed = collapseReasoningWhitespace(step.text)
        val live = step.streaming && !step.answerStarted
        if (live && collapsed == null) return "思考中…"
        val preview = when {
            collapsed == null -> null
            live -> reasoningTickerText(collapsed, followEnd = true)
            else -> collapseReasoningWhitespace(step.text, maxChars = REASONING_PREVIEW_CHARS)
        }
        val duration = formatReasoningDuration(step.reasoningMs)
        val head = when {
            live -> "思考中…"
            duration != null -> "已深度思考 $duration"
            else -> "已深度思考"
        }
        return if (preview.isNullOrBlank()) head else "$head · $preview"
    }
}
