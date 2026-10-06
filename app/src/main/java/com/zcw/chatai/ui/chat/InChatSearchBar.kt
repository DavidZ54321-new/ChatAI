package com.zcw.chatai.ui.chat

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ui.theme.ChatTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 按住上一处 / 下一处这么久，直接跳到第一处或最后一处。 */
private const val SearchJumpHoldMs = 3_000L

private val BarCorner = 26.dp
private val BarShape = RoundedCornerShape(BarCorner)

/**
 * 贴在输入框正上方的会话内搜索条。
 * 右边两个按钮：左是上一处，右是下一处。按住 3 秒分别跳到第一处和最后一处。
 * 清除只清字，不关条。
 */
@Composable
fun InChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    countLabel: String?,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpToFirst: () -> Unit,
    onJumpToLast: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 3.dp, shape = BarShape, clip = false)
            .clip(BarShape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, BarShape)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            StepButton(
                icon = Icons.Filled.Close,
                contentDescription = "清除",
                enabled = true,
                onClick = { onQueryChange("") },
            )
        }
        if (countLabel != null) {
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        StepButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = "上一处",
            enabled = canPrevious,
            onClick = onPrevious,
            onLongHold = onJumpToFirst,
            longHoldLabel = "跳到第一处",
        )
        StepButton(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = "下一处",
            enabled = canNext,
            onClick = onNext,
            onLongHold = onJumpToLast,
            longHoldLabel = "跳到最后一处",
        )
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongHold: (() -> Unit)? = null,
    longHoldLabel: String? = null,
) {
    val click = rememberUpdatedState(onClick)
    val hold = rememberUpdatedState(onLongHold)
    val enabledNow = rememberUpdatedState(enabled)
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val gesture = if (onLongHold == null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
    } else {
        // 手指按住不动时不会再来指针事件。计时必须自己走，不能等下一次触摸。
        Modifier
            .indication(interaction, LocalIndication.current)
            .pointerInput(interaction) {
                coroutineScope {
                    val scope = this
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val canAct = enabledNow.value
                        val press = PressInteraction.Press(down.position)
                        val pressJob = if (canAct) scope.launch { interaction.emit(press) } else null
                        var jumped = false
                        val holdJob = scope.launch {
                            delay(SearchJumpHoldMs)
                            val action = hold.value
                            if (!canAct || action == null) return@launch
                            jumped = true
                            action()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        val up = waitForUpOrCancellation()
                        holdJob.cancel()
                        up?.consume()
                        if (pressJob != null) {
                            scope.launch {
                                pressJob.join()
                                interaction.emit(
                                    if (up == null) PressInteraction.Cancel(press)
                                    else PressInteraction.Release(press),
                                )
                            }
                        }
                        if (canAct && !jumped && up != null) click.value()
                    }
                }
            }
            .semantics {
                if (enabled && longHoldLabel != null) {
                    onLongClick(label = longHoldLabel) {
                        onLongHold()
                        true
                    }
                }
            }
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.35f)
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}
