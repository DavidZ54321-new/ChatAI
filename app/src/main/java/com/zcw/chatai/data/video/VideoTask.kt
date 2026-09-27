package com.zcw.chatai.data.video

import com.zcw.chatai.data.db.VideoTaskEntity

/** 一条视频任务的领域模型（UI 用来区分「等待生成」/「生成中」/「失败」）。 */
data class VideoTask(
    val id: String,
    val conversationId: String,
    val messageId: String,
    val remoteTaskId: String?,
    val model: String,
    val mode: VideoMode,
    val status: VideoTaskStatus,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

fun VideoTaskEntity.toModel(): VideoTask = VideoTask(
    id = id,
    conversationId = conversationId,
    messageId = messageId,
    remoteTaskId = remoteTaskId,
    model = model,
    mode = VideoMode.entries.firstOrNull { it.name == mode } ?: VideoMode.T2V,
    status = VideoTaskStatus.fromString(status),
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** Worker 每次推进的结论：[CONTINUE] 继续轮询，[DONE] 终态收工，[FAILED] 终态报错。 */
enum class AdvanceOutcome { CONTINUE, DONE, FAILED }

/**
 * 视频任务的调度缝：把「入队/取消 WorkManager」抽象出来，仓库层不直接依赖 WorkManager。
 * 测试与无 WorkManager 的环境用 [NoOp]。
 */
interface VideoTaskScheduler {

    fun enqueue(taskId: String)

    fun cancel(taskId: String)

    companion object {
        val NoOp = object : VideoTaskScheduler {
            override fun enqueue(taskId: String) = Unit
            override fun cancel(taskId: String) = Unit
        }
    }
}
