package com.zcw.chatai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY is_pinned DESC, updated_at DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    /**
     * 标题或任一条消息正文命中 `pattern` 的会话（`pattern` 由 [LikePattern.contains] 转义好）。
     * 一个会话多条消息命中时用 DISTINCT 去重，排序与 [observeAll] 一致。
     */
    @Query(
        """
        SELECT DISTINCT c.* FROM conversations c
        LEFT JOIN messages m ON m.conversation_id = c.id
        WHERE c.title LIKE :pattern ESCAPE '\'
           OR m.content LIKE :pattern ESCAPE '\'
        ORDER BY c.is_pinned DESC, c.updated_at DESC
        """,
    )
    fun search(pattern: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

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
}
