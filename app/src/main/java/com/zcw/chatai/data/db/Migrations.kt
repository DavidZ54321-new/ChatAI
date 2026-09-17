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
