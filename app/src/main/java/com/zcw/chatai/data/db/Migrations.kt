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

/**
 * v3 → v4：Agent 工具调用落地。
 *
 * - `messages.tool_calls`：assistant 一步内请求的所有函数调用（JSON 数组）
 * - `messages.tool_call_id`：`role=TOOL` 行对应的调用 id
 * - `messages.tool_result`：结构化工具结果（ToolCallCodec），正文同时写进 `content`
 * - `conversations.web_search_enabled`：🌐 开关按会话记忆
 *
 * 均为可空/带默认值，旧数据行为 NULL/0，不做破坏性迁移。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN tool_calls TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN tool_call_id TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN tool_result TEXT")
        db.execSQL("ALTER TABLE conversations ADD COLUMN web_search_enabled INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v4 → v5：会话绑定供应商。
 *
 * - `conversations.provider_id`：写 `ProviderCatalog` 的稳定 id（deepseek/qwen/opencode-go/custom）。
 *   旧版本只有一套**全局**连接配置，所以旧会话统一回填空串 = 跟随当前激活供应商
 *   （`resolveConfig` 把空串解析成激活供应商，行为与旧版一致）。
 *   不能写死 deepseek：旧配置可能是 Qwen/自建端点，写死会让这些会话打开就报「供应商已被删除」，
 *   而 Room 迁移又读不到 DataStore，无法按基址推断。
 * - 连接配置从 DataStore 的单套平铺 key 迁到 `providers_json`（懒迁移，见 SettingsRepository）。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN provider_id TEXT NOT NULL DEFAULT 'deepseek'")
        db.execSQL("UPDATE conversations SET provider_id = ''")
    }
}

/**
 * v5 → v6：会话绑定角色。
 *
 * - `conversations.persona_id`：`personas_json` 表的 key；空串 = 跟随当前激活角色
 *   （与 `provider_id` 同语义，旧会话统一回填空串，行为与旧版全局提示词一致）。
 * - `conversations.system_prompt` 列保留但不再读取：历史写入恒为 null，
 *   提示词统一按 persona_id 实时解析。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN persona_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE conversations SET persona_id = ''")
    }
}

/**
 * v6 → v7：会话支持分支（从某条 AI 回复签出一个自包含的新会话）。
 *
 * - `conversations.parent_conversation_id`：分支的来源会话 id；空串 = 普通会话。
 *   不需要 `UPDATE` 回填——`''` 本身就是「非分支」的合法取值，与 `provider_id` 不同
 *   （那个必须回填是因为旧版本的语义是「全局唯一配置」，写死某个 id 会导致误判）。
 * - 只做父子指针，不加 `branch_from_message_id`：树形展示只需要父指针。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN parent_conversation_id TEXT NOT NULL DEFAULT ''")
    }
}
