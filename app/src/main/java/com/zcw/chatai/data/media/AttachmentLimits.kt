package com.zcw.chatai.data.media

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.model.SearchedImage

/** 出站附件的硬性上限（误发超限请求会被服务端 400/413，不如本地先拦住）。 */
object AttachmentLimits {

    const val MAX_IMAGES = 8

    /** 压缩后总字节上限；base64 后约 ×1.34，仍远低于端点的 48 MiB 请求体限制。 */
    const val MAX_TOTAL_BYTES = 6L * 1024 * 1024

    const val MAX_VIDEOS = 2

    /**
     * 视频走内联 base64 的上限：编码后约 6.7MB，官方硬线是「编码后 ≤10MB」，留足余量。
     * 超过这条线走临时上传路由。
     */
    const val VIDEO_INLINE_MAX_BYTES = 5L * 1024 * 1024

    /** 上传路由的客户端上限（云端 policy 可能更高，但手机端 100MB 已足够）。 */
    const val MAX_VIDEO_BYTES = 100L * 1024 * 1024

    /** base64 之后的估算请求体大小（图片 + 内联视频）。 */
    fun estimatedRequestBytes(attachments: List<Attachment>): Long {
        val inline = attachments.sumOf { attachment ->
            if (attachment.kind == AttachmentKind.VIDEO && attachment.sizeBytes > VIDEO_INLINE_MAX_BYTES) {
                0L
            } else {
                attachment.sizeBytes
            }
        }
        return ImageCodec.base64Length(inline)
    }

    /** 返回 null 表示可以发送，否则是给用户看的原因。 */
    fun validate(attachments: List<Attachment>): String? {
        val images = attachments.filter { it.kind == AttachmentKind.IMAGE }
        val videos = attachments.filter { it.kind == AttachmentKind.VIDEO }
        return when {
            images.size > MAX_IMAGES -> "最多只能发送 $MAX_IMAGES 张图片"
            images.sumOf { it.sizeBytes } > MAX_TOTAL_BYTES ->
                "图片总大小超过上限（压缩后约 ${MAX_TOTAL_BYTES / 1024 / 1024} MiB），请减少图片数量"
            videos.size > MAX_VIDEOS -> "最多只能发送 $MAX_VIDEOS 个视频"
            videos.any { it.sizeBytes > MAX_VIDEO_BYTES } ->
                "单个视频不能超过 ${MAX_VIDEO_BYTES / 1024 / 1024} MB"
            else -> null
        }
    }
}
