package com.zcw.chatai.data.media

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.net.ChatRequestVideo
import com.zcw.chatai.data.net.DashScopeUpload
import com.zcw.chatai.data.net.PendingUpload
import com.zcw.chatai.data.net.UploadOutcome
import com.zcw.chatai.data.net.UploadPolicy
import com.zcw.chatai.data.provider.ProviderCatalog
import kotlinx.serialization.json.Json

/**
 * 视频附件的解析器：把本地视频变成可出站的部件（内联 data URL / oss:// URL）。
 *
 * 崩溃安全的关键顺序：**先落 `pendingKey`+凭证日志，再发上传**；
 * 上传成功（或 409 = 云端已完整）后立刻落 `remoteUrl`，重发/重试绝不二次上传。
 * [persist] 由仓库层注入（写回 messages.attachments）。
 * 内联上限与是否可上传由供应商预设决定（MiMo 只内联、Qwen 走 DashScope）。
 */
class VideoUploadCoordinator(
    private val attachmentStore: AttachmentStore,
    private val upload: DashScopeUpload,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun resolve(
        config: ChatConfig,
        attachment: Attachment,
        persist: suspend (Attachment) -> Unit,
    ): Pair<ChatRequestVideo, Attachment> = when (
        val plan = VideoPlanner.plan(
            attachment,
            config.model,
            nowMs(),
            inlineMaxBytes = ProviderCatalog.videoInlineMaxBytesFor(config.providerId),
            allowUpload = ProviderCatalog.videoUploadViaDashScope(config.providerId),
        )
    ) {
        VideoPlan.Inline -> {
            val wire = attachmentStore.toRequestVideo(attachment)
                ?: throw AttachmentException("视频文件不可读，请重新发送")
            wire to attachment
        }

        is VideoPlan.Reuse -> ChatRequestVideo(plan.url, isOss = true) to attachment

        is VideoPlan.TooLargeForInline -> {
            val mb = plan.limitBytes / 1024 / 1024
            throw AttachmentException(
                "视频超过 ${mb} MB 上限（当前供应商仅支持内联发送），请压缩或剪短",
            )
        }

        is VideoPlan.Upload -> {
            val pending = plan.resumePolicy?.let(::parsePending)
            val canResume = plan.resumeKey != null &&
                pending != null &&
                pending.expiresAtMs > nowMs() &&
                pending.policy.isComplete
            val policy: UploadPolicy = if (canResume) {
                pending!!.policy
            } else {
                upload.fetchPolicy(config, config.model)
            }
            val key = if (canResume) plan.resumeKey!! else "${policy.uploadDir}/${attachment.id}.mp4"

            val limitBytes = policy.maxFileSizeMb.toLong() * 1024 * 1024
            if (attachment.sizeBytes > limitBytes) {
                throw AttachmentException(
                    "云端对该模型的视频上限是 ${policy.maxFileSizeMb} MB，当前文件过大",
                )
            }

            // 先落日志（凭证 + 绝对过期时间），再发 POST：中途崩溃也能免二次上传。
            val journaled = attachment.copy(
                pendingKey = key,
                pendingPolicy = json.encodeToString(
                    PendingUpload.serializer(),
                    PendingUpload(
                        policy = policy,
                        expiresAtMs = nowMs() + policy.expireInSeconds * 1000L,
                    ),
                ),
            )
            persist(journaled)

            val file = attachmentStore.fileOf(attachment)
            if (!file.isFile) throw AttachmentException("视频文件已丢失，无法上传")

            when (val outcome = upload.upload(file, key, policy)) {
                is UploadOutcome.Failure -> throw AttachmentException("视频上传失败：${outcome.message}")

                UploadOutcome.Success, UploadOutcome.AlreadyExists -> {
                    val updated = journaled.copy(
                        remoteUrl = upload.urlFor(key),
                        remoteModel = config.model,
                        remoteExpiresAt = nowMs() + REMOTE_TTL_MS,
                        pendingKey = null,
                        pendingPolicy = null,
                    )
                    persist(updated)
                    ChatRequestVideo(updated.remoteUrl!!, isOss = true) to updated
                }
            }
        }
    }

    private fun parsePending(raw: String): PendingUpload? = try {
        json.decodeFromString(PendingUpload.serializer(), raw)
    } catch (t: Exception) {
        null
    }

    companion object {
        /** 官方临时 URL 有效期 48 小时；本地留 47h 余量，到期前重传。 */
        const val REMOTE_TTL_MS = 47L * 60 * 60 * 1000
    }
}
