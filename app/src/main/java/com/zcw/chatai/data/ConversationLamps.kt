package com.zcw.chatai.data

import com.zcw.chatai.data.model.MessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一轮结束时用户没在看这条会话：完成或失败。取消不记。 */
enum class UnseenOutcome { DONE, FAILED }

/** 列表圆点。处理中优先于未见结果。 */
enum class ConversationLamp { RUNNING, UNSEEN_DONE, UNSEEN_FAILED }

/**
 * 进程内的会话状态灯。对话和生图共用一份：会话 id 全局唯一，屏幕上同时只看着一条。
 *
 * 黄灯跟着回合走（[begin] / [finish]），不是跟着某一条助手消息。
 * 被新回合替换掉的旧 token 在 [finish] 时直接忽略，避免旧回合的收尾把新灯清掉。
 * 进程被杀掉后清空，不落库。
 */
class ConversationLamps {

    private val lock = Any()
    private var nextToken = 0L
    private var watchingId: String? = null
    private val running = HashMap<String, Long>()
    private val unseen = HashMap<String, UnseenOutcome>()
    private val _current = MutableStateFlow<Map<String, ConversationLamp>>(emptyMap())

    val current: StateFlow<Map<String, ConversationLamp>> = _current.asStateFlow()

    /** 屏幕上正在显示的会话。传入即清掉它的未见结果；正在跑的黄灯保留。 */
    fun setWatching(id: String?) = synchronized(lock) {
        watchingId = id
        if (id != null && id !in running) unseen.remove(id)
        publish()
    }

    /** 一轮开始。返回的 token 要原样交给同一次 [finish]。 */
    fun begin(conversationId: String): Long = synchronized(lock) {
        val token = ++nextToken
        running[conversationId] = token
        unseen.remove(conversationId)
        publish()
        token
    }

    /**
     * 一轮结束。[status] 为 null、取消或仍在流式时不留灯。
     * token 与当前回合不一致（已被新回合替换）时什么都不做。
     */
    fun finish(conversationId: String, token: Long, status: MessageStatus?) {
        synchronized(lock) {
            if (running[conversationId] != token) return
            running.remove(conversationId)
            when (val outcome = outcome(watchingId == conversationId, status)) {
                null -> unseen.remove(conversationId)
                else -> unseen[conversationId] = outcome
            }
            publish()
        }
    }

    private fun publish() {
        val shown = HashMap<String, ConversationLamp>(running.size + unseen.size)
        for (id in running.keys) shown[id] = ConversationLamp.RUNNING
        for ((id, outcome) in unseen) {
            if (id in running) continue
            shown[id] = when (outcome) {
                UnseenOutcome.FAILED -> ConversationLamp.UNSEEN_FAILED
                UnseenOutcome.DONE -> ConversationLamp.UNSEEN_DONE
            }
        }
        if (_current.value != shown) _current.value = shown
    }

    companion object {

        fun outcome(watching: Boolean, status: MessageStatus?): UnseenOutcome? = when {
            watching || status == null -> null
            status == MessageStatus.ERROR -> UnseenOutcome.FAILED
            status == MessageStatus.COMPLETE -> UnseenOutcome.DONE
            else -> null
        }
    }
}
