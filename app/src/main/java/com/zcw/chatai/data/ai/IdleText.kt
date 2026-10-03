package com.zcw.chatai.data.ai

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 一段流式正文，以及是不是因为太久没有新块而停下来的。 */
data class IdleText(
    val text: String,
    val stoppedForIdle: Boolean,
)

/**
 * 收集文本流。每来一块就交给 [onText]。
 * 从开始、以及从上一块起，超过 [idleMillis] 没有任何新块，就停掉收集。
 * 流自己先结束时马上返回，不必再等这段空闲。
 */
suspend fun Flow<String>.collectUntilQuiet(
    idleMillis: Long = 5_000,
    onText: (String) -> Unit,
): IdleText = coroutineScope {
    val buffer = StringBuilder()
    val chunks = Channel<Unit>(Channel.CONFLATED)
    var idleStop = false
    val collectJob = launch {
        collect { delta ->
            chunks.trySend(Unit)
            buffer.append(delta)
            onText(buffer.toString())
        }
    }
    val idleJob = launch {
        while (isActive) {
            val tick = withTimeoutOrNull(idleMillis) { chunks.receive() }
            if (tick == null) {
                idleStop = true
                collectJob.cancel()
                break
            }
        }
    }
    collectJob.join()
    idleJob.cancel()
    IdleText(buffer.toString(), idleStop)
}
