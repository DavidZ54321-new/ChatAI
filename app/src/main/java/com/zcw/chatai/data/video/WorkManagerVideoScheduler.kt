package com.zcw.chatai.data.video

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * 用 WorkManager 承载视频任务：按 taskId 唯一入队（[ExistingWorkPolicy.KEEP]），
 * 要求联网才能跑，失败线性退避重排。
 *
 * 入队（而不是在仓库 scope 里长跑）正是「杀后台也不被打断」的关键：
 * 进程被杀后 WorkManager 会在网络可用时重新拉起 Worker，Worker 再按落库的任务状态续跑。
 *
 * 用 [ExistingWorkPolicy.KEEP]（而非 REPLACE）：冷启动的 `resumePending` 会对同一 taskId 重复入队，
 * REPLACE 会把正在轮询/下载的 Worker 掐掉重启（还会清掉退避计数）；KEEP 在已有排队/运行的任务时
 * 是 no-op，只在任务已结束或不存在时才真正入队——语义正是「确保有 Worker 在跑，别重启在跑的那个」。
 */
class WorkManagerVideoScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : VideoTaskScheduler {

    override fun enqueue(taskId: String) {
        val request = OneTimeWorkRequestBuilder<VideoGenWorker>()
            .setInputData(workDataOf(VideoGenWorker.KEY_TASK_ID to taskId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(uniqueName(taskId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(taskId: String) {
        workManager.cancelUniqueWork(uniqueName(taskId))
    }

    companion object {
        fun uniqueName(taskId: String): String = "videogen-$taskId"
    }
}
