package com.zcw.chatai.ui.chat

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 聊天页上的流式轻震。时钟、马达、前后台都在这里。
 *
 * [armed] 由调用方合成「设置开着且聊天主界面露出来」。退到后台时这里再解除武装，
 * 回来的第一帧只记基线。
 */
@Composable
fun StreamHaptics(
    armed: Boolean,
    messageId: String?,
    content: String,
) {
    val context = LocalContext.current
    val clock = remember { StreamHapticClock() }
    val player = remember(context) { StreamHapticPlayer(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val effective = armed && foreground
    LaunchedEffect(messageId, content, effective) {
        val due = clock.onFrame(
            messageId = messageId,
            content = content,
            nowMs = SystemClock.elapsedRealtime(),
            armed = effective,
        )
        if (due) player.tick()
    }
}
