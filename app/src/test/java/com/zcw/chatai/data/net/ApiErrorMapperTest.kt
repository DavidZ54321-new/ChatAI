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

    @Test
    fun detectsImageRelatedMessages() {
        assertTrue(ApiErrorMapper.isImageRelated("unsupported image"))
        assertTrue(ApiErrorMapper.isImageRelated("Image in system message is unsupported"))
        assertTrue(ApiErrorMapper.isImageRelated("图片格式不受支持"))
        assertFalse(ApiErrorMapper.isImageRelated("invalid api key"))
    }
}
