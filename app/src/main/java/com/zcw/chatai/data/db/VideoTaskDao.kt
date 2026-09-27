package com.zcw.chatai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoTaskDao {

    @Query("SELECT * FROM video_tasks WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeByConversation(conversationId: String): Flow<List<VideoTaskEntity>>

    @Query("SELECT * FROM video_tasks WHERE status IN (:activeStatuses) ORDER BY created_at ASC")
    fun observeActive(activeStatuses: List<String>): Flow<List<VideoTaskEntity>>

    @Query("SELECT * FROM video_tasks WHERE id = :id")
    suspend fun getById(id: String): VideoTaskEntity?

    @Query("SELECT * FROM video_tasks WHERE message_id = :messageId LIMIT 1")
    suspend fun getByMessage(messageId: String): VideoTaskEntity?

    @Query("SELECT * FROM video_tasks WHERE conversation_id = :conversationId AND status IN (:activeStatuses) ORDER BY created_at ASC")
    suspend fun getActiveForConversation(conversationId: String, activeStatuses: List<String>): List<VideoTaskEntity>

    /** 未到终态的任务：冷启动对账（重排 worker）用。 */
    @Query("SELECT * FROM video_tasks WHERE status IN (:activeStatuses) ORDER BY created_at ASC")
    suspend fun getActive(activeStatuses: List<String>): List<VideoTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: VideoTaskEntity)

    @Query("UPDATE video_tasks SET remote_task_id = :remoteTaskId, status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateRemote(
        id: String,
        remoteTaskId: String,
        status: String,
        updatedAt: Long,
    )

    @Query("UPDATE video_tasks SET status = :status, error_message = :errorMessage, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        errorMessage: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM video_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM video_tasks WHERE message_id IN (:messageIds)")
    suspend fun deleteByMessageIds(messageIds: List<String>)

    @Query("DELETE FROM video_tasks WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
