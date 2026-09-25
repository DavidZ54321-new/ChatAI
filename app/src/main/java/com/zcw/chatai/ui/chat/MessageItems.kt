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
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.ui.md.MessageMarkdown
import com.zcw.chatai.ui.theme.ChatTheme
import com.zcw.chatai.ui.theme.SpikeMark

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageItem(
    message: ChatMessageItem,
    onLongPress: (ChatMessageItem) -> Unit,
    onClick: (ChatMessageItem) -> Unit,
    onOpenImage: (ChatMessageItem, MessageImage) -> Unit,
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
                // 长按等同气泡长按：只有图没有文字时，这是够到消息菜单的唯一入口。
                MessageImageRow(
                    images = message.images,
                    onOpen = { image -> onOpenImage(message, image) },
                    onLongPress = { onLongPress(message) },
                )
            }
            if (message.content.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.bubbleUser)
                        .combinedClickable(onClick = { onClick(message) }, onLongClick = { onLongPress(message) })
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.bubbleUserText,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantTurnItem(
    group: List<ChatMessageItem>,
    streamingMessageId: String?,
    isCurrentTurn: Boolean,
    /** 用户展开工具/思考详情时回调：让列表临时松钉，别把内容拽走。 */
    onUserExpand: () -> Unit,
    meta: String?,
    onLongPress: (ChatMessageItem) -> Unit,
    onRetry: (ChatMessageItem) -> Unit,
    onCopy: (ChatMessageItem) -> Unit,
    onRegenerate: (ChatMessageItem) -> Unit,
    onDelete: (ChatMessageItem) -> Unit,
    /** 从这一条 AI 回复签出分支（复制该条及之前的消息到一个新会话）。 */
    onBranch: (ChatMessageItem) -> Unit,
    onContinue: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val lastAssistant = group.lastOrNull { it.role == Role.ASSISTANT } ?: return
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(0.9f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            group.forEach { message ->
                if (message.role == Role.TOOL) {
                    message.toolResult?.let { ToolCallBlock(it, onUserExpand = onUserExpand) }
                } else {
                    AssistantStep(
                        message = message,
                        isStreaming = message.id == streamingMessageId,
                        isCurrentTurn = isCurrentTurn,
                        onUserExpand = onUserExpand,
                        onLongPress = { onLongPress(message) },
                        onRetry = { onRetry(message) },
                        onContinue = onContinue,
                    )
                }
            }
            // 整回合只挂一次操作行，放在所有步骤（含工具块）之后；
            // 最后一步没有正文（纯报错回合）时不挂，避免出现复制空白。
            if (!isCurrentTurn && lastAssistant.content.isNotEmpty()) {
                MessageActions(
                    onCopy = { onCopy(lastAssistant) },
                    onRegenerate = { onRegenerate(lastAssistant) },
                    onBranch = { onBranch(lastAssistant) },
                    onDelete = { onDelete(lastAssistant) },
                    meta = meta,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantStep(
    message: ChatMessageItem,
    isStreaming: Boolean,
    isCurrentTurn: Boolean,
    onUserExpand: () -> Unit,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onContinue: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val reasoning = message.reasoning.orEmpty()
        if (reasoning.isNotBlank()) {
            ReasoningBlock(
                reasoning = reasoning,
                isStreaming = isStreaming,
                answerStarted = message.content.isNotEmpty(),
                reasoningMs = message.reasoningMs,
                onUserExpand = onUserExpand,
            )
        } else if (message.content.isEmpty() &&
            (isStreaming || (isCurrentTurn && message.status == MessageStatus.STREAMING))
        ) {
            // 兜底：当前回合的 DB 行还是 STREAMING 空壳（overlay 已撤、定稿内容未发射）时
            // 也占住高度，不让助手气泡塌成 0 高把视口钳回用户气泡。
            // 限定 isCurrentTurn：崩溃遗留的 STREAMING 行不是当前回合，不挂永久转圈。
            StreamingIndicator()
        }
        if (message.content.isNotEmpty()) {
            MessageMarkdown(
                content = message.content,
                cacheable = !isStreaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
            )
        }
        when (message.status) {
            MessageStatus.ERROR -> ErrorRow(message.errorMessage, onRetry)
            MessageStatus.CANCELLED -> CancelledRow(onRetry, onContinue)
            else -> message.errorMessage?.let { WarningRow(it) }
        }
        if (isStreaming && message.content.isNotEmpty()) {
            StreamingIndicator()
        }
    }
}

@Composable
private fun MessageActions(
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    onDelete: () -> Unit,
    meta: String?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconActionButton(
            contentDescription = "复制",
            onClick = onCopy,
            painter = painterResource(R.drawable.ic_copy),
        )
        IconActionButton(
            contentDescription = "创建分支",
            onClick = onBranch,
            painter = painterResource(R.drawable.ic_branch),
        )
        IconActionButton(
            contentDescription = "重新生成",
            onClick = onRegenerate,
            icon = Icons.Filled.Refresh,
        )
        IconActionButton(
            contentDescription = "删除",
            onClick = onDelete,
            icon = Icons.Filled.Delete,
        )
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
private fun CancelledRow(onRetry: () -> Unit, onContinue: (() -> Unit)? = null) {
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
        if (onContinue != null) {
            Text(
                text = "继续",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.clickable(onClick = onContinue),
            )
        }
        Text(
            text = "重新生成",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}
