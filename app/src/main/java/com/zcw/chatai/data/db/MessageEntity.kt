package com.zcw.chatai.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversation_id", "seq"])],
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "content", defaultValue = "")
    val content: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "reasoning_content")
    val reasoningContent: String?,
    @ColumnInfo(name = "seq")
    val seq: Long,
    @ColumnInfo(name = "model")
    val model: String?,
    @ColumnInfo(name = "prompt_tokens")
    val promptTokens: Int?,
    @ColumnInfo(name = "completion_tokens")
    val completionTokens: Int?,
    @ColumnInfo(name = "reasoning_tokens")
    val reasoningTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens")
    val cachedTokens: Int? = null,
    /** 思考耗时（毫秒）。NULL = 未测量（v3 之前的历史消息、没有思考的消息）。 */
    @ColumnInfo(name = "reasoning_ms")
    val reasoningMs: Long? = null,
    @ColumnInfo(name = "attachments")
    val attachments: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
