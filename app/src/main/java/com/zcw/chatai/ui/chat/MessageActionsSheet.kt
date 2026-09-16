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
) {
    DarkSheet(onDismiss = onDismiss) {
        SheetAction(label = "复制", onClick = onCopy)
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
