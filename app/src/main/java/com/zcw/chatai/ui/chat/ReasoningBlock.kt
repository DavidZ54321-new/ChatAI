package com.zcw.chatai.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ui.theme.ChatTheme
import com.zcw.chatai.ui.theme.SpikeMark

/**
 * 思考过程。思考模式默认开启，思维链可能比回答还长，所以：
 * 生成中展开并实时滚动；回答开始后被顶上去，自动折叠成一行，点一下能再展开。
 */
@Composable
fun ReasoningBlock(
    reasoning: String,
    isStreaming: Boolean,
    answerStarted: Boolean,
    seconds: Int?,
    modifier: Modifier = Modifier,
) {
    if (reasoning.isBlank() && !isStreaming) return
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    var expandedByUser by rememberSaveable { mutableStateOf(false) }
    val expanded = if (isStreaming && !answerStarted) true else expandedByUser

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceSoft)
            .border(1.dp, colors.hairlineSoft, RoundedCornerShape(12.dp))
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandedByUser = !expanded }
                .padding(vertical = 2.dp),
        ) {
            SpikeMark(size = 14.dp, color = scheme.primary)
            Text(
                text = when {
                    isStreaming && !answerStarted -> "思考中…"
                    seconds != null -> "已深度思考 ${seconds}s"
                    else -> "已深度思考"
                },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Box(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
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
