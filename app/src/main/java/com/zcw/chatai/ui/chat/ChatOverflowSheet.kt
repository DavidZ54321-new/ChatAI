package com.zcw.chatai.ui.chat

import androidx.compose.runtime.Composable

@Composable
fun ChatOverflowSheet(
    onDismiss: () -> Unit,
    onClearConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    DarkSheet(onDismiss = onDismiss) {
        SheetAction(label = "清空当前会话", onClick = onClearConversation)
        SheetAction(label = "设置", onClick = onOpenSettings)
    }
}
