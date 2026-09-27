package com.zcw.chatai.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.zcw.chatai.data.video.VideoMode
import com.zcw.chatai.ui.chat.IconBareButton
import com.zcw.chatai.ui.chat.PendingAttachment
import com.zcw.chatai.ui.chat.PendingAttachmentStrip
import com.zcw.chatai.ui.chat.PrimaryActionButton
import com.zcw.chatai.ui.theme.ChatTheme

private val ComposerCorner = 26.dp
private val ComposerFocusRing = 3.dp
private val ComposerShape = RoundedCornerShape(ComposerCorner)

/**
 * 视频页的提示词输入区（放在**页面顶部**）：多行提示词 + 参考图 + 模式/参数 + 「优化/分镜」+ 模型 + 发送。
 * 「优化」把提示词改写成按秒分镜；「分镜」插入一个镜头骨架。
 */
@Composable
fun VideoComposer(
    value: String,
    onValueChange: (String) -> Unit,
    model: String,
    params: VideoParams,
    isBusy: Boolean,
    rewriting: Boolean,
    canSend: Boolean,
    pending: List<PendingAttachment>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOptimize: () -> Unit,
    onInsertShot: () -> Unit,
    onModeClick: () -> Unit,
    onParamsClick: () -> Unit,
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
    Column(
        modifier = modifier
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
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
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
                .heightIn(min = 26.dp, max = 180.dp)
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            maxLines = 8,
            interactionSource = interaction,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.TopStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = "描述要生成的视频，或按「第1个镜头[0-3秒] …」写分镜；点「优化」自动改写",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        // 第一行：生成设置（模式 / 参数 / 模型），窄屏可横向滚动。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MiniPill(text = params.mode.label(), onClick = onModeClick)
            MiniPill(text = params.summary(), onClick = onParamsClick)
            ModelChip(model = model, onClick = onModelClick, maxWidth = 220.dp)
        }
        // 第二行：动作（参考图 / 优化提示词 / 分镜骨架）+ 发送，始终可见不被滚动藏起来。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconBareButton(
                painter = painterResource(R.drawable.ic_attach),
                contentDescription = "添加参考图",
                onClick = onAttachClick,
                iconSize = 22.dp,
                tint = scheme.onSurfaceVariant,
            )
            MiniPill(
                text = if (rewriting) "取消优化" else "优化",
                onClick = onOptimize,
                enabled = value.isNotBlank() || rewriting,
            )
            MiniPill(text = "分镜", onClick = onInsertShot)
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

@Composable
private fun MiniPill(text: String, onClick: () -> Unit, enabled: Boolean = true) {
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
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
            maxLines = 1,
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

internal fun VideoMode.label(): String = when (this) {
    VideoMode.T2V -> "文生"
    VideoMode.I2V -> "图生"
    VideoMode.R2V -> "参考"
}

/** 参数摘要：「720P · 16:9 · 5s · 有声」；图生模式不显示比例（由首帧决定）。 */
internal fun VideoParams.summary(): String {
    val durationText = if (duration < 0) "智能" else "${duration}s"
    return buildList {
        add(resolution)
        if (supportsRatio) add(ratio)
        add(durationText)
        add(if (audio) "有声" else "无声")
    }.joinToString(" · ")
}
