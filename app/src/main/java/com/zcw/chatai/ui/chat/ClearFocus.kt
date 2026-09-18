package com.zcw.chatai.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

/**
 * 点 [outside] 矩形以外的区域时清掉焦点。
 * Initial 通道只观察、不消费：列表滚动和气泡点击照常。
 */
fun Modifier.clearFocusOnTapOutside(outside: () -> Rect): Modifier = composed {
    val focusManager = LocalFocusManager.current
    pointerInput(focusManager) {
        awaitPointerEventScope {
            var down: Offset? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { change ->
                    when {
                        change.changedToDownIgnoreConsumed() -> down = change.position
                        change.changedToUpIgnoreConsumed() -> {
                            val start = down
                            down = null
                            if (start != null &&
                                (change.position - start).getDistance() < viewConfiguration.touchSlop &&
                                !outside().contains(change.position)
                            ) {
                                focusManager.clearFocus()
                            }
                        }
                    }
                }
            }
        }
    }
}
