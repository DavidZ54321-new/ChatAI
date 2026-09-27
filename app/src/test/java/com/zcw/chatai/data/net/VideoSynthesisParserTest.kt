package com.zcw.chatai.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSynthesisParserTest {

    @Test
    fun parsesSubmittedTaskId() {
        val raw = """
            {"request_id":"r1","output":{"task_id":"abc-123","task_status":"PENDING"}}
        """.trimIndent()
        val outcome = VideoSynthesisParser.parseSubmission(raw)
        assertTrue(outcome is VideoSynthesisOutcome.Submitted)
        outcome as VideoSynthesisOutcome.Submitted
        assertEquals("abc-123", outcome.taskId)
        assertEquals("PENDING", outcome.status)
    }

    @Test
    fun submissionFailureReadsTopLevelCode() {
        val raw = """{"code":"InvalidApiKey","message":"bad key"}"""
        val outcome = VideoSynthesisParser.parseSubmission(raw)
        assertTrue(outcome is VideoSynthesisOutcome.Failure)
        outcome as VideoSynthesisOutcome.Failure
        assertEquals("InvalidApiKey", outcome.code)
        assertEquals("bad key", outcome.message)
    }

    @Test
    fun submissionMalformedWhenNoTaskIdAndNoCode() {
        assertEquals(VideoSynthesisOutcome.Malformed, VideoSynthesisParser.parseSubmission("{}"))
        assertEquals(VideoSynthesisOutcome.Malformed, VideoSynthesisParser.parseSubmission("not json"))
    }

    @Test
    fun parsesTaskStatuses() {
        assertEquals(VideoTaskOutcome.Pending, task("PENDING"))
        assertEquals(VideoTaskOutcome.Running, task("RUNNING"))
        assertEquals(VideoTaskOutcome.Canceled, task("CANCELED"))
        assertEquals(VideoTaskOutcome.Canceled, task("CANCELLED"))
    }

    @Test
    fun parsesSucceededVideoUrl() {
        val outcome = task("SUCCEEDED", extra = "\"video_url\":\"https://x/y.mp4\"")
        assertTrue(outcome is VideoTaskOutcome.Succeeded)
        assertEquals("https://x/y.mp4", (outcome as VideoTaskOutcome.Succeeded).url)
    }

    @Test
    fun succeededWithoutUrlIsFailure() {
        val outcome = task("SUCCEEDED")
        assertTrue(outcome is VideoTaskOutcome.Failed)
    }

    @Test
    fun parsesFailedWithOutputCode() {
        val outcome = task("FAILED", extra = "\"code\":\"DataInspectionFailed\",\"message\":\"blocked\"")
        assertTrue(outcome is VideoTaskOutcome.Failed)
        outcome as VideoTaskOutcome.Failed
        assertEquals("DataInspectionFailed", outcome.code)
        assertEquals("blocked", outcome.message)
    }

    @Test
    fun unknownStatusIsMalformed() {
        assertEquals(VideoTaskOutcome.Malformed, task("WEIRD"))
        assertEquals(VideoTaskOutcome.Malformed, VideoSynthesisParser.parseTask("nope"))
    }

    /** 官方语义：task_id 超过 24 小时有效期 → UNKNOWN，重试无意义。 */
    @Test
    fun unknownTaskStatusIsExpired() {
        assertEquals(VideoTaskOutcome.Expired, task("UNKNOWN"))
    }

    private fun task(status: String, extra: String = ""): VideoTaskOutcome {
        val tail = if (extra.isBlank()) "" else ",$extra"
        return VideoSynthesisParser.parseTask(
            """{"output":{"task_id":"t","task_status":"$status"$tail}}""",
        )
    }
}
