package com.zcw.chatai.data.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Chat 历史 → Responses `input` 的纯翻译（JVM 可测）。
 *
 * 协议差异（2026-09 真网关实测）：
 * - assistant 的 `tool_calls` 要拆成 `function_call` 项，`role=tool` 结果是
 *   `function_call_output` 项（不是 role=tool 消息），靠 `call_id` 配对；
 * - `reasoning_content` 回传没有语义（Responses 的 reasoning 项需要服务端加密内容），
 *   直接丢弃——普通回合本来也不回传，只有带工具调用的回合在 chat 面才带；
 * - 图片走 `input_image`（data URL 照搬）；**视频无对应语义**，整单拒绝，
 *   由调用方转成可读错误（不发注定 400 的请求）。
 */
object ResponsesHistory {

    sealed interface Result {
        data class Ok(val input: JsonArray) : Result

        /** 本回合含视频：Responses 面没有 `video_url` 对应物，不翻译。 */
        data object HasVideo : Result
    }

    fun translate(systemPrompt: String, messages: List<ChatRequestMessage>): Result {
        // 音频（MiMo `input_audio`）没有加 HasAudio 守卫：MiMo 不在 FailoverChatApi 的
        // responsesFallbacks 名单里（只有 OpenCode Go 的 Luna/Grok/Muse 走这面），永不触达。
        if (messages.any { it.videos.isNotEmpty() }) return Result.HasVideo
        return Result.Ok(
            buildJsonArray {
                if (systemPrompt.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", textParts(systemPrompt))
                        },
                    )
                }
                messages.forEach { message ->
                    when (message.role) {
                        "tool" -> add(
                            buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", message.toolCallId.orEmpty())
                                put("output", message.content)
                            },
                        )

                        "assistant" -> {
                            if (message.toolCalls.isNotEmpty()) {
                                if (message.content.isNotEmpty()) {
                                    add(assistantText(message.content))
                                }
                                message.toolCalls.forEach { call ->
                                    add(
                                        buildJsonObject {
                                            put("type", "function_call")
                                            put("call_id", call.id)
                                            put("name", call.name)
                                            put("arguments", call.arguments)
                                        },
                                    )
                                }
                            } else {
                                add(assistantText(message.content))
                            }
                        }

                        else -> add(userItem(message))
                    }
                }
            },
        )
    }

    private fun userItem(message: ChatRequestMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        put(
            "content",
            buildJsonArray {
                if (message.content.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", message.content)
                        },
                    )
                }
                message.images.forEach { image ->
                    image.label?.takeIf { it.isNotBlank() }?.let { label ->
                        add(
                            buildJsonObject {
                                put("type", "input_text")
                                put("text", label)
                            },
                        )
                    }
                    add(
                        buildJsonObject {
                            put("type", "input_image")
                            put("image_url", image.dataUrl)
                            // Responses 只认 low/high/auto；chat 面的 original 在这里无意义。
                            image.detail?.takeIf { it == "low" || it == "high" || it == "auto" }?.let {
                                put("detail", it)
                            }
                        },
                    )
                }
            },
        )
    }

    private fun assistantText(text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "output_text")
                        put("text", text)
                    },
                )
            },
        )
    }

    private fun textParts(text: String): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "input_text")
                put("text", text)
            },
        )
    }
}
