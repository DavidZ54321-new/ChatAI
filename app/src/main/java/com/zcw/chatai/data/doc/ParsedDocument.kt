package com.zcw.chatai.data.doc

/** 解析出的纯文本：门内有多少发多少，不截断（上限只在发送前按会话配额拦）。 */
data class ParsedDocument(
    val text: String,
    /** 模型可见的规模标注，如 "共 12 页" / "共 3 个工作表"；没有时为 null。 */
    val meta: String? = null,
)
