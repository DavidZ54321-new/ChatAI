package com.zcw.chatai.data.net

/**
 * HTTP 状态码与 `finish_reason` → 可读中文提示。纯函数，JVM 单测覆盖。
 *
 * 只按标准语义映射（不针对任何厂商），服务端自带的 message 优先展示。
 */
object ApiErrorMapper {

    private const val MAX_DETAIL = 300

    fun httpError(status: Int, serverMessage: String?): String {
        val detail = serverMessage?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DETAIL)
        return when {
            status == 400 && detail != null && isImageRelated(detail) ->
                "该模型可能不支持图片，或图片格式/尺寸不受支持：$detail"

            status == 400 && detail != null && isVideoRelated(detail) ->
                "该模型可能不支持视频输入，或视频格式/大小不受支持：$detail"

            // 历史里工具应答缺失/错序导致的 400。组装层已能自动修复，重试即可。
            status == 400 && detail != null && isToolPairingRelated(detail) ->
                "对话记录里的工具调用缺少应答（已自动修复），请重试：$detail"

            status == 400 -> detail ?: "请求格式有误（400）"
            // 网关把「模型不支持 OpenAI 兼容面」也报成 401（实测 OpenCode Go 的 Grok/GPT），
            // 不能一律说成 API Key 问题。
            status == 401 && detail != null && isUnsupportedFormat(detail) ->
                "该模型不支持当前接口格式（OpenAI 兼容面）：$detail"
            status == 401 -> detail?.let { "API Key 无效或已过期：$it" }
                ?: "API Key 无效或已过期，请到设置里检查"
            status == 402 -> "账户余额不足，请充值后重试"
            status == 403 -> detail ?: "没有访问权限（403）"
            status == 404 -> "接口不存在（404），请检查 Base URL 是否填写正确"
            status == 413 -> "请求体过大，请减少图片/视频数量或降低图片精度"
            status == 422 -> "服务端不接受该参数：${detail ?: "参数错误（422）"}"
            status == 429 -> "请求过于频繁或已达速率上限，请稍后重试"
            // 网关把「该模型没挂到这个协议面」也报成 503（实测 OpenCode Go 的
            // grok-4.6 / gpt-5.6-luna / muse-spark 走 chat/completions 全是
            // `Endpoint is unavailable`）——这不是稍后重试能解决的，得换模型。
            status in listOf(502, 503, 504) && detail != null && isEndpointUnavailable(detail) ->
                "该模型在当前接口上不可用，换个模型试试：$detail"
            status == 500 -> "服务端出错了（500），请稍后重试"
            status == 502 || status == 503 || status == 504 -> "服务暂时不可用，请稍后重试"
            status >= 500 -> "服务端异常（$status），请稍后重试"
            else -> detail?.let { "HTTP $status：$it" } ?: "HTTP $status"
        }
    }

    /** 图片相关的 400（例如 `unsupported image` / `Image in system message is unsupported`）。 */
    fun isImageRelated(detail: String): Boolean {
        val lower = detail.lowercase()
        return "image" in lower || "图片" in detail || "vision" in lower
    }

    /** 视频相关的 400（例如 `video format is not supported` / 「不支持视频输入」）。 */
    fun isVideoRelated(detail: String): Boolean {
        val lower = detail.lowercase()
        return "video" in lower || "视频" in detail
    }

    /** 模型/接口格式不匹配（例如 `Model grok-4.6 is not supported for format oa-compat`）。 */
    fun isUnsupportedFormat(detail: String): Boolean {
        val lower = detail.lowercase()
        return "not supported for format" in lower ||
            "unsupported model" in lower ||
            "does not support" in lower
    }

    /** 模型没挂到当前协议面（`Endpoint is unavailable`）：换模型，重试无用。 */
    fun isEndpointUnavailable(detail: String): Boolean =
        "endpoint is unavailable" in detail.lowercase()

    /** 工具应答缺失/错序导致的 400（`insufficient tool messages following tool_calls message`）。 */
    fun isToolPairingRelated(detail: String): Boolean {
        val lower = detail.lowercase()
        return "insufficient tool messages" in lower ||
            ("tool_calls" in lower && "must be followed by tool messages" in lower)
    }

    /** 正常结束（`stop`/null）返回 null，其余给可读提示。 */
    fun finishReasonMessage(reason: String?): String? = when (reason) {
        null, "", "stop" -> null
        // `tool_calls` 是 Agent 循环的中间态，不是错误/截断。
        "tool_calls" -> null
        "length" -> "已达输出长度上限，回答被截断。可在设置里提高回复长度上限，或关闭思考模式"
        "content_filter" -> "输出被内容策略过滤，请调整提问方式"
        "insufficient_system_resource" -> "服务端推理资源不足，本次生成被中断，可直接重试"
        "aborted" -> "生成被中断"
        else -> "生成中断（$reason）"
    }
}
