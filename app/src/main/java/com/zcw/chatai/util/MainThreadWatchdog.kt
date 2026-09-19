package com.zcw.chatai.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 仅 debug 构建启用：主线程超过 [timeoutMs] 没响应就打一份堆栈。
 *
 * 「应用卡死」类问题在真机上很难抓现场（ANR trace 在 /data/anr，非 root 读不到），
 * 这个看门狗把现场直接写进 logcat，`adb logcat -s MainWatchdog:E` 即可。
 */
object MainThreadWatchdog {

    private const val TAG = "MainWatchdog"
    private const val TIMEOUT_MS = 2_500L
    private const val POLL_MS = 500L

    @Volatile
    private var lastBeat = SystemClock.uptimeMillis()

    private var started = false

    fun startIfDebuggable(debuggable: Boolean) {
        if (!debuggable || started) return
        started = true
        val mainLooper = Looper.getMainLooper()
        val handler = Handler(mainLooper)
        val mainThread = mainLooper.thread
        Thread {
            while (true) {
                handler.post { lastBeat = SystemClock.uptimeMillis() }
                Thread.sleep(POLL_MS)
                val stuck = SystemClock.uptimeMillis() - lastBeat
                if (stuck > TIMEOUT_MS) {
                    val frames = mainThread.stackTrace
                    // 进程被系统/安装器冻结时，堆栈只剩 Looper 空转（post 的 beat 根本轮不到执行），
                    // 那不是应用代码卡住；只有堆栈里出现应用/框架代码才值得报。
                    if (frames.hasAppCode()) {
                        val trace = frames.joinToString("\n") { "    at $it" }
                        Log.e(TAG, "主线程已阻塞 ${stuck}ms，当前堆栈：\n$trace")
                    }
                    Thread.sleep(TIMEOUT_MS * 2)
                }
            }
        }.apply {
            name = "main-thread-watchdog"
            isDaemon = true
            start()
        }
    }

    /** 栈里除 `android.os.*` / `java.*` 等运行时空转帧外，是否还有真正的代码在执行。 */
    private fun Array<StackTraceElement>.hasAppCode(): Boolean = any { frame ->
        val name = frame.className
        !name.startsWith("android.os.") &&
            !name.startsWith("java.") &&
            !name.startsWith("jdk.") &&
            !name.startsWith("com.android.internal.os.") &&
            !name.startsWith("dalvik.")
    }
}
