package com.zcw.chatai.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FloatingTopControls(
    onOpenDrawer: () -> Unit,
    onNewConversation: () -> Unit,
    onOverflow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBareButton(
            icon = Icons.Filled.Menu,
            contentDescription = "会话列表",
            onClick = onOpenDrawer,
        )
        Spacer(Modifier.weight(1f))
        IconBareButton(
            icon = Icons.Filled.Add,
            contentDescription = "新对话",
            onClick = onNewConversation,
        )
        IconBareButton(
            icon = Icons.Filled.MoreVert,
            contentDescription = "更多",
            onClick = onOverflow,
            iconSize = 22.dp,
        )
    }
}
