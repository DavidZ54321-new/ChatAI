package com.zcw.chatai.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.ui.md.MessageMarkdown
import com.zcw.chatai.ui.theme.ChatTheme
import com.zcw.chatai.ui.theme.SpikeMark

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageItem(
    message: ChatMessageItem,
    onLongPress: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: (MessageImage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val bubbleMaxWidth = maxWidth * 0.85f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.images.isNotEmpty()) {
                // 图片行占满宽度：少则靠右对齐（LazyRow 内部 End 排列），多则可横向滑动。
                MessageImageRow(images = message.images, onOpen = onOpenImage)
            }
            if (message.content.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.bubbleUser)
                        .combinedClickable(onClick = {}, onLongClick = onLongPress)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.bubbleUserText,
                    )
                }
            }
            MessageActions(
                onCopy = onCopy,
                onRegenerate = null,
                onDelete = onDelete,
                meta = null,
                alignEnd = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiMessageItem(
    message: ChatMessageItem,
    isStreaming: Boolean,
    meta: String?,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(0.9f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val reasoning = message.reasoning.orEmpty()
            if (reasoning.isNotBlank()) {
                ReasoningBlock(
                    reasoning = reasoning,
                    isStreaming = isStreaming,
                    answerStarted = message.content.isNotEmpty(),
                    reasoningMs = message.reasoningMs,
                )
            } else if (isStreaming && message.content.isEmpty()) {
                StreamingIndicator()
            }
            if (message.content.isNotEmpty()) {
                MessageMarkdown(
                    content = message.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = onLongPress),
                )
            }
            when (message.status) {
                MessageStatus.ERROR -> ErrorRow(message.errorMessage, onRetry)
                MessageStatus.CANCELLED -> CancelledRow(onRetry)
                else -> message.errorMessage?.let { WarningRow(it) }
            }
            if (isStreaming && message.content.isNotEmpty()) {
                StreamingIndicator()
            }
            if (!isStreaming) {
                MessageActions(
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onDelete = onDelete,
                    meta = meta,
                    alignEnd = false,
                )
            }
        }
    }
}

@Composable
private fun MessageActions(
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)?,
    onDelete: () -> Unit,
    meta: String?,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (alignEnd) Spacer(Modifier.weight(1f))
        IconActionButton(
            contentDescription = "复制",
            onClick = onCopy,
            painter = painterResource(R.drawable.ic_copy),
        )
        if (onRegenerate != null) {
            IconActionButton(
                contentDescription = "重新生成",
                onClick = onRegenerate,
                icon = Icons.Filled.Refresh,
            )
        }
        IconActionButton(
            contentDescription = "删除",
            onClick = onDelete,
            icon = Icons.Filled.Delete,
        )
        if (!alignEnd) {
            Spacer(Modifier.weight(1f))
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StreamingIndicator() {
    val transition = rememberInfiniteTransition(label = "streaming")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpikeMark(size = 16.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        Text(
            text = "思考中",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorRow(message: String?, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = message ?: "请求失败",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.error,
        )
        Text(
            text = "重试",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}

/** 有内容但被截断/受限的提示（例如 finish_reason=length），用琥珀色而非报错红。 */
@Composable
private fun WarningRow(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = ChatTheme.colors.accentAmber,
    )
}

@Composable
private fun CancelledRow(onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "已停止生成",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = "重新生成",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}
