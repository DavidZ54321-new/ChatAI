package com.zcw.chatai.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 提交（创建任务）的解析结果（纯数据）。 */
sealed interface VideoSynthesisOutcome {
    /** 已受理：远端任务 id + 初始状态。 */
    data class Submitted(val taskId: String, val status: String?) : VideoSynthesisOutcome

    /** 业务失败：DashScope 顶层 `code` / `message`。 */
    data class Failure(val code: String?, val message: String?) : VideoSynthesisOutcome

    /** 响应不是可识别的 JSON 结构（网关错误页等）。 */
    data object Malformed : VideoSynthesisOutcome
}

/** 轮询任务状态的解析结果（纯数据）。 */
sealed interface VideoTaskOutcome {
    /** 排队中。 */
    data object Pending : VideoTaskOutcome

    /** 生成中。 */
    data object Running : VideoTaskOutcome

    /** 成功：视频 URL（24 小时有效，须立刻下载）。 */
    data class Succeeded(val url: String) : VideoTaskOutcome

    /** 失败：`output.code` / `output.message`（或顶层）。 */
    data class Failed(val code: String?, val message: String?) : VideoTaskOutcome

    /** 已取消。 */
    data object Canceled : VideoTaskOutcome

    /**
     * 任务不存在或已过期：官方 `task_status = "UNKNOWN"`，
     * 文档明确这是「task_id 超过 24 小时有效期」的表现——重试没有意义。
     */
    data object Expired : VideoTaskOutcome

    /** 非可识别结构或未知状态。 */
    data object Malformed : VideoTaskOutcome
}

/**
 * 解析 DashScope 视频生成的提交/查询响应（纯函数，JVM 单测覆盖）。
 *
 * 两段响应同一形状：`output.task_id` / `output.task_status`，成功另带 `output.video_url`。
 * 失败时错误码可能在顶层（提交阶段）也可能在 `output` 内（查询阶段），两处都认。
 */
object VideoSynthesisParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseSubmission(raw: String): VideoSynthesisOutcome {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return VideoSynthesisOutcome.Malformed
        val output = runCatching { root["output"]?.jsonObject }.getOrNull()
        val code = root.stringOf("code") ?: output.stringOf("code")
        val message = root.stringOf("message") ?: output.stringOf("message")
        val taskId = output.stringOf("task_id")
        if (taskId.isNullOrBlank()) {
            return if (code != null || message != null) {
                VideoSynthesisOutcome.Failure(code, message)
            } else {
                VideoSynthesisOutcome.Malformed
            }
        }
        return VideoSynthesisOutcome.Submitted(taskId, output.stringOf("task_status"))
    }

    fun parseTask(raw: String): VideoTaskOutcome {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return VideoTaskOutcome.Malformed
        val output = runCatching { root["output"]?.jsonObject }.getOrNull()
        val code = root.stringOf("code") ?: output.stringOf("code")
        val message = root.stringOf("message") ?: output.stringOf("message")
        return when (output.stringOf("task_status")?.uppercase()) {
            "PENDING" -> VideoTaskOutcome.Pending
            "RUNNING" -> VideoTaskOutcome.Running
            "SUCCEEDED" -> {
                val url = output.stringOf("video_url")
                if (url.isNullOrBlank()) {
                    VideoTaskOutcome.Failed(code, message ?: "任务成功但未返回视频地址")
                } else {
                    VideoTaskOutcome.Succeeded(url)
                }
            }
            "FAILED" -> VideoTaskOutcome.Failed(code, message)
            "CANCELED", "CANCELLED" -> VideoTaskOutcome.Canceled
            // task_id 过期（>24h）或不存在；重试无用，直接判失败。
            "UNKNOWN" -> VideoTaskOutcome.Expired
            else -> if (code != null) VideoTaskOutcome.Failed(code, message) else VideoTaskOutcome.Malformed
        }
    }

    private fun JsonObject?.stringOf(key: String): String? =
        runCatching { this?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.takeIf { it.isNotBlank() }
}
