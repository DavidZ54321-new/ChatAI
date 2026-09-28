package com.zcw.chatai.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.data.ai.formatReasoningDuration
import com.zcw.chatai.data.model.ToolStatus
import com.zcw.chatai.ui.theme.ChatTheme
import java.time.ZoneId

private const val SLIDE_IN_MS = 260
private const val SLIDE_OUT_MS = 200

/**
 * 盖在聊天上面的链路页。从右上角菜单打开，系统返回关掉。
 * 主列表一直留在下面，点一轮时滚动位置还在。
 */
@Composable
fun TurnOutlineOverlay(
    visible: Boolean,
    messages: List<ChatMessageItem>,
    conversationTitle: String,
    hasBranchHeader: Boolean,
    isTurnActive: Boolean,
    entryIndex: Int,
    onDismiss: () -> Unit,
    onJump: (TurnStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val turns = remember(messages) { TurnOutline.of(messages) }
    val groups = remember(messages) { MessageGroups.of(messages) }
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = slideInHorizontally(tween(SLIDE_IN_MS)) { -it },
        exit = slideOutHorizontally(tween(SLIDE_OUT_MS)) { -it },
    ) {
        TurnOutlineSheet(
            turns = turns,
            conversationTitle = conversationTitle,
            initialIndex = TurnOutline.turnIndexAtLazyIndex(entryIndex, hasBranchHeader, turns),
            modifier = Modifier.fillMaxSize(),
            onJump = onJump,
            sticks = { stop -> TurnOutline.sticksToBottom(stop, groups, isTurnActive) },
        )
    }
}

@Composable
private fun TurnOutlineSheet(
    turns: List<TurnStop>,
    conversationTitle: String,
    initialIndex: Int,
    onJump: (TurnStop) -> Unit,
    sticks: (TurnStop) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { System.currentTimeMillis() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex.coerceIn(0, (turns.size - 1).coerceAtLeast(0)),
    )
    Column(
        modifier = modifier
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = "链路",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
            if (conversationTitle.isNotBlank()) {
                Text(
                    text = conversationTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (turns.isEmpty()) {
            Text(
                text = "还没有可以跳转的记录",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(turns, key = { it.anchorKey }) { stop ->
                TurnOutlineCard(
                    stop = stop,
                    timeLabel = TurnOutline.timeLabel(stop.sentAt, now, zone),
                    pendingReply = sticks(stop) && stop.assistantText.isBlank() && stop.tools.isEmpty(),
                    onJump = { onJump(stop) },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp)
                        .height(1.dp)
                        .background(scheme.onSurface.copy(alpha = 0.08f)),
                )
            }
        }
    }
}

@Composable
private fun TurnOutlineCard(
    stop: TurnStop,
    timeLabel: String,
    pendingReply: Boolean,
    onJump: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val blockHeight = ChatMetrics.outlineBlockMaxHeight(
        LocalWindowInfo.current.containerDpSize.height,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 时间和你发的内容点一下就跳回。思考 / 工具行自己处理点击，展开时不跳。
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onJump)) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            if (stop.userText.isNotEmpty()) {
                Text(
                    text = stop.userText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = TurnOutline.USER_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (stop.attachmentsLabel != null) {
                Text(
                    text = stop.attachmentsLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (stop.reasoning.isNotEmpty() || stop.tools.isNotEmpty()) {
            Column {
                stop.reasoning.forEach { row ->
                    key(row.messageId) {
                        OutlineFold(
                            title = reasoningTitle(row.reasoningMs),
                            body = row.text,
                            blockHeight = blockHeight,
                            showClock = true,
                        )
                    }
                }
                stop.tools.forEach { row ->
                    key(row.messageId) {
                        OutlineFold(
                            title = toolRowTitle(row.result),
                            body = toolBody(row.result),
                            blockHeight = blockHeight,
                            showClock = false,
                        )
                    }
                }
            }
        }
        if (stop.assistantText.isNotEmpty()) {
            Text(
                text = stop.assistantText,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = TurnOutline.ASSISTANT_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onJump),
            )
        } else if (pendingReply) {
            Text(
                text = "生成中…",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onJump),
            )
        }
    }
}

@Composable
private fun OutlineFold(
    title: String,
    body: String,
    blockHeight: androidx.compose.ui.unit.Dp,
    showClock: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        ) {
            if (showClock) {
                Icon(
                    painter = painterResource(R.drawable.ic_schedule),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded && body.isNotBlank()) {
            BlockScrollContainer(maxHeight = blockHeight) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}

private fun reasoningTitle(reasoningMs: Long?): String {
    val duration = formatReasoningDuration(reasoningMs)
    return if (duration == null) "已深度思考" else "已深度思考 $duration"
}

private fun toolRowTitle(result: com.zcw.chatai.data.model.ToolResult): String {
    val title = toolCallTitle(result)
    val failed = if (result.status == ToolStatus.FAILED) " · 失败" else ""
    val running = if (result.status == ToolStatus.RUNNING) "…" else ""
    return if (result.detail.isBlank()) "$title$running$failed" else "$title$running · ${result.detail}$failed"
}

private fun toolBody(result: com.zcw.chatai.data.model.ToolResult): String {
    val lines = mutableListOf<String>()
    for (source in result.sources) {
        val label = source.title?.takeIf { it.isNotBlank() } ?: source.url
        if (label.isNotBlank()) lines += label
    }
    if (result.text.isNotBlank()) lines += result.text.take(2000)
    return lines.joinToString("\n")
}
