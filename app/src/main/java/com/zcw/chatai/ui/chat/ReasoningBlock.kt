package com.zcw.chatai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.data.ai.REASONING_PREVIEW_CHARS
import com.zcw.chatai.data.ai.collapseReasoningWhitespace
import com.zcw.chatai.data.ai.formatReasoningDuration
import com.zcw.chatai.data.ai.reasoningTickerText

/**
 * 思考过程。像真实产品那样 **没有背景、没有边框、没有卡片**，就是正文上方的一行：
 * 时钟 + 「已深度思考 Ns」+ 思考摘要 + ›。点一下展开完整思维链。
 *
 * 时长来自消息里的 `reasoningMs`：流式时它就是当前值（同一个测量），冷启动从库里读回来，
 * 所以重开 App 秒数还在。null 或不足 1 秒时不显示数字。
 *
 * 刚发出去默认收成一行。生成中摘要跟到最新；结束后单行省略，
 * 不再给每一条历史思维链挂无限跑马灯。不自动展开整段思维链。
 */
@Composable
fun ReasoningBlock(
    reasoning: String,
    isStreaming: Boolean,
    answerStarted: Boolean,
    reasoningMs: Long?,
    modifier: Modifier = Modifier,
    /** 用户展开时回调：让外层列表临时松钉，别把正在读的内容拽走。 */
    onUserExpand: () -> Unit = {},
) {
    if (reasoning.isBlank() && !isStreaming) return
    val scheme = MaterialTheme.colorScheme
    var expandedByUser by rememberSaveable { mutableStateOf(false) }
    val followEnd = isStreaming && !answerStarted
    // 生成中要句尾，得扫整段；历史行只要开头一段。
    val preview = if (followEnd) {
        collapseReasoningWhitespace(reasoning)
    } else {
        collapseReasoningWhitespace(reasoning, maxChars = REASONING_PREVIEW_CHARS)
    }
    val duration = formatReasoningDuration(reasoningMs)

    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!expandedByUser) onUserExpand()
                    expandedByUser = !expandedByUser
                }
                .padding(vertical = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_schedule),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = when {
                    followEnd -> "思考中…"
                    duration != null -> "已深度思考 $duration"
                    else -> "已深度思考"
                },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (preview != null) {
                ReasoningTicker(
                    text = "· ${reasoningTickerText(preview, followEnd)}",
                    followEnd = followEnd,
                    modifier = Modifier.weight(1f),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expandedByUser) "收起思考过程" else "展开思考过程",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expandedByUser) 90f else 0f),
            )
        }
        if (expandedByUser) {
            // 高度上限与工具结果块统一（视窗高 30%），超出在块内滚动、不撑长消息。
            BlockScrollContainer(
                maxHeight = ChatMetrics.expandedBlockMaxHeight(
                    LocalWindowInfo.current.containerDpSize.height,
                ),
            ) {
                Text(
                    text = reasoning.ifBlank { "……" },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReasoningTicker(
    text: String,
    followEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    if (!followEnd) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }
    val scroll = rememberScrollState()
    LaunchedEffect(text, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .horizontalScroll(scroll, enabled = false),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}
