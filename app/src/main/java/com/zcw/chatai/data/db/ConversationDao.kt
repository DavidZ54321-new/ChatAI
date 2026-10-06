package com.zcw.chatai.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE kind = :kind ORDER BY is_pinned DESC, updated_at DESC")
    fun observeByKind(kind: String): Flow<List<ConversationEntity>>

    /**
     * 标题或任一条消息正文命中的会话（`pattern` 由 [LikePattern.contains] 转义好）。
     * 排序与 [observeByKind] 一致。限定 [kind]，避免生图会话混进普通对话。
     *
     * 每个会话只取 `seq` 最早的一条命中，`instr` 只算这一次，再裁成命中点前后的小窗。
     * `snippet_start` 是小窗在正文里的起点（从 1 计），`snippet_full_length` 是那条正文的字符数。
     * 只有标题命中时这三列是 NULL。
     */
    @Query(
        """
        SELECT
          id, title, model, system_prompt, created_at, updated_at,
          last_message_preview, message_count, is_pinned, web_search_enabled,
          provider_id, persona_id, parent_conversation_id, kind,
          CASE
            WHEN hit_pos > 0 THEN substr(hit_content, max(1, hit_pos - :pad), :span)
          END AS match_snippet,
          CASE
            WHEN hit_pos > 0 THEN max(1, hit_pos - :pad)
          END AS snippet_start,
          CASE
            WHEN hit_pos > 0 THEN hit_length
          END AS snippet_full_length
        FROM (
          SELECT
            c.id, c.title, c.model, c.system_prompt, c.created_at, c.updated_at,
            c.last_message_preview, c.message_count, c.is_pinned, c.web_search_enabled,
            c.provider_id, c.persona_id, c.parent_conversation_id, c.kind,
            m.content AS hit_content,
            CASE
              WHEN m.content IS NULL THEN 0
              ELSE instr(lower(m.content), lower(:needle))
            END AS hit_pos,
            length(m.content) AS hit_length
          FROM conversations c
          LEFT JOIN messages m ON m.id = (
            SELECT m2.id FROM messages m2
            WHERE m2.conversation_id = c.id
              AND m2.content LIKE :pattern ESCAPE '\'
            ORDER BY m2.seq ASC
            LIMIT 1
          )
          WHERE c.kind = :kind
            AND (
              c.title LIKE :pattern ESCAPE '\'
              OR m.id IS NOT NULL
            )
        ) AS found
        ORDER BY is_pinned DESC, updated_at DESC
        """,
    )
    fun search(
        kind: String,
        pattern: String,
        needle: String,
        pad: Int,
        span: Int,
    ): Flow<List<ConversationSearchRow>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT COUNT(*) FROM conversations WHERE provider_id = :providerId")
    suspend fun countByProviderId(providerId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET model = :model, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateModel(id: String, model: String, updatedAt: Long)

    /** 换供应商必须同时换模型：分两条语句会留下「新供应商 + 旧模型」的中间态。 */
    @Query("UPDATE conversations SET provider_id = :providerId, model = :model, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateProviderAndModel(id: String, providerId: String, model: String, updatedAt: Long)

    /** 换角色只改绑定：提示词与生成参数按 persona_id 实时解析，不落库。 */
    @Query("UPDATE conversations SET persona_id = :personaId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePersona(id: String, personaId: String, updatedAt: Long)

    @Query("UPDATE conversations SET last_message_preview = :preview, message_count = :count, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSummary(id: String, preview: String, count: Int, updatedAt: Long)

    @Query("UPDATE conversations SET web_search_enabled = :enabled WHERE id = :id")
    suspend fun updateWebSearchEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    // ---------- 备份 / 还原 ----------

    @Query("SELECT * FROM conversations")
    suspend fun getAll(): List<ConversationEntity>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<ConversationEntity>)
}

/** 搜索一行：会话本身，外加命中小窗和它在正文里的位置。小窗不落库。 */
data class ConversationSearchRow(
    @Embedded val conversation: ConversationEntity,
    @ColumnInfo(name = "match_snippet") val matchSnippet: String?,
    /** 小窗在正文中的起点，从 1 计。没有正文命中时是 null。 */
    @ColumnInfo(name = "snippet_start") val snippetStart: Int?,
    /** 那条命中正文的字符数。没有正文命中时是 null。 */
    @ColumnInfo(name = "snippet_full_length") val snippetFullLength: Int?,
)
