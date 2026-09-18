package com.zcw.chatai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.R
import com.zcw.chatai.ui.theme.ChatTheme

private val ComposerShape = RoundedCornerShape(26.dp)

@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    model: String,
    isStreaming: Boolean,
    canSend: Boolean,
    pending: List<PendingAttachment>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAddImage: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onModelClick: () -> Unit,
    webSearchEnabled: Boolean,
    webSearchAvailable: Boolean,
    onToggleWebSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (focused) 6.dp else 3.dp,
                shape = ComposerShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.10f),
            )
            .clip(ComposerShape)
            .background(colors.surfaceCard)
            .border(
                width = 1.dp,
                color = if (focused) scheme.primary else colors.hairline,
                shape = ComposerShape,
            )
            .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pending.isNotEmpty()) {
            PendingAttachmentStrip(pending = pending, onRemove = onRemoveAttachment)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 26.dp, max = 150.dp),
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBareButton(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = "添加图片",
                onClick = onAddImage,
                iconSize = 22.dp,
                tint = scheme.onSurfaceVariant,
            )
            WebSearchToggle(
                enabled = webSearchEnabled,
                available = webSearchAvailable,
                onClick = onToggleWebSearch,
            )
            ModelChip(model = model, onClick = onModelClick)
            Spacer(Modifier.weight(1f))
            PrimaryActionButton(
                isStreaming = isStreaming,
                canSend = canSend,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun WebSearchToggle(enabled: Boolean, available: Boolean, onClick: () -> Unit) {
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
private fun ModelChip(model: String, onClick: () -> Unit) {
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
            modifier = Modifier.widthIn(max = 170.dp),
        )
    }
}

@Composable
private fun PrimaryActionButton(
    isStreaming: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val active = isStreaming || canSend
    val container = if (active) scheme.onSurface else colors.chipBackground
    val content = if (active) scheme.surface else scheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = active) {
                if (isStreaming) onStop() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isStreaming) {
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
