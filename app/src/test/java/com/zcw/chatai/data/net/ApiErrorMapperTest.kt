package com.zcw.chatai.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorMapperTest {

    @Test
    fun mapsStatusCodesToActionableChinese() {
        assertTrue(ApiErrorMapper.httpError(401, null).contains("API Key"))
        assertTrue(ApiErrorMapper.httpError(402, null).contains("余额不足"))
        assertTrue(ApiErrorMapper.httpError(429, null).contains("稍后重试"))
        assertTrue(ApiErrorMapper.httpError(500, null).contains("500"))
        assertTrue(ApiErrorMapper.httpError(503, null).contains("稍后重试"))
        assertTrue(ApiErrorMapper.httpError(404, null).contains("Base URL"))
    }

    /** 回归：网关把「模型不支持 OpenAI 兼容面」也报成 401（实测 OpenCode Go 的 Grok）。 */
    @Test
    fun doesNotBlameApiKeyForUnsupportedModelFormat() {
        val message = ApiErrorMapper.httpError(
            401,
            "Model grok-4.6 is not supported for format oa-compat",
        )
        assertTrue(message, message.contains("不支持当前接口格式"))
        assertFalse(message, message.contains("API Key 无效"))
    }

    @Test
    fun keepsDetailForRealUnauthorized() {
        val message = ApiErrorMapper.httpError(401, "Authentication Fails, Your api key is invalid")
        assertTrue(message, message.contains("API Key 无效"))
        assertTrue(message, message.contains("Authentication Fails"))
    }

    @Test
    fun keepsServerMessageForBadRequest() {
        val message = ApiErrorMapper.httpError(400, "model not found")
        assertTrue(message, message.contains("model not found"))
    }

    @Test
    fun flagsImageRelatedBadRequest() {
        val message = ApiErrorMapper.httpError(
            400,
            "You have uploaded an unsupported image. Please make sure your image is valid",
        )
        assertTrue(message, message.contains("不支持图片"))
    }

    @Test
    fun flagsToolPairingBadRequestAsAutoRepairable() {
        val message = ApiErrorMapper.httpError(
            400,
            "An assistant message with 'tool_calls' must be followed by tool messages " +
                "responding to each 'tool_call_id'. (insufficient tool messages following tool_calls message)",
        )
        assertTrue(message, message.contains("已自动修复"))
    }

    @Test
    fun detectsToolPairingMessages() {
        assertTrue(
            ApiErrorMapper.isToolPairingRelated(
                "An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'",
            ),
        )
        assertFalse(ApiErrorMapper.isToolPairingRelated("model not found"))
    }

    @Test
    fun truncatesLongServerMessage() {
        val message = ApiErrorMapper.httpError(422, "x".repeat(1000))
        assertTrue(message.length < 400)
    }

    @Test
    fun finishReasonStopIsNotAnError() {
        assertNull(ApiErrorMapper.finishReasonMessage("stop"))
        assertNull(ApiErrorMapper.finishReasonMessage(null))
        assertNull(ApiErrorMapper.finishReasonMessage(""))
    }

    /** 回归：`tool_calls` 是 Agent 循环的中间态，曾经被显示成「生成中断」。 */
    @Test
    fun toolCallsFinishReasonIsNotAnError() {
        assertNull(ApiErrorMapper.finishReasonMessage("tool_calls"))
    }

    @Test
    fun mapsFinishReasons() {
        assertTrue(
            ApiErrorMapper.finishReasonMessage("length").orEmpty().contains("输出长度上限"),
        )
        assertTrue(
            ApiErrorMapper.finishReasonMessage("insufficient_system_resource")
                .orEmpty()
                .contains("资源不足"),
        )
        assertTrue(ApiErrorMapper.finishReasonMessage("content_filter").orEmpty().contains("过滤"))
        assertTrue(ApiErrorMapper.finishReasonMessage("aborted").orEmpty().contains("中断"))
    }

    /** MiMo 的 421 = 内容安全拦截（错误码文档）。 */
    @Test
    fun mapsContentModerationBlock() {
        assertTrue(ApiErrorMapper.httpError(421, null).contains("内容安全审核未通过"))
        val withDetail = ApiErrorMapper.httpError(421, "sensitive content")
        assertTrue(withDetail, withDetail.contains("内容安全审核未通过"))
        assertTrue(withDetail, withDetail.contains("sensitive content"))
    }

    /** MiMo 的重复截断 finish_reason：可读提示，不能落进「生成中断（…）」兜底。 */
    @Test
    fun repetitionTruncationIsReadableNotGenericInterruption() {
        val message = ApiErrorMapper.finishReasonMessage("repetition_truncation").orEmpty()
        assertTrue(message, message.contains("重复"))
        assertFalse(message, message.contains("生成中断（"))
        // 正常结束与 Agent 中间态仍然不报错。
        assertNull(ApiErrorMapper.finishReasonMessage("stop"))
        assertNull(ApiErrorMapper.finishReasonMessage("tool_calls"))
    }

    @Test
    fun detectsImageRelatedMessages() {
        assertTrue(ApiErrorMapper.isImageRelated("unsupported image"))
        assertTrue(ApiErrorMapper.isImageRelated("Image in system message is unsupported"))
        assertTrue(ApiErrorMapper.isImageRelated("图片格式不受支持"))
        assertFalse(ApiErrorMapper.isImageRelated("invalid api key"))
    }

    @Test
    fun flagsVideoRelatedBadRequest() {
        val message = ApiErrorMapper.httpError(400, "The video format is not supported for this model")
        assertTrue(message, message.contains("不支持视频"))
    }

    @Test
    fun detectsVideoRelatedMessages() {
        assertTrue(ApiErrorMapper.isVideoRelated("unsupported video format"))
        assertTrue(ApiErrorMapper.isVideoRelated("不支持视频输入"))
        assertFalse(ApiErrorMapper.isVideoRelated("invalid api key"))
    }

    /** 回归：Go 网关未挂载的模型报 503 `Endpoint is unavailable`，重试无用，应该提示换模型。 */
    @Test
    fun flagsUnavailableEndpointInsteadOfRetry() {
        val message = ApiErrorMapper.httpError(
            503,
            "Upstream request failed: Endpoint is unavailable.",
        )
        assertTrue(message, message.contains("换个模型"))
        assertFalse(message, message.contains("稍后重试"))
        // 普通 503 仍是稍后重试。
        assertTrue(ApiErrorMapper.httpError(503, null).contains("稍后重试"))
    }
}
