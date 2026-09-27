package com.zcw.chatai.data.video

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zcw.chatai.ChatAiApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 视频任务 Worker：每次唤醒推进一条任务（提交→轮询→下载），直到出终态。
 *
 * 单次运行有 [MAX_RUNTIME_MS] 上限（WorkManager 也会在约 10 分钟后回收 Worker），
 * 未到终态就 `Result.retry()` 退避重排，链式跑完；重排次数超过 [MAX_ATTEMPTS] 才判超时失败。
 * `advance` 命中业务失败会自己落库为 ERROR 并返回 FAILED，Worker 直接收工（不再重排）。
 */
class VideoGenWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val app = applicationContext as ChatAiApp
        val repository = app.videoRepository
        val startedAt = SystemClock.elapsedRealtime()

        while (!isStopped) {
            val outcome = try {
                repository.advance(taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // 网络抖动：不落错误，留在本轮里继续等（超过预算再退避重排）。
                Log.w(TAG, "视频任务推进失败，稍后继续", t)
                null
            }
            when (outcome) {
                AdvanceOutcome.DONE -> return Result.success()
                // 业务失败已在 advance 里落库为 ERROR，Worker 收工即可。
                AdvanceOutcome.FAILED -> return Result.success()
                AdvanceOutcome.CONTINUE, null -> Unit
            }
            if (SystemClock.elapsedRealtime() - startedAt >= MAX_RUNTIME_MS) {
                return retryOrGiveUp(repository, taskId)
            }
            delay(POLL_INTERVAL_MS)
        }
        // Worker 被系统叫停（isStopped）：同样受重排次数约束，不无限续。
        return retryOrGiveUp(repository, taskId)
    }

    /** 未到终态的退避重排；重排次数用尽则判超时失败（不再无限续）。 */
    private suspend fun retryOrGiveUp(repository: VideoRepository, taskId: String): Result {
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            // 尽力标失败；协程已被取消时这步可能跑不到，再由下次冷启动的 resumePending 兜底。
            runCatching { repository.giveUp(taskId, "生成超时，请重试") }
            return Result.failure()
        }
        return Result.retry()
    }

    companion object {
        const val KEY_TASK_ID = "taskId"

        /** 轮询间隔。 */
        private const val POLL_INTERVAL_MS = 10_000L

        /** 单次运行预算：留足余量给 WorkManager 的 ~10 分钟上限。 */
        private const val MAX_RUNTIME_MS = 8 * 60 * 1000L

        /** 退避重排上限（≈ 8 分钟 × N，够覆盖很长的生成）。 */
        private const val MAX_ATTEMPTS = 30

        private const val TAG = "VideoGenWorker"
    }
}
