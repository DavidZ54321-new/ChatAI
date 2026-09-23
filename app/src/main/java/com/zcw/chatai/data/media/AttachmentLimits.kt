package com.zcw.chatai.data.media

import com.zcw.chatai.data.doc.DocumentLimits
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

    const val MAX_DOCUMENTS = 4

    /** 单个文档原文件上限（解析前按流式计数拦截，不 whole 读进内存再判）。 */
    const val MAX_DOC_BYTES = 20L * 1024 * 1024

    const val MAX_AUDIOS = 4

    /** 单个音频原始字节上限：MiMo base64 编码后 ≤50MB 硬线，原始字节留头 35MiB。 */
    const val MAX_AUDIO_BYTES = 35L * 1024 * 1024

    /**
     * 音频**合计**上限（音频始终内联、无上传路由）：单文件 35MiB × 4 = 140MiB
     * base64 后约 187MB，服务端大概率 413——本地先拦。40MiB ≈ 54MB base64，
     * 高于单文件线、低于已知的单文件 base64 硬线两倍。
     */
    const val MAX_AUDIO_TOTAL_BYTES = 40L * 1024 * 1024

    /**
     * **内联视频**合计上限（按供应商内联口径统计，上传路由的不计）。
     * Qwen 内联线 5MiB × 2 = 10MiB 永远够不着；MiMo 抬到 35MiB × 2 = 70MiB
     * 才可能越线（60MiB ≈ 80MB base64 起拦）。
     */
    const val MAX_INLINE_VIDEO_TOTAL_BYTES = 60L * 1024 * 1024

    /**
     * base64 之后的估算请求体大小（图片 + 按**全局 5MiB 口径**的内联视频；音频计入；文档以纯文本出站，另有 10 万字门限，不管这里）。
     * 仅估算工具——MiMo 抬高的内联视频（≤35MiB）不在这个口径里，
     * 发前门禁（`validate` / `audioGateOk` / `resolvePendingVideos`）按供应商口径另拦。
     */
    fun estimatedRequestBytes(attachments: List<Attachment>): Long {
        val inline = attachments.sumOf { attachment ->
            if (attachment.kind == AttachmentKind.DOCUMENT) {
                0L
            } else if (attachment.kind == AttachmentKind.VIDEO && attachment.sizeBytes > VIDEO_INLINE_MAX_BYTES) {
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
        val documents = attachments.filter { it.kind == AttachmentKind.DOCUMENT }
        val audios = attachments.filter { it.kind == AttachmentKind.AUDIO }
        return when {
            images.size > MAX_IMAGES -> "最多只能发送 $MAX_IMAGES 张图片"
            images.sumOf { it.sizeBytes } > MAX_TOTAL_BYTES ->
                "图片总大小超过上限（压缩后约 ${MAX_TOTAL_BYTES / 1024 / 1024} MiB），请减少图片数量"
            videos.size > MAX_VIDEOS -> "最多只能发送 $MAX_VIDEOS 个视频"
            videos.any { it.sizeBytes > MAX_VIDEO_BYTES } ->
                "单个视频不能超过 ${MAX_VIDEO_BYTES / 1024 / 1024} MB"
            documents.size > MAX_DOCUMENTS -> "最多只能发送 $MAX_DOCUMENTS 个文档"
            documents.any { it.sizeBytes > MAX_DOC_BYTES } ->
                "单个文档不能超过 ${MAX_DOC_BYTES / 1024 / 1024} MB"
            documents.sumOf { it.extractedChars } > DocumentLimits.MAX_DOCS_SEND_CHARS ->
                "本次发送的文档共 ${formatChars(documents.sumOf { it.extractedChars })}，" +
                    "超过 ${formatChars(DocumentLimits.MAX_DOCS_SEND_CHARS)}上限，请删减后发送"
            audios.size > MAX_AUDIOS -> "最多只能发送 $MAX_AUDIOS 个音频"
            audios.any { it.sizeBytes > MAX_AUDIO_BYTES } ->
                "单个音频不能超过 ${MAX_AUDIO_BYTES / 1024 / 1024} MB"
            audios.sumOf { it.sizeBytes } > MAX_AUDIO_TOTAL_BYTES ->
                "音频合计超过 ${MAX_AUDIO_TOTAL_BYTES / 1024 / 1024} MB 上限，请减少数量或压缩"
            else -> null
        }
    }

    internal fun formatChars(chars: Long): String {
        if (chars < 10_000) return "${chars}字"
        val wan = chars / 10_000
        val remainder = (chars % 10_000) / 1000
        return if (remainder == 0L) "${wan}万字" else "$wan.${remainder}万字"
    }
}
