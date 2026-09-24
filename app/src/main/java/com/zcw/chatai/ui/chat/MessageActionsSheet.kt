package com.zcw.chatai.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun MessageActionsSheet(
    canRegenerate: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    /** 编辑只对用户消息开放；null（助手消息）时不出现这一项。 */
    onEdit: (() -> Unit)? = null,
) {
    DarkSheet(onDismiss = onDismiss) {
        SheetAction(label = "复制", onClick = onCopy)
        if (onEdit != null) {
            SheetAction(label = "编辑", onClick = onEdit)
        }
        if (canRegenerate) {
            SheetAction(label = "重新生成", onClick = onRegenerate)
        }
        SheetAction(
            label = "删除",
            onClick = onDelete,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
