package com.zcw.chatai.ui.md

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ui.chat.BlockScrollContainer
import com.zcw.chatai.ui.chat.ChatMetrics
import com.zcw.chatai.ui.theme.ChatTheme

/** 全屏 viewer 打开器：由 ChatScreen 提供（previewTarget state），列表内卡片只管调用。 */
val LocalPreviewOpener = compositionLocalOf<((PreviewSegment.Preview) -> Unit)?> { null }

/**
 * SVG / HTML / mermaid 列表卡片：代码常驻（块内滚动），点「预览」进全屏 viewer。
 *
 * 三种语言都不在列表里渲染：SVG 动效与 HTML 需要 WebView，mermaid 大图内嵌会超时、
 * 位图还有截断，统一进 viewer 渐进渲染 + 缩放。这里只留一个入口。
 */
@Composable
fun PreviewCard(
    segment: PreviewSegment.Preview,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val opener = LocalPreviewOpener.current
    val colors = ChatTheme.colors
    val title = when (segment.language) {
        PreviewLanguage.SVG -> "SVG 预览"
        PreviewLanguage.HTML -> "HTML 预览"
        PreviewLanguage.MERMAID -> "mermaid 预览"
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (opener != null) {
                TextButton(onClick = { opener(segment) }) { Text("预览") }
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(segment.code)) }) { Text("复制") }
        }
        // 与思考块/工具结果块统一：视窗高 30% 上限，超出在块内滚动，不撑长消息。
        BlockScrollContainer(
            maxHeight = ChatMetrics.expandedBlockMaxHeight(
                LocalWindowInfo.current.containerDpSize.height,
            ),
        ) {
            Text(
                text = segment.code,
                style = ChatTheme.typography.code.copy(color = colors.codeOnBackground),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
