package com.zcw.chatai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ui.theme.ChatTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DarkSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ChatTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.codeBackground,
        contentColor = colors.codeOnBackground,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.codeHeaderText) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            content = content,
        )
    }
}

@Composable
internal fun SheetAction(
    label: String,
    onClick: () -> Unit,
    color: Color = ChatTheme.colors.codeOnBackground,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}
