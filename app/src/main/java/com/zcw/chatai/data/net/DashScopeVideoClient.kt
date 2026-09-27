package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.web.awaitBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DashScope 视频生成（**异步**）客户端。
 *
 * 两步协议：`submit`（带 `X-DashScope-Async: enable`）拿 `task_id`，`query` 轮询到出结果，
 * `download` 把 24 小时有效的 `video_url` 取回本地字节。鉴权与端点同「通义千问」条目。
 *
 * 只做协议与解析，不碰数据库/调度——那两个在 `VideoRepository` 与 `VideoGenWorker` 里。
 */
class DashScopeVideoClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /** 提交一次生成，返回远端任务 id（或业务失败）。 */
    suspend fun submit(config: ChatConfig, payload: JsonObject): VideoSynthesisOutcome {
        val endpoint = EndpointUrl.dashScopeVideoSynthesis(config.baseUrl)
            ?: return VideoSynthesisOutcome.Failure(null, "Base URL 无效，无法推导视频生成端点，请到设置里检查")
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .header("X-DashScope-Async", "enable")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .post(body)
            .build()
        val http = execute(httpRequest, "视频生成请求失败")
        val outcome = VideoSynthesisParser.parseSubmission(http.text)
        if (http.code !in 200..299) {
            val failure = outcome as? VideoSynthesisOutcome.Failure
            return VideoSynthesisOutcome.Failure(
                failure?.code,
                failure?.message ?: ApiErrorMapper.httpError(http.code, http.text),
            )
        }
        return outcome
    }

    /** 查询任务状态（轮询用）。 */
    suspend fun query(config: ChatConfig, taskId: String): VideoTaskOutcome {
        val endpoint = EndpointUrl.dashScopeTask(config.baseUrl, taskId)
            ?: return VideoTaskOutcome.Malformed
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .apply {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .get()
            .build()
        val http = execute(httpRequest, "视频任务查询失败")
        if (http.code !in 200..299) {
            val failure = VideoSynthesisParser.parseTask(http.text) as? VideoTaskOutcome.Failed
            return VideoTaskOutcome.Failed(
                failure?.code,
                failure?.message ?: ApiErrorMapper.httpError(http.code, http.text),
            )
        }
        return VideoSynthesisParser.parseTask(http.text)
    }

    /**
     * 下载生成结果到本地文件（24 小时失效，必须即时落盘）。返回写入字节数。
     *
     * **流式写盘**：不把整段视频读进堆（200MB 级视频会 OOM）。HTTP 非 2xx、超 [maxBytes]、
     * 内容为空、或实际字节少于声明长度（被截断）都抛可读错误，并删掉半成品文件。
     */
    suspend fun downloadToFile(url: String, target: File, maxBytes: Long = MAX_VIDEO_BYTES): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", OpenAiCompatibleChatApi.USER_AGENT)
            .get()
            .build()
        return try {
            // 传输层中断（IOException）可重试；语义错误（ChatApiException）不重试。
            TransientNetwork.retry { streamOnce(request, target, maxBytes) }
        } catch (cancelled: CancellationException) {
            target.delete()
            throw cancelled
        } catch (t: Throwable) {
            target.delete()
            if (t is ChatApiException) throw t
            throw ChatApiException("生成视频下载失败：${t.message ?: "网络异常"}", t)
        }
    }

    private suspend fun streamOnce(request: Request, target: File, maxBytes: Long): Long =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val call = client.newCall(request)
            // 协程被取消（用户停止 / Worker 被回收）时立刻掐断阻塞中的 socket 读。
            val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (response.code !in 200..299) {
                        throw ChatApiException("生成视频下载失败（HTTP ${response.code}）")
                    }
                    val body = response.body
                    val declared = body.contentLength()
                    val limitMessage = "生成视频超过 ${maxBytes / 1024 / 1024} MB 上限，请缩短时长或降低分辨率"
                    if (declared > maxBytes) throw ChatApiException(limitMessage)
                    var total = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes) throw ChatApiException(limitMessage)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (total == 0L) throw ChatApiException("生成视频下载失败：内容为空")
                    // 声明了长度却少读 → 连接被从中间掐断，落盘的是坏文件，必须报错。
                    if (declared > 0 && total < declared) {
                        throw ChatApiException("生成视频下载不完整，请重试")
                    }
                    total
                }
            } finally {
                handle?.dispose()
            }
        }

    private suspend fun execute(request: Request, fallback: String) =
        try {
            TransientNetwork.retry { client.awaitBody(request, MAX_RESPONSE_BYTES) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw ChatApiException("$fallback：${t.message ?: "网络异常"}", t)
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val MAX_RESPONSE_BYTES = 2_000_000

        /** 单段生成视频的下载上限（流式落盘，不再受堆内存约束）。 */
        private const val MAX_VIDEO_BYTES = 200L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 提交/查询都慢（生成任务本身要几十秒到几分钟），读超时给足，但不设 callTimeout
            //（由上层协程取消控制，`awaitBody` 会随之 cancel socket）。
            .readTimeout(300, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
