package com.zcw.chatai.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.ui.md.MessageMarkdown
import com.zcw.chatai.ui.theme.ChatTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageItem(
    message: ChatMessageItem,
    onLongPress: (ChatMessageItem) -> Unit,
    onClick: (ChatMessageItem) -> Unit,
    onOpenImage: (ChatMessageItem, MessageImage) -> Unit,
    searchHits: List<InChatHit> = emptyList(),
    activeHit: InChatHit? = null,
    onActiveCenter: InChatCenterReport? = null,
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
                    HighlightedPlainText(
                        text = message.content,
                        hits = searchHits,
                        active = activeHit,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.bubbleUserText,
                        onActiveCenter = onActiveCenter,
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantTurnItem(
    group: List<ChatMessageItem>,
    streamingMessageId: String?,
    isCurrentTurn: Boolean,
    /** 打开这一段活动的摘要。参数是这段活动的 key。 */
    onOpenActivity: (String) -> Unit,
    meta: String?,
    /** 长按点的窗口坐标，悬浮菜单靠它定位。 */
    onLongPress: (ChatMessageItem, Offset) -> Unit,
    /** 报错行的「重试」。和尾轮无关，历史回合也保留。 */
    onRetry: (ChatMessageItem) -> Unit,
    onCopy: (ChatMessageItem) -> Unit,
    /** 尾轮才有。null 不画垃圾桶。 */
    onDelete: ((ChatMessageItem) -> Unit)?,
    /** 从这一条 AI 回复签出分支（复制该条及之前的消息到一个新会话）。 */
    onBranch: (ChatMessageItem) -> Unit,
    /** 尾轮的停止条才有。null 不画「继续」。 */
    onContinue: (() -> Unit)?,
    /** 尾轮的停止条才有。null 不画「重新生成」。 */
    onCancelledRegenerate: ((ChatMessageItem) -> Unit)?,
    searchHits: List<InChatHit> = emptyList(),
    activeHit: InChatHit? = null,
    searchQuery: String = "",
    onActiveCenter: InChatCenterReport? = null,
    modifier: Modifier = Modifier,
) {
    val pieces = remember(group, streamingMessageId) {
        ActivityLog.pieces(group, streamingMessageId)
    }
    val lastAssistant = group.lastOrNull { it.role == Role.ASSISTANT } ?: return
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(0.9f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isCurrentTurn && pieces.isEmpty()) {
                ActivityLog.latestLabel(emptyList(), turnActive = true)?.let { label ->
                    ActivityRow(label = label, onClick = {})
                }
            }
            pieces.forEachIndexed { index, piece ->
                when (piece) {
                    is TurnPiece.Body -> {
                        val message = group.firstOrNull { it.id == piece.messageId } ?: return@forEachIndexed
                        val streaming = message.id == streamingMessageId
                        if (!message.hasAssistantBody(streaming)) return@forEachIndexed
                        AssistantStep(
                            message = message,
                            isStreaming = streaming,
                            onLongPress = { offset -> onLongPress(message, offset) },
                            onRetry = { onRetry(message) },
                            onContinue = onContinue,
                            onCancelledRegenerate = onCancelledRegenerate?.let { regenerate ->
                                { regenerate(message) }
                            },
                            searchHits = searchHits.filter { it.messageId == message.id },
                            activeHit = activeHit?.takeIf { it.messageId == message.id },
                            searchQuery = searchQuery,
                            onActiveCenter = if (activeHit?.messageId == message.id) onActiveCenter else null,
                        )
                    }
                    is TurnPiece.Activity -> {
                        val label = ActivityLog.rowLabel(pieces, index, isCurrentTurn, streamingMessageId)
                        if (label != null) {
                            ActivityRow(label = label, onClick = { onOpenActivity(piece.key) })
                        }
                    }
                }
            }
            // 整回合只挂一次操作行，放在所有步骤（含工具块）之后；
            // 最后一步没有正文（纯报错回合）时不挂，避免出现复制空白。
            if (!isCurrentTurn && lastAssistant.visibleAnswer().isNotEmpty()) {
                MessageActions(
                    onCopy = { onCopy(lastAssistant) },
                    onBranch = { onBranch(lastAssistant) },
                    onDelete = onDelete?.let { delete -> { delete(lastAssistant) } },
                    meta = meta,
                )
            }
        }
    }
}

@Composable
private fun AssistantStep(
    message: ChatMessageItem,
    isStreaming: Boolean,
    onLongPress: (Offset) -> Unit,
    onRetry: () -> Unit,
    onContinue: (() -> Unit)?,
    onCancelledRegenerate: (() -> Unit)?,
    searchHits: List<InChatHit>,
    activeHit: InChatHit?,
    searchQuery: String,
    onActiveCenter: InChatCenterReport?,
) {
    val placed = remember(message.id) { PlacedMarkdown() }
    val body = message.visibleAnswer(streaming = isStreaming)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (body.isNotEmpty()) {
            MessageMarkdown(
                content = body,
                cacheable = !isStreaming,
                searchHits = searchHits,
                activeHit = activeHit,
                searchQuery = searchQuery,
                onActiveCenter = onActiveCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { placed.coordinates = it }
                    .pointerInput(message.id) {
                        detectTapGestures(
                            onLongPress = { local ->
                                val coords = placed.coordinates
                                if (coords != null && coords.isAttached) {
                                    onLongPress(coords.localToWindow(local))
                                }
                            },
                        )
                    },
            )
        }
        if (message.citations.isNotEmpty()) {
            val uriHandler = LocalUriHandler.current
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message.citations.distinctBy { it.url }.forEachIndexed { index, citation ->
                    Text(
                        text = "${index + 1}. ${citation.title?.takeIf { it.isNotBlank() } ?: citation.url}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { runCatching { uriHandler.openUri(citation.url) } },
                    )
                }
            }
        }
        when (message.status) {
            MessageStatus.ERROR -> ErrorRow(message.errorMessage, onRetry)
            MessageStatus.CANCELLED -> CancelledRow(
                onRetry = onCancelledRegenerate,
                onContinue = onContinue,
            )
            else -> message.errorMessage?.let { WarningRow(it) }
        }
    }
}

/** 思考和工具已经收进摘要行，这里只留正文、引用和状态。空壳不占一行，避免多出空白间隔。 */
private fun ChatMessageItem.hasAssistantBody(streaming: Boolean): Boolean =
    visibleAnswer(streaming).isNotEmpty() ||
        citations.isNotEmpty() ||
        status == MessageStatus.ERROR ||
        status == MessageStatus.CANCELLED ||
        errorMessage != null

@Composable
private fun MessageActions(
    onCopy: () -> Unit,
    onBranch: () -> Unit,
    onDelete: (() -> Unit)?,
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
        if (onDelete != null) {
            IconActionButton(
                contentDescription = "删除",
                onClick = onDelete,
                icon = Icons.Filled.Delete,
            )
        }
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
private fun CancelledRow(onRetry: (() -> Unit)?, onContinue: (() -> Unit)?) {
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
        if (onRetry != null) {
            Text(
                text = "重新生成",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.clickable(onClick = onRetry),
            )
        }
    }
}

/** 正文节点的布局坐标。长按回调里当场换成窗口坐标，节点已经卸掉就不弹菜单。 */
private class PlacedMarkdown {
    var coordinates: LayoutCoordinates? = null
}
