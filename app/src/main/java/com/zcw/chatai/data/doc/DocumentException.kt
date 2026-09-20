package com.zcw.chatai.data.doc

/** 文档解析失败：message 直接面向用户（调用方原样展示，不再包装）。 */
class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)
