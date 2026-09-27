package com.zcw.chatai.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条视频生成任务（DashScope 异步任务）。
 *
 * 与助手消息行**解耦**：异步任务跨进程存活（WorkManager 轮询），进程被杀后靠本表恢复——
 * 只知道助手消息是 STREAMING 是不够的，还得知道远端 `task_id` 与请求参数。
 *
 * [requestJson] 只存 prompt + 参数 + 输入附件 id（不含 base64 data URL，那个提交时现组，
 * 免得把几 MB 的字符串塞进数据库）。
 */
@Entity(
    tableName = "video_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversation_id"]), Index(value = ["status"])],
)
data class VideoTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    /** 对应的助手占位消息行 id（结果落到它上面）。 */
    @ColumnInfo(name = "message_id")
    val messageId: String,
    /** 远端任务 id；提交成功后才写。null = 还没提交。 */
    @ColumnInfo(name = "remote_task_id")
    val remoteTaskId: String? = null,
    @ColumnInfo(name = "model")
    val model: String,
    /** 生成模式（`VideoMode` 的名字：T2V/I2V/R2V）。 */
    @ColumnInfo(name = "mode")
    val mode: String,
    /** `VideoGenRequest` 的序列化（prompt + 参数 + 输入附件引用）。 */
    @ColumnInfo(name = "request_json")
    val requestJson: String,
    /** 任务状态（`VideoTaskStatus` 的名字：PENDING/SUBMITTED/RUNNING/SUCCEEDED/FAILED/CANCELLED）。 */
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
