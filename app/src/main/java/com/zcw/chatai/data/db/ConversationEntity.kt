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
    @ColumnInfo(name = "web_search_enabled", defaultValue = "0")
    val webSearchEnabled: Boolean = false,
    /**
     * 绑定的供应商 id（`ProviderCatalog` 的常量）；空串 = 跟随当前激活供应商
     * （迁移后的旧会话没有绑定信息，见 `MIGRATION_4_5`）。
     * SQL 默认值保留 'deepseek' 只为不动已导出的 v5 schema；运行时插入总是显式传值。
     */
    @ColumnInfo(name = "provider_id", defaultValue = "deepseek")
    val providerId: String = "",
)
