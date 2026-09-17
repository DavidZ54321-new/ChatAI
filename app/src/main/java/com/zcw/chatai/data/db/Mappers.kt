package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role

fun ConversationEntity.toModel(): Conversation = Conversation(
    id = id,
    title = title,
    model = model,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessagePreview = lastMessagePreview,
    messageCount = messageCount,
    isPinned = isPinned,
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    model = model,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessagePreview = lastMessagePreview,
    messageCount = messageCount,
    isPinned = isPinned,
)

fun MessageEntity.toModel(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = roleFromString(role),
    content = content,
    status = statusFromString(status),
    errorMessage = errorMessage,
    reasoningContent = reasoningContent,
    seq = seq,
    model = model,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = reasoningTokens,
    cachedTokens = cachedTokens,
    reasoningMs = reasoningMs,
    attachments = AttachmentCodec.decode(attachments),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    status = status.name,
    errorMessage = errorMessage,
    reasoningContent = reasoningContent,
    seq = seq,
    model = model,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = reasoningTokens,
    cachedTokens = cachedTokens,
    reasoningMs = reasoningMs,
    attachments = AttachmentCodec.encode(attachments),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun roleFromString(value: String): Role =
    Role.entries.firstOrNull { it.name == value } ?: Role.SYSTEM

private fun statusFromString(value: String): MessageStatus =
    MessageStatus.entries.firstOrNull { it.name == value } ?: MessageStatus.COMPLETE
