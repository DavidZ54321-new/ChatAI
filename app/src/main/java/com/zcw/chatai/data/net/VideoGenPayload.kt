package com.zcw.chatai.data.net

import com.zcw.chatai.data.video.VideoGenRequest
import com.zcw.chatai.data.video.VideoInputRef
import com.zcw.chatai.data.video.VideoInputs
import com.zcw.chatai.data.video.VideoMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 组装 DashScope `video-generation/video-synthesis` 的请求体（纯函数，与网络层解耦便于单测）。
 *
 * `wan3.0-video` 是 All-in-One，**模式由 `input.media[].type` 决定**：
 * 文生不传 media；图生 `first_frame`/`last_frame`；参考生 `reference_image`。
 * [urlFor] 把素材引用解析成入参 URL（本地图片 → base64 data URL）；返回 null 的素材直接丢弃。
 */
object VideoGenPayload {

    fun build(request: VideoGenRequest, urlFor: (VideoInputRef) -> String?): JsonObject = buildJsonObject {
        put("model", request.model)
        put(
            "input",
            buildJsonObject {
                if (request.prompt.isNotBlank()) put("prompt", request.prompt)
                val media = mediaArray(request, urlFor)
                if (media.isNotEmpty()) put("media", media)
            },
        )
        put(
            "parameters",
            buildJsonObject {
                put("resolution", request.resolution)
                // 文生视频没有输入素材可「自适应」，官方允许的 ratio 也不含 adaptive——
                // 此时不下发，交给服务端默认，避免 400。
                if (request.ratio.isNotBlank() && !(request.mode == VideoMode.T2V && request.ratio == ADAPTIVE)) {
                    put("ratio", request.ratio)
                }
                put("duration", request.duration)
                put("prompt_extend", request.promptExtend)
                put("watermark", request.watermark)
                put("audio", request.audio)
            },
        )
    }

    private const val ADAPTIVE = "adaptive"

    private fun mediaArray(
        request: VideoGenRequest,
        urlFor: (VideoInputRef) -> String?,
    ) = buildJsonArray {
        val refs = when (request.mode) {
            VideoMode.T2V -> emptyList()
            VideoMode.I2V -> listOfNotNull(request.firstFrame, request.lastFrame)
            VideoMode.R2V -> request.references
        }
        val types = VideoInputs.mediaTypes(
            mode = request.mode,
            hasFirstFrame = request.firstFrame != null,
            hasLastFrame = request.lastFrame != null,
            referenceCount = request.references.size,
        )
        types.zip(refs).forEach { (type, ref) ->
            val url = urlFor(ref)?.takeIf { it.isNotBlank() } ?: return@forEach
            add(
                buildJsonObject {
                    put("type", type)
                    put("url", url)
                },
            )
        }
    }
}
