package com.zcw.chatai.data.media

import com.zcw.chatai.data.model.Attachment

/** 出站附件的硬性上限（误发超限请求会被服务端 400/413，不如本地先拦住）。 */
object AttachmentLimits {

    const val MAX_IMAGES = 8

    /** 压缩后总字节上限；base64 后约 ×1.34，仍远低于端点的 48 MiB 请求体限制。 */
    const val MAX_TOTAL_BYTES = 6L * 1024 * 1024

    /** base64 之后的估算请求体大小。 */
    fun estimatedRequestBytes(attachments: List<Attachment>): Long =
        ImageCodec.base64Length(attachments.sumOf { it.sizeBytes })

    /** 返回 null 表示可以发送，否则是给用户看的原因。 */
    fun validate(attachments: List<Attachment>): String? = when {
        attachments.size > MAX_IMAGES -> "最多只能发送 $MAX_IMAGES 张图片"
        attachments.sumOf { it.sizeBytes } > MAX_TOTAL_BYTES ->
            "图片总大小超过上限（压缩后约 ${MAX_TOTAL_BYTES / 1024 / 1024} MiB），请减少图片数量"
        else -> null
    }
}
