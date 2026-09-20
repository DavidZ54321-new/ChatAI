package com.zcw.chatai.data.doc

/** 文档文本侧的上限（纯逻辑，JVM 单测覆盖）。文件体积上限见 `AttachmentLimits`。 */
object DocumentLimits {

    /**
     * 单次发送的文档字数上限（字符）：待发附件的 extractedChars 之和，
     * 超出则发送前直接拒绝（见 `AttachmentLimits.validate`）。
     * 注意这是**字数门**不是截断——门内有多少发多少，历史消息不计入。
     */
    const val MAX_DOCS_SEND_CHARS = 100_000L

    /** 单个工作表最多保留行数（内存安全网，真实文档基本碰不到）。 */
    const val MAX_SHEET_ROWS = 5000

    /** 单个工作簿最多解析工作表数（内存安全网）。 */
    const val MAX_SHEETS = 50

    /** 共享字符串表最多保留条数（超大词典防炸内存，超出的下标读成空串）。 */
    const val MAX_SHARED_STRINGS = 20000

    /** 单条共享字符串最多保留字符数（防单个巨型单元格吃内存）。 */
    const val MAX_SHARED_STRING_CHARS = 100_000

    /** zip 中央目录最多接受条目数（炸弹包门禁）。 */
    const val MAX_ZIP_PARTS = 2000

    /** 解压后总字节上限（炸弹包门禁）。 */
    const val MAX_ZIP_UNCOMPRESSED_BYTES = 64L * 1024 * 1024
}
