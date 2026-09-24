package com.zcw.chatai.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.ui.theme.ChatTheme

private val ComposerCorner = 26.dp
private val ComposerFocusRing = 3.dp
private val ComposerShape = RoundedCornerShape(ComposerCorner)

@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    model: String,
    isTurnActive: Boolean,
    canSend: Boolean,
    pending: List<PendingAttachment>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (PendingAttachment) -> Unit = {},
    onModelClick: () -> Unit,
    webSearchEnabled: Boolean,
    webSearchAvailable: Boolean,
    onToggleWebSearch: () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "composerFocus",
    )
    val borderColor = lerp(colors.hairline, scheme.primary, focus)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 3px 15% 珊瑚外环：失焦时透明，避免和 clip/shadow 图层把溢出绘制裁掉。
            .border(
                width = ComposerFocusRing,
                color = scheme.primary.copy(alpha = 0.15f * focus),
                shape = RoundedCornerShape(ComposerCorner + ComposerFocusRing),
            )
            .padding(ComposerFocusRing)
            .shadow(
                elevation = (3f + 3f * focus).dp,
                shape = ComposerShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.10f),
            )
            .clip(ComposerShape)
            .background(colors.surfaceCard)
            .border(width = 1.dp, color = borderColor, shape = ComposerShape)
            .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pending.isNotEmpty()) {
            PendingAttachmentStrip(
                pending = pending,
                onRemove = onRemoveAttachment,
                onOpen = onOpenAttachment,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 26.dp, max = 150.dp)
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            maxLines = 6,
            interactionSource = interaction,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.TopStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = "回复 ChatAI…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        // 按钮行：附件 → 模型 → 联网 → 弹簧 → 发送。模型 chip 宽度上限是行宽的 25%，
        // 长模型名只省略号，不把发送键挤出去（BoxWithConstraints 读的是行可用宽）。
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val chipMaxWidth = maxWidth * 0.25f
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBareButton(
                    painter = painterResource(R.drawable.ic_attach),
                    contentDescription = "添加附件",
                    onClick = onAttachClick,
                    iconSize = 22.dp,
                    tint = scheme.onSurfaceVariant,
                )
                ModelChip(model = model, onClick = onModelClick, maxWidth = chipMaxWidth)
                WebSearchToggle(
                    enabled = webSearchEnabled,
                    available = webSearchAvailable,
                    onClick = onToggleWebSearch,
                )
                Spacer(Modifier.weight(1f))
                PrimaryActionButton(
                    isTurnActive = isTurnActive,
                    canSend = canSend,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

/** 🌐 联网开关：编辑弹层复用同一视觉与「不可用就点不动」的语义。 */
@Composable
internal fun WebSearchToggle(enabled: Boolean, available: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled && available) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = available, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🌐",
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled && available) scheme.primary else scheme.onSurfaceVariant.copy(alpha = if (available) 1f else 0.4f),
        )
    }
}

@Composable
private fun ModelChip(model: String, onClick: () -> Unit, maxWidth: Dp) {
    val colors = ChatTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.chipBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = model.ifBlank { "未选择模型" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxWidth),
        )
    }
}

@Composable
private fun PrimaryActionButton(
    isTurnActive: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val active = isTurnActive || canSend
    val container = if (active) scheme.onSurface else colors.chipBackground
    val content = if (active) scheme.surface else scheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = active) {
                if (isTurnActive) onStop() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isTurnActive) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(content),
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
