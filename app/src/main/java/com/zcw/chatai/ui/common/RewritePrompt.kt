package com.zcw.chatai.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.ai.collectUntilQuiet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 图像和视频共用的提示词优化：锁住输入，边收边写，5 秒没有新正文就停。
 * 已经在优化时返回 null，调用方不要清掉正在跑的任务。
 */
fun CoroutineScope.launchPromptRewrite(
    input: MutableStateFlow<String>,
    rewriting: MutableStateFlow<Boolean>,
    notice: MutableStateFlow<String?>,
    source: (String) -> Flow<String>,
): Job? {
    if (rewriting.value) return null
    val text = input.value.trim()
    if (text.isEmpty()) {
        notice.value = "先输入提示词再优化"
        return null
    }
    rewriting.value = true
    return launch {
        try {
            val outcome = source(text).collectUntilQuiet { input.value = it }
            if (outcome.text.isBlank()) {
                input.value = text
                notice.value = if (outcome.stoppedForIdle) {
                    "超过 5 秒没有新内容，已停止等待"
                } else {
                    "提示词优化没有返回内容"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            notice.value = t.message?.takeIf { it.isNotBlank() } ?: "提示词优化失败"
        } finally {
            rewriting.value = false
        }
    }
}

/** 优化进行中把正文限制在 [maxHeight] 里，并跟着滚到最底部。 */
@Composable
fun RewritePromptField(
    value: String,
    onValueChange: (String) -> Unit,
    rewriting: Boolean,
    placeholder: String,
    maxHeight: Dp,
    restingMaxLines: Int,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
    textStyle: TextStyle,
    cursorColor: Color,
    placeholderColor: Color,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(value, rewriting) {
        if (!rewriting) return@LaunchedEffect
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }
    BasicTextField(
        value = value,
        onValueChange = { if (!rewriting) onValueChange(it) },
        readOnly = rewriting,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 26.dp, max = maxHeight)
            .then(if (rewriting) Modifier.verticalScroll(scroll) else Modifier)
            .focusRequester(focusRequester),
        textStyle = textStyle,
        cursorBrush = SolidColor(cursorColor),
        maxLines = if (rewriting) Int.MAX_VALUE else restingMaxLines,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.TopStart) {
                if (value.isEmpty()) {
                    Text(text = placeholder, style = textStyle, color = placeholderColor)
                }
                innerTextField()
            }
        },
    )
}

/** 盖住整个输入卡片，挡住点击，直到优化结束。 */
@Composable
fun BoxScope.RewriteLock(visible: Boolean, shape: Shape, surface: Color, indicator: Color) {
    if (!visible) return
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(surface.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = indicator,
        )
    }
}
