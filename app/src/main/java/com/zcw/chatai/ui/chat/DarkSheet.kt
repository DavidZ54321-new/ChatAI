package com.zcw.chatai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ui.theme.ChatTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DarkSheet(
    onDismiss: () -> Unit,
    scroll: Boolean = false,
    /** 表单类弹层（含输入框）要一次到位：默认的半屏态会把输入框顶到一半、按钮挤出屏幕。 */
    fullHeight: Boolean = false,
    /**
     * 表单类弹层还要自己让开键盘：M3 的 sheet 默认 windowInsets 只含系统栏（不含 IME），
     * 不 dodge 的话输入框和底部按钮会被输入法盖住。
     */
    imeAware: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ChatTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight),
        containerColor = colors.codeBackground,
        contentColor = colors.codeOnBackground,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.codeHeaderText) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 让在 scroll 之前：缩小的是滚动视口，内容才能滚到键盘之上。
                .then(if (imeAware) Modifier.imePadding() else Modifier)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(bottom = 20.dp),
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
