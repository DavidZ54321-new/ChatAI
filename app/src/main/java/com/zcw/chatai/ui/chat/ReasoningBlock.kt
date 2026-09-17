package com.zcw.chatai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.data.ai.formatReasoningDuration
import com.zcw.chatai.data.ai.reasoningPreview

/**
 * 思考过程。像真实产品那样 **没有背景、没有边框、没有卡片**，就是正文上方的一行：
 * 时钟 + 「已深度思考 Ns」+ 思考首行摘要 + ›。点一下展开完整思维链。
 *
 * 时长来自消息里的 `reasoningMs`：流式时它就是当前值（同一个测量），冷启动从库里读回来，
 * 所以重开 App 秒数还在。null 或不足 1 秒时不显示数字。
 *
 * 思考模式默认开启，思维链可能比回答还长，所以生成中先展开并实时滚动，
 * 回答开始后自动折叠成一行。
 */
@Composable
fun ReasoningBlock(
    reasoning: String,
    isStreaming: Boolean,
    answerStarted: Boolean,
    reasoningMs: Long?,
    modifier: Modifier = Modifier,
) {
    if (reasoning.isBlank() && !isStreaming) return
    val scheme = MaterialTheme.colorScheme
    var expandedByUser by rememberSaveable { mutableStateOf(false) }
    val expanded = if (isStreaming && !answerStarted) true else expandedByUser
    val preview = reasoningPreview(reasoning)
    val duration = formatReasoningDuration(reasoningMs)

    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandedByUser = !expanded }
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
                    isStreaming && !answerStarted -> "思考中…"
                    duration != null -> "已深度思考 $duration"
                    else -> "已深度思考"
                },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (preview != null) {
                Text(
                    text = "· $preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
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
