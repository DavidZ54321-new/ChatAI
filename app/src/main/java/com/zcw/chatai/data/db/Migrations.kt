package com.zcw.chatai.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2：消息增加附件元数据与用量明细。
 *
 * - `attachments`：附件数组的 JSON（`AttachmentCodec`），二进制在应用私有目录
 * - `reasoning_tokens` / `cached_tokens`：思考 token 与上下文缓存命中 token（用量展示）
 *
 * 均为可空列，旧行为 NULL，不做破坏性迁移。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN reasoning_tokens INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN cached_tokens INTEGER")
    }
}

/**
 * v2 → v3：消息增加思考耗时。
 *
 * - `reasoning_ms`：毫秒整数。存毫秒不存秒，精度不丢，界面想显示成「9s」还是「1分12秒」都行
 * - 可空：NULL 明确表示**未测量**（老消息、没有思考的消息），与「0ms（瞬间完成）」区分开
 * - 与 `reasoning_tokens` 配对：一个是思考花了多少 token，一个是思考花了多久
 *
 * 老数据保留 `reasoning_content`，只是没有时长——界面显示「已深度思考」而不假装是 0s。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN reasoning_ms INTEGER")
    }
}
