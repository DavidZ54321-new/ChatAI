package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.web.awaitBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/** DashScope 临时文件上传凭证（`GET /api/v1/uploads?action=getPolicy` 的 `data`）。 */
@Serializable
data class UploadPolicy(
    val policy: String = "",
    val signature: String = "",
    @SerialName("upload_dir") val uploadDir: String = "",
    @SerialName("upload_host") val uploadHost: String = "",
    @SerialName("expire_in_seconds") val expireInSeconds: Int = 300,
    @SerialName("max_file_size_mb") val maxFileSizeMb: Int = 100,
    @SerialName("oss_access_key_id") val ossAccessKeyId: String = "",
    @SerialName("x_oss_object_acl") val xOssObjectAcl: String = "private",
    @SerialName("x_oss_forbid_overwrite") val xOssForbidOverwrite: String = "true",
) {
    val isComplete: Boolean
        get() = policy.isNotBlank() && signature.isNotBlank() && uploadDir.isNotBlank() &&
            uploadHost.isNotBlank() && ossAccessKeyId.isNotBlank()
}

/** 崩溃恢复日志：凭证 + 绝对过期时间（凭证 300 秒有效）。 */
@Serializable
data class PendingUpload(
    val policy: UploadPolicy,
    val expiresAtMs: Long,
)

sealed interface UploadOutcome {
    data object Success : UploadOutcome

    /** 云端已有同名完整对象（forbid-overwrite 下的 409）：等价于上传成功。 */
    data object AlreadyExists : UploadOutcome

    data class Failure(val message: String) : UploadOutcome
}

/**
 * DashScope 免费临时存储的上传客户端（三步：取凭证 → multipart 上传 → 拼 `oss://` URL）。
 *
 * - 文件与**模型绑定**：取凭证时传的 model 必须和后续调用一致，所以 key/凭证都要按会话模型管理；
 * - `x-oss-forbid-overwrite=true` 让重传同 key 返回 409 `FileAlreadyExists`——
 *   这是「云端已有完整对象」的可靠信号，用于崩溃后的免二次上传；
 * - POST 走 [TransientNetwork.retry]：中断后重试时，已完成的第一次会上报 409，不会被当成失败。
 */
class DashScopeUpload(
    private val client: OkHttpClient = defaultClient(),
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun fetchPolicy(config: ChatConfig, model: String): UploadPolicy {
        val base = EndpointUrl.dashScopeUploads(config.baseUrl)
            ?: throw ChatApiException("请先在设置中填写 Base URL")
        val url = base.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("action", "getPolicy")
            ?.addQueryParameter("model", model)
            ?.build()
            ?: throw ChatApiException("Base URL 无效：$base")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (config.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .get()
            .build()
        val http = try {
            TransientNetwork.retry { client.awaitBody(request, MAX_POLICY_BYTES) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            throw ChatApiException("获取上传凭证失败：${t.message ?: "未知错误"}", t)
        }
        if (http.code !in 200..299) {
            throw ChatApiException(ApiErrorMapper.httpError(http.code, http.text.take(MAX_ERROR_CHARS)))
        }
        return parsePolicy(http.text) ?: throw ChatApiException("上传凭证格式异常，请稍后重试")
    }

    suspend fun upload(file: File, key: String, policy: UploadPolicy): UploadOutcome {
        val host = policy.uploadHost.toHttpUrlOrNull()
            ?: return UploadOutcome.Failure("上传地址无效")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("OSSAccessKeyId", policy.ossAccessKeyId)
            .addFormDataPart("Signature", policy.signature)
            .addFormDataPart("policy", policy.policy)
            .addFormDataPart("x-oss-object-acl", policy.xOssObjectAcl)
            .addFormDataPart("x-oss-forbid-overwrite", policy.xOssForbidOverwrite)
            .addFormDataPart("key", key)
            .addFormDataPart("success_action_status", "200")
            // file 必须是最后一个表单字段（官方要求）。
            .addFormDataPart("file", file.name, file.asRequestBody(FILE_MEDIA_TYPE))
            .build()
        val request = Request.Builder().url(host).post(body).build()
        return try {
            val http = TransientNetwork.retry { client.awaitBody(request, MAX_UPLOAD_RESPONSE_BYTES) }
            when {
                http.code in 200..299 -> UploadOutcome.Success
                http.code == 409 || "FileAlreadyExists" in http.text -> UploadOutcome.AlreadyExists
                else -> UploadOutcome.Failure("HTTP ${http.code}：${http.text.take(MAX_ERROR_CHARS)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            throw ChatApiException("视频上传失败：${t.message ?: "未知错误"}", t)
        }
    }

    fun urlFor(key: String): String = "oss://$key"

    companion object {
        private val FILE_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private const val MAX_POLICY_BYTES = 512 * 1024
        private const val MAX_UPLOAD_RESPONSE_BYTES = 256 * 1024
        private const val MAX_ERROR_CHARS = 300

        /** 解析凭证响应（`{"data":{…}}` 或直接 `{…}`）；纯函数，JVM 可测。 */
        fun parsePolicy(raw: String): UploadPolicy? = try {
            val root = Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(raw)
                .jsonObject
            val data = root["data"] ?: root
            Json { ignoreUnknownKeys = true }
                .decodeFromJsonElement(UploadPolicy.serializer(), data)
                .takeIf { it.isComplete }
        } catch (t: Exception) {
            null
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
