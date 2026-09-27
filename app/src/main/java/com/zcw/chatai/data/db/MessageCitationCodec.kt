package com.zcw.chatai.data.db

import com.zcw.chatai.data.model.MessageCitation
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object MessageCitationCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(MessageCitationDto.serializer())

    fun encode(citations: List<MessageCitation>): String? =
        citations.takeIf { it.isNotEmpty() }
            ?.let { json.encodeToString(serializer, it.map { citation -> citation.toDto() }) }

    fun decode(raw: String?): List<MessageCitation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw).mapNotNull { it.toModel() } }
            .getOrDefault(emptyList())
    }

    private fun MessageCitation.toDto() = MessageCitationDto(startIndex, endIndex, url, title)

    private fun MessageCitationDto.toModel(): MessageCitation? {
        val scheme = url.substringBefore(':', "").lowercase()
        return if (startIndex < 0 || endIndex < startIndex || scheme !in setOf("http", "https")) null
        else MessageCitation(startIndex, endIndex, url, title?.takeIf { it.isNotBlank() })
    }
}

@Serializable
private data class MessageCitationDto(
    val startIndex: Int,
    val endIndex: Int,
    val url: String,
    val title: String? = null,
)
