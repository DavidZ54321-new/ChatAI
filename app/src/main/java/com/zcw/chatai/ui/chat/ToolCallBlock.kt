package com.zcw.chatai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.model.ToolKind
import com.zcw.chatai.data.model.ToolResult
import com.zcw.chatai.data.model.ToolStatus
import com.zcw.chatai.ui.theme.ChatTheme

/**
 * 一次工具调用的内联可折叠块：标题 + 查询/URL + 来源列表（图搜则是缩略图网格）。
 * 折叠模式沿用 [ReasoningBlock]：无背景、无边框，就是正文上方的一行。
 */
@Composable
fun ToolCallBlock(
    result: ToolResult,
    modifier: Modifier = Modifier,
    /** 用户展开时回调：让外层列表临时松钉，别把正在读的内容拽走。 */
    onUserExpand: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    val title = toolTitle(result)
    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!expanded) onUserExpand()
                    expanded = !expanded
                }
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = if (result.status == ToolStatus.RUNNING) "$title…" else title,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = "· ${result.detail}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (result.status == ToolStatus.FAILED) {
                Text(
                    text = "失败",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.error,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起工具结果" else "展开工具结果",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            // 与思考块统一：视窗高 30% 上限，超出在块内滚动，不把整条消息撑长。
            BlockScrollContainer(
                maxHeight = ChatMetrics.expandedBlockMaxHeight(
                    LocalWindowInfo.current.containerDpSize.height,
                ),
            ) {
                if (result.images.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(result.images, key = { _, image -> image.url }) { index, image ->
                            RemoteImage(
                                url = image.url,
                                contentDescription = image.title.ifBlank { "搜索结果图片" },
                                maxEdge = 512,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(92.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ChatTheme.colors.surfaceSoft)
                                    .clickable { previewIndex = index },
                            )
                        }
                    }
                }
                result.sources.forEach { source ->
                    Text(
                        text = source.title ?: source.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, bottom = 2.dp),
                    )
                }
                if (result.text.isNotBlank()) {
                    Text(
                        text = result.text.take(2000),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                }
            }
        }
    }
    previewIndex?.takeIf { result.images.isNotEmpty() }?.let { index ->
        RemoteImagePreviewDialog(
            images = result.images.map { image ->
                PreviewImage(url = image.url, title = image.title.takeIf { it.isNotBlank() })
            },
            initialIndex = index,
            onDismiss = { previewIndex = null },
        )
    }
}

private fun toolTitle(result: ToolResult): String = when (result.kind) {
    ToolKind.FETCH -> "网页抓取"
    ToolKind.IMAGE_SEARCH -> "文搜图"
    ToolKind.IMAGE_SIMILAR -> "以图搜图"
    // 旧数据没有 kind：沿用升级前的启发式（抓取行的 detail 是 URL / 「正在抓取」）。
    ToolKind.SEARCH ->
        if (result.detail.startsWith("http") || result.detail.startsWith("正在抓取")) "网页抓取" else "联网搜索"
}
