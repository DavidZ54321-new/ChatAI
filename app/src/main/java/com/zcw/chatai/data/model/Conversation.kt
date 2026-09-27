package com.zcw.chatai.data.model

/**
 * 会话种类：普通对话 / 生图 / 视频生成。
 * 与消息表共用一套存储，只靠 `conversations.kind` 判别列区分，列表按它各自过滤。
 */
enum class ConversationKind { CHAT, IMAGE, VIDEO }

data class Conversation(
    val id: String,
    val title: String,
    val model: String,
    val systemPrompt: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String,
    val messageCount: Int,
    val isPinned: Boolean,
    val webSearchEnabled: Boolean = false,
    /** 绑定的供应商 id；决定用哪套连接配置与原生工具。空串 = 跟随当前激活供应商。 */
    val providerId: String = "",
    /** 绑定的角色 id；决定提示词与生成参数。空串 = 跟随当前激活角色。 */
    val personaId: String = "",
    /** 分支的来源会话 id；空串 = 普通会话。只用于列表里的父子嵌套展示。 */
    val parentConversationId: String = "",
    /** 会话种类；普通对话 / 生图 / 视频各自只在自己的列表里出现。 */
    val kind: ConversationKind = ConversationKind.CHAT,
)
