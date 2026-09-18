package com.zcw.chatai

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zcw.chatai.data.TurnForeground

/**
 * 用代数计数 + 延迟拆服务，避免「停止立刻重生成」时旧回合的 finally
 * 把新回合刚拉起的前台服务拆掉。
 */
class ChatTurnForeground(
    private val app: Application,
) : TurnForeground {

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    private val stopRunnable = Runnable {
        if (generation == 0) {
            runCatching { app.stopService(Intent(app, ChatTurnService::class.java)) }
        }
    }

    override fun acquire() = onMain { acquireNow() }

    override fun release() = onMain { releaseNow() }

    private fun acquireNow() {
        handler.removeCallbacks(stopRunnable)
        generation++
        try {
            // 每次都 start：超时路径会在计数清零前 stopSelf，只在 generation==1 时启动会把下一回合饿死。
            app.startForegroundService(Intent(app, ChatTurnService::class.java))
        } catch (t: Throwable) {
            generation = (generation - 1).coerceAtLeast(0)
            Log.e(TAG, "无法启动前台服务", t)
        }
    }

    private fun releaseNow() {
        generation = (generation - 1).coerceAtLeast(0)
        if (generation == 0) {
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, RELEASE_DELAY_MS)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            handler.post(block)
        }
    }

    companion object {
        private const val TAG = "ChatTurnForeground"
        private const val RELEASE_DELAY_MS = 400L
    }
}
