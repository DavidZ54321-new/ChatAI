package com.zcw.chatai.data.db

/**
 * SQLite `LIKE` 通配符转义：用户输入里的 `%`、`_`、`\` 必须当字面量，
 * 否则搜「100%」会命中任意内容。配合查询里的 `ESCAPE '\'` 使用。
 * 纯字符串处理（不用 Regex），JVM 单测覆盖。
 */
object LikePattern {

    /** `contains` 语义的匹配串：`%<转义后的 query>%`。空串返回 `%%`（匹配一切）。 */
    fun contains(query: String): String = "%" + escape(query) + "%"

    /** 逐个字符转义：`\` → `\\`，`%` → `\%`，`_` → `\_`。 */
    fun escape(query: String): String {
        val out = StringBuilder(query.length)
        for (ch in query) {
            when (ch) {
                '\\', '%', '_' -> out.append('\\').append(ch)
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
