package com.zcw.chatai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY seq ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY seq ASC")
    suspend fun getByConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE conversation_id = :conversationId")
    suspend fun nextSeq(conversationId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, reasoning_content = :reasoning, reasoning_ms = :reasoningMs, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateContent(
        id: String,
        content: String,
        reasoning: String?,
        reasoningMs: Long?,
        updatedAt: Long,
    )

    @Query("UPDATE messages SET content = :content, reasoning_content = :reasoning, status = :status, error_message = :errorMessage, prompt_tokens = :promptTokens, completion_tokens = :completionTokens, reasoning_tokens = :reasoningTokens, cached_tokens = :cachedTokens, reasoning_ms = :reasoningMs, updated_at = :updatedAt WHERE id = :id")
    suspend fun finalize(
        id: String,
        content: String,
        reasoning: String?,
        status: String,
        errorMessage: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        reasoningTokens: Int?,
        cachedTokens: Int?,
        reasoningMs: Long?,
        updatedAt: Long,
    )

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND seq >= :seq ORDER BY seq ASC")
    suspend fun getFrom(conversationId: String, seq: Long): List<MessageEntity>

    /** 按 id 集合删除（分组规则由 `ToolTurnGrouping` 决定，SQL 不做配对推断）。 */
    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId AND seq >= :seq")
    suspend fun deleteFrom(conversationId: String, seq: Long)

    /** 给插入腾位置：把 [fromSeq] 起的行整体后移一位（seq 只有非唯一索引）。 */
    @Query("UPDATE messages SET seq = seq + 1 WHERE conversation_id = :conversationId AND seq >= :fromSeq")
    suspend fun shiftSeqsFrom(conversationId: String, fromSeq: Long)

    @Query("UPDATE messages SET tool_calls = :toolCalls, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolCalls(id: String, toolCalls: String?, updatedAt: Long)

    /** 回写附件元数据（视频上传日志/远端的 URL 会随上传过程更新）。 */
    @Query("UPDATE messages SET attachments = :attachments, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateAttachments(id: String, attachments: String?, updatedAt: Long)

    @Query("UPDATE messages SET content = :content, tool_result = :toolResult, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolResultContent(id: String, content: String, toolResult: String?, updatedAt: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countByConversation(conversationId: String): Int

    /** 所有附件元数据（JSON），用于孤儿文件清理。 */
    @Query("SELECT attachments FROM messages WHERE attachments IS NOT NULL")
    suspend fun getAllAttachmentJson(): List<String>
}
