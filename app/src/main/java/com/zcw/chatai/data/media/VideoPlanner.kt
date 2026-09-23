package com.zcw.chatai.data.media

import com.zcw.chatai.data.model.Attachment

/** 一个视频附件的出站计划（纯决策，JVM 可测）。 */
sealed interface VideoPlan {

    /** 小文件：内联 base64，无需上传。 */
    data object Inline : VideoPlan

    /** 已上传且未过期、模型一致：直接复用云端 URL。 */
    data class Reuse(val url: String) : VideoPlan

    /**
     * 需要上传。[resumeKey]/[resumePolicy] 非空表示存在崩溃日志：
     * 凭证未过期时用同一 key 条件重传（409 = 云端已完整），否则换新凭证重传。
     */
    data class Upload(val resumeKey: String?, val resumePolicy: String?) : VideoPlan

    /**
     * 超过内联上限、且该供应商没有上传路由（如 MiMo 无 DashScope 服务）。
     * 由协调器转成可读错误，不发注定失败的请求。
     */
    data class TooLargeForInline(val limitBytes: Long) : VideoPlan
}

object VideoPlanner {

    /**
     * - `remoteUrl` 有效 = 未过期 **且** 上传时的模型与当前模型一致（官方要求文件与模型绑定）；
     * - 小文件优先内联，不做任何上传/缓存判断；
     * - 超限且允许上传 → 崩溃日志 → 上传；超限但不允许上传 → [VideoPlan.TooLargeForInline]。
     */
    fun plan(
        attachment: Attachment,
        model: String,
        nowMs: Long,
        inlineMaxBytes: Long = AttachmentLimits.VIDEO_INLINE_MAX_BYTES,
        allowUpload: Boolean = true,
    ): VideoPlan {
        val remoteUrl = attachment.remoteUrl
        if (!remoteUrl.isNullOrBlank() &&
            attachment.remoteModel == model &&
            (attachment.remoteExpiresAt ?: 0L) > nowMs
        ) {
            return VideoPlan.Reuse(remoteUrl)
        }
        if (attachment.sizeBytes in 1..inlineMaxBytes) return VideoPlan.Inline
        if (!allowUpload) return VideoPlan.TooLargeForInline(inlineMaxBytes)
        val key = attachment.pendingKey?.takeIf { it.isNotBlank() }
        val policy = attachment.pendingPolicy?.takeIf { it.isNotBlank() }
        return if (key != null && policy != null) VideoPlan.Upload(key, policy) else VideoPlan.Upload(null, null)
    }
}
