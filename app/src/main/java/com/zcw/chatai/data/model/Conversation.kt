package com.zcw.chatai.data.model

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
)
