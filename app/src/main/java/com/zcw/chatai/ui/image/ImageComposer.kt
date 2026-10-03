package com.zcw.chatai.ui.image

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.ui.common.RewriteLock
import com.zcw.chatai.ui.common.RewritePromptField
import com.zcw.chatai.ui.chat.IconBareButton
import com.zcw.chatai.ui.chat.PendingAttachment
import com.zcw.chatai.ui.chat.PendingAttachmentStrip
import com.zcw.chatai.ui.chat.PrimaryActionButton
import com.zcw.chatai.ui.theme.ChatTheme

private val ComposerCorner = 26.dp
private val ComposerFocusRing = 3.dp
private val ComposerShape = RoundedCornerShape(ComposerCorner)

/**
 * 生图输入栏：文本提示词 + 用户自己传的图 +「优化」+ 图像模型 + 发送/停止。
 * 上一轮生成结果是**静默带入**的（不在这里显示），所以这里没有「上一张」chip。
 */
@Composable
fun ImageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    model: String,
    isBusy: Boolean,
    rewriting: Boolean,
    canSend: Boolean,
    pending: List<PendingAttachment>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOptimize: () -> Unit,
    onModelClick: () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = if (focused) 1f else 0f
    val borderColor = lerp(colors.hairline, scheme.primary, focus)
    Box(modifier = modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            PendingAttachmentStrip(pending = pending, onRemove = onRemoveAttachment)
        }
        RewritePromptField(
            value = value,
            onValueChange = onValueChange,
            rewriting = rewriting,
            placeholder = "描述你想生成或修改的画面…",
            maxHeight = 150.dp,
            restingMaxLines = 6,
            focusRequester = focusRequester,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorColor = scheme.primary,
            placeholderColor = scheme.onSurfaceVariant,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val chipMaxWidth = maxWidth * 0.28f
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBareButton(
                    painter = painterResource(R.drawable.ic_attach),
                    contentDescription = "添加图片",
                    onClick = onAttachClick,
                    iconSize = 22.dp,
                    tint = scheme.onSurfaceVariant,
                )
                OptimizePill(enabled = value.isNotBlank() && !rewriting, onClick = onOptimize)
                ModelChip(model = model, onClick = onModelClick, maxWidth = chipMaxWidth)
                Spacer(Modifier.weight(1f))
                PrimaryActionButton(
                    isTurnActive = isBusy,
                    canSend = canSend,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
        RewriteLock(
            visible = rewriting,
            shape = RoundedCornerShape(ComposerCorner + ComposerFocusRing),
            surface = colors.surfaceCard,
            indicator = scheme.primary,
        )
    }
}

@Composable
private fun OptimizePill(enabled: Boolean, onClick: () -> Unit) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) colors.chipBackground else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "优化",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
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
