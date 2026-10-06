package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.Conversation
import com.zcw.chatai.data.model.ConversationKind
import com.zcw.chatai.data.model.Message
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.MessageCitation
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.model.SearchSnippet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun ConversationEntity.toModel(searchSnippet: String? = null): Conversation = Conversation(
    id = id,
    title = title,
    model = model,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessagePreview = lastMessagePreview,
    messageCount = messageCount,
    isPinned = isPinned,
    webSearchEnabled = webSearchEnabled,
    providerId = providerId,
    personaId = personaId,
    parentConversationId = parentConversationId,
    kind = kindFromString(kind),
    searchSnippet = searchSnippet,
)

/**
 * 按种类搜索会话。窗口宽度和命中片段的折叠都在这里，仓库只传种类和原文。
 */
fun ConversationDao.searchKind(kind: ConversationKind, query: String): Flow<List<Conversation>> {
    val span = SearchSnippet.spanFor(query)
    return search(
        kind = kind.name,
        pattern = LikePattern.contains(query),
        needle = query,
        pad = SearchSnippet.PAD,
        span = span,
    ).map { rows -> rows.map { it.toModel(query, span) } }
}

private fun ConversationSearchRow.toModel(query: String, span: Int): Conversation {
    val window = matchSnippet
    val start = snippetStart
    val full = snippetFullLength
    val text = if (window != null && start != null && full != null) {
        SearchSnippet.present(
            window = window,
            query = query,
            leadTrimmed = SearchSnippet.isLeadTrimmed(start),
            tailTrimmed = SearchSnippet.isTailTrimmed(start, full, span),
        )?.text
    } else {
        null
    }
    return conversation.toModel(searchSnippet = text)
}

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
    webSearchEnabled = webSearchEnabled,
    providerId = providerId,
    personaId = personaId,
    parentConversationId = parentConversationId,
    kind = kind.name,
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
    toolCalls = ToolCallCodec.decodeCalls(toolCalls),
    toolCallId = toolCallId,
    toolResult = ToolCallCodec.decodeResult(toolResult),
    citations = MessageCitationCodec.decode(citations),
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
    toolCalls = ToolCallCodec.encodeCalls(toolCalls),
    toolCallId = toolCallId,
    toolResult = ToolCallCodec.encodeResult(toolResult),
    citations = MessageCitationCodec.encode(citations),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun roleFromString(value: String): Role =
    Role.entries.firstOrNull { it.name == value } ?: Role.SYSTEM

private fun statusFromString(value: String): MessageStatus =
    MessageStatus.entries.firstOrNull { it.name == value } ?: MessageStatus.COMPLETE

/** 未知/缺失的 kind 一律回落普通对话：生图会话只会是我们自己写进去的。 */
private fun kindFromString(value: String): ConversationKind =
    ConversationKind.entries.firstOrNull { it.name == value } ?: ConversationKind.CHAT
