package com.zcw.chatai.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["updated_at"])],
)
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "model")
    val model: String,
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String,
    @ColumnInfo(name = "message_count", defaultValue = "0")
    val messageCount: Int,
    @ColumnInfo(name = "is_pinned", defaultValue = "false")
    val isPinned: Boolean,
)
