package com.zcw.chatai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zcw.chatai.R
import com.zcw.chatai.ui.theme.ChatTheme
import kotlin.math.roundToInt

private val MenuShape = RoundedCornerShape(16.dp)
private val MenuWidth = 220.dp
private val MenuMargin = 12.dp
private val MenuGap = 8.dp

/**
 * 助手正文的长按菜单。锚点、状态栏底边、输入栏顶边都是窗口坐标。
 * 边距在这里加：放不下就翻到按压点上方，避免盖住状态栏和输入栏。
 */
@Composable
internal fun AssistantContextMenu(
    anchorInWindow: Offset,
    statusBarBottomInWindow: Int,
    composerTopInWindow: Int?,
    canSelectText: Boolean,
    /** 这条助手回合已不是最后一轮时不给重新生成，避免截掉后面的对话。 */
    canRegenerate: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val density = LocalDensity.current
    val marginPx = with(density) { MenuMargin.roundToPx() }
    val gapPx = with(density) { MenuGap.roundToPx() }
    val anchor = IntOffset(anchorInWindow.x.roundToInt(), anchorInWindow.y.roundToInt())
    val topLimit = statusBarBottomInWindow + marginPx
    val bottomLimit = composerTopInWindow?.minus(marginPx) ?: Int.MAX_VALUE
    val provider = remember(anchor, marginPx, gapPx, topLimit, bottomLimit) {
        ContextMenuPositionProvider(anchor, marginPx, gapPx, bottomLimit, topLimit)
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // 阴影画在窗口外会被裁掉，留一圈给它。
        Box(Modifier.padding(8.dp)) {
            MenuCard(
                canSelectText = canSelectText,
                canRegenerate = canRegenerate,
                onCopy = onCopy,
                onSelectText = onSelectText,
                onRegenerate = onRegenerate,
            )
        }
    }
}

@Composable
private fun MenuCard(
    canSelectText: Boolean,
    canRegenerate: Boolean,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val colors = ChatTheme.colors
    val labelColor = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .width(MenuWidth)
            .shadow(elevation = 8.dp, shape = MenuShape)
            .clip(MenuShape)
            .background(colors.surfaceCard),
    ) {
        MenuRow(
            label = "复制",
            painter = painterResource(R.drawable.ic_copy),
            tint = labelColor,
            onClick = onCopy,
        )
        if (canSelectText) {
            MenuDivider()
            MenuRow(
                label = "选择文本",
                painter = painterResource(R.drawable.ic_select_text),
                tint = labelColor,
                onClick = onSelectText,
            )
        }
        if (canRegenerate) {
            MenuDivider()
            MenuRow(
                label = "重新生成",
                icon = Icons.Filled.Refresh,
                tint = labelColor,
                onClick = onRegenerate,
            )
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ChatTheme.colors.hairline),
    )
}

@Composable
private fun MenuRow(
    label: String,
    tint: Color,
    onClick: () -> Unit,
    painter: Painter? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            painter != null -> Icon(
                painter = painter,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

private class ContextMenuPositionProvider(
    private val anchorInWindow: IntOffset,
    private val marginPx: Int,
    private val gapPx: Int,
    private val bottomLimitPx: Int,
    private val topLimitPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val place = placeContextMenu(
            anchorX = anchorInWindow.x,
            anchorY = anchorInWindow.y,
            menuWidth = popupContentSize.width,
            menuHeight = popupContentSize.height,
            windowWidth = windowSize.width,
            windowHeight = windowSize.height,
            margin = marginPx,
            gap = gapPx,
            bottomLimit = bottomLimitPx,
            topLimit = topLimitPx,
        )
        return IntOffset(place.x, place.y)
    }
}
