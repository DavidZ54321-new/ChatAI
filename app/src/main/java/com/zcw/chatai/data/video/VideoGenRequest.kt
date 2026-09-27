package com.zcw.chatai.data.video

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个视频输入素材的引用（落库用）。
 *
 * 只存**相对路径**（相对 `filesDir`）与 MIME，不存 base64——几 MB 的字符串不能进数据库，
 * 提交时按路径现读现组。
 */
@Serializable
data class VideoInputRef(
    val id: String,
    val relativePath: String,
    val mimeType: String,
)

/**
 * 一次视频生成的完整请求（序列化进 `video_tasks.request_json`）。
 * 进程被杀后 Worker 靠它重建请求体，不需要再读助手消息。
 */
@Serializable
data class VideoGenRequest(
    val mode: VideoMode,
    val prompt: String,
    val model: String,
    val resolution: String,
    val ratio: String,
    /** 2~30 秒，或 -1（智能时长）。 */
    val duration: Int,
    val audio: Boolean,
    val watermark: Boolean,
    val promptExtend: Boolean,
    val firstFrame: VideoInputRef? = null,
    val lastFrame: VideoInputRef? = null,
    val references: List<VideoInputRef> = emptyList(),
)

/** `request_json` 列的 JSON 编解码（纯函数，JVM 可测）。非法/缺字段一律返回 null。 */
object VideoGenRequestCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(request: VideoGenRequest): String = json.encodeToString(request)

    fun decode(raw: String?): VideoGenRequest? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<VideoGenRequest>(raw) }.getOrNull()
    }
}
