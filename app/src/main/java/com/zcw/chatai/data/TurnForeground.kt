package com.zcw.chatai.data

/**
 * 把进程抬到 cached 以上，避免 Android 14+ Cached Apps Freezer 冻住后掐 TCP。
 * 工作仍在 [ChatRepository]，实现只负责前台服务的 acquire/release。
 */
interface TurnForeground {
    fun acquire()
    fun release()

    companion object {
        val NoOp: TurnForeground = object : TurnForeground {
            override fun acquire() = Unit
            override fun release() = Unit
        }
    }
}
