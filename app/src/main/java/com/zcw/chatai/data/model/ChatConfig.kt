package com.zcw.chatai.data.model

data class ChatConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val temperature: Double?,
)
