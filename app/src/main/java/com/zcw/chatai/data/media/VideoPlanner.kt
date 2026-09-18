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
}

object VideoPlanner {

    /**
     * - `remoteUrl` 有效 = 未过期 **且** 上传时的模型与当前模型一致（官方要求文件与模型绑定）；
     * - 小文件优先内联，不做任何上传/缓存判断；
     * - 其余情况才考虑崩溃日志 → 上传。
     */
    fun plan(
        attachment: Attachment,
        model: String,
        nowMs: Long,
        inlineMaxBytes: Long = AttachmentLimits.VIDEO_INLINE_MAX_BYTES,
    ): VideoPlan {
        val remoteUrl = attachment.remoteUrl
        if (!remoteUrl.isNullOrBlank() &&
            attachment.remoteModel == model &&
            (attachment.remoteExpiresAt ?: 0L) > nowMs
        ) {
            return VideoPlan.Reuse(remoteUrl)
        }
        if (attachment.sizeBytes in 1..inlineMaxBytes) return VideoPlan.Inline
        val key = attachment.pendingKey?.takeIf { it.isNotBlank() }
        val policy = attachment.pendingPolicy?.takeIf { it.isNotBlank() }
        return if (key != null && policy != null) VideoPlan.Upload(key, policy) else VideoPlan.Upload(null, null)
    }
}
