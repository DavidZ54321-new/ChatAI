package com.zcw.chatai.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话级回合注册表：同一会话至多一个进行中的回合，跨会话不限并发。
 * 纯逻辑（无 Android 依赖），JVM 可测。
 *
 * [Job] 的完成回调带身份校验：被 [restart] 替换掉的旧 Job 完成时，
 * 不会把新 Job 的登记记录误删。
 */
class TurnRegistry {

    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** 正在跑回合的会话集合（含流式与工具执行全程），供 UI 决定发送/停止键。 */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** 空闲则登记并返回 Job；该会话已有回合在跑则返回 null。 */
    fun startIfIdle(conversationId: String, launch: () -> Job): Job? = synchronized(lock) {
        if (jobs[conversationId]?.isActive == true) return null
        register(conversationId, launch())
    }

    /** 取消该会话旧回合（若有）并登记新回合，供「重新生成」使用。 */
    fun restart(conversationId: String, launch: () -> Job): Job = synchronized(lock) {
        jobs.remove(conversationId)?.cancel()
        register(conversationId, launch())
    }

    fun cancel(conversationId: String): Unit = synchronized(lock) {
        jobs.remove(conversationId)?.cancel()
        publishBusy()
    }

    fun cancelAll(): Unit = synchronized(lock) {
        val running = jobs.values.toList()
        jobs.clear()
        publishBusy()
        running.forEach { it.cancel() }
    }

    /** 调用方必须持有 [lock]：登记后注册完成回调，回调里做身份校验清理。 */
    private fun register(conversationId: String, job: Job): Job {
        jobs[conversationId] = job
        publishBusy()
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[conversationId] === job) {
                    jobs.remove(conversationId)
                    publishBusy()
                }
            }
        }
        return job
    }

    private fun publishBusy() {
        _busy.value = jobs.keys.toSet()
    }
}
