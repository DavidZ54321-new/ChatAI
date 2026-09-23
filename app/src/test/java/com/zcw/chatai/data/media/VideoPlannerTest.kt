package com.zcw.chatai.data.media

import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlannerTest {

    private val now = 1_700_000_000_000L
    private val inlineMax = AttachmentLimits.VIDEO_INLINE_MAX_BYTES

    @Test
    fun freshRemoteUrlWithSameModelIsReused() {
        val attachment = video(
            size = 30 * 1024 * 1024,
            remoteUrl = "oss://a/b.mp4",
            remoteModel = "qwen3.8-max",
            remoteExpiresAt = now + 1000,
        )
        assertEquals(VideoPlan.Reuse("oss://a/b.mp4"), VideoPlanner.plan(attachment, "qwen3.8-max", now))
    }

    @Test
    fun remoteUrlForAnotherModelIsNotReused() {
        val attachment = video(
            size = 30 * 1024 * 1024,
            remoteUrl = "oss://a/b.mp4",
            remoteModel = "qwen3.8-max",
            remoteExpiresAt = now + 1000,
        )
        assertEquals(VideoPlan.Upload(null, null), VideoPlanner.plan(attachment, "qwen3.8-flash", now))
    }

    @Test
    fun expiredRemoteUrlIsReuploaded() {
        val attachment = video(
            size = 30 * 1024 * 1024,
            remoteUrl = "oss://a/b.mp4",
            remoteModel = "qwen3.8-max",
            remoteExpiresAt = now - 1,
        )
        assertEquals(VideoPlan.Upload(null, null), VideoPlanner.plan(attachment, "qwen3.8-max", now))
    }

    @Test
    fun smallFileIsInlinedEvenWithoutRemoteUrl() {
        assertEquals(VideoPlan.Inline, VideoPlanner.plan(video(size = inlineMax), "qwen3.8-max", now))
        assertEquals(VideoPlan.Inline, VideoPlanner.plan(video(size = 1), "qwen3.8-max", now))
    }

    @Test
    fun fileExactlyAtInlineBoundaryIsInlined() {
        assertEquals(VideoPlan.Inline, VideoPlanner.plan(video(size = 5 * 1024 * 1024), "m", now))
    }

    @Test
    fun oneByteOverBoundaryGoesToUpload() {
        assertEquals(
            VideoPlan.Upload(null, null),
            VideoPlanner.plan(video(size = 5 * 1024 * 1024 + 1), "m", now),
        )
    }

    @Test
    fun pendingJournalIsPassedThroughForResume() {
        val attachment = video(
            size = 30 * 1024 * 1024,
            pendingKey = "dashscope-instant/x/y.mp4",
            pendingPolicy = "{\"policy\":\"p\"}",
        )
        assertEquals(
            VideoPlan.Upload("dashscope-instant/x/y.mp4", "{\"policy\":\"p\"}"),
            VideoPlanner.plan(attachment, "m", now),
        )
    }

    @Test
    fun blankPendingValuesAreIgnored() {
        val attachment = video(size = 30 * 1024 * 1024, pendingKey = "  ", pendingPolicy = "")
        assertEquals(VideoPlan.Upload(null, null), VideoPlanner.plan(attachment, "m", now))
    }

    @Test
    fun uploadForbiddenAndOversizedYieldsTooLargeForInline() {
        // MiMo 场景：35MiB 上限、无 DashScope 路由。
        val limit = 35L * 1024 * 1024
        assertEquals(
            VideoPlan.TooLargeForInline(limit),
            VideoPlanner.plan(video(size = limit + 1), "m", now, inlineMaxBytes = limit, allowUpload = false),
        )
    }

    @Test
    fun uploadForbiddenButWithinInlineLimitStaysInline() {
        val limit = 35L * 1024 * 1024
        assertEquals(
            VideoPlan.Inline,
            VideoPlanner.plan(video(size = limit), "m", now, inlineMaxBytes = limit, allowUpload = false),
        )
    }

    @Test
    fun uploadAllowedKeepsLegacyUploadBeyondLimit() {
        // 默认参数（Qwen 等）行为不变：超限仍走上传。
        assertEquals(
            VideoPlan.Upload(null, null),
            VideoPlanner.plan(video(size = 5 * 1024 * 1024 + 1), "m", now),
        )
    }

    private fun video(
        size: Long,
        remoteUrl: String? = null,
        remoteModel: String? = null,
        remoteExpiresAt: Long? = null,
        pendingKey: String? = null,
        pendingPolicy: String? = null,
    ) = Attachment(
        id = "v1",
        kind = AttachmentKind.VIDEO,
        relativePath = "attachments/c/v1.mp4",
        mimeType = "video/mp4",
        width = 320,
        height = 240,
        sizeBytes = size,
        durationMs = 5_000,
        remoteUrl = remoteUrl,
        remoteModel = remoteModel,
        remoteExpiresAt = remoteExpiresAt,
        pendingKey = pendingKey,
        pendingPolicy = pendingPolicy,
    )
}
