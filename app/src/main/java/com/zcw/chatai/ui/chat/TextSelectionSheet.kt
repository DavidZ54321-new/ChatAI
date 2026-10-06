package com.zcw.chatai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ui.theme.ChatTheme

/**
 * 从底部升起的选字层。正文是纯文本，拖选后走系统选择工具条（复制 / 全选）。
 * 关掉手势拖拽，避免划选时把弹层一起拖走；关闭靠右上角和返回键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextSelectionSheet(
    text: String,
    onDismiss: () -> Unit,
) {
    val colors = ChatTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = colors.codeBackground,
        contentColor = colors.codeOnBackground,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
            ) {
                Text(
                    text = "选择文本",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.codeOnBackground,
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.codeButtonBackground)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = colors.codeOnBackground,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.codeOnBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
