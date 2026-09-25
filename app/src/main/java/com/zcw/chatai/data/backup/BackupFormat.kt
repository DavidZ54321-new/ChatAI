package com.zcw.chatai.data.backup

import com.zcw.chatai.data.db.AttachmentCodec
import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.persona.PersonaConfigCodec
import com.zcw.chatai.data.persona.PersonaEntry
import com.zcw.chatai.data.prefs.ChatSettings
import com.zcw.chatai.data.prefs.ImageDetail
import com.zcw.chatai.data.prefs.ReasoningEffort
import com.zcw.chatai.data.prefs.ThemeFamily
import com.zcw.chatai.data.prefs.ThemeMode
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 备份包的格式定义与编解码（纯函数，JVM 单测覆盖）：整包是一个 zip。
 *
 * ```
 * manifest.json        # 格式标识 + 版本 + 统计 + 选项
 * settings.json        # 一份设置快照（providers 可脱敏）
 * conversations.json   # 会话数组
 * messages.json        # 消息数组
 * attachments/<conversationId>/<file>   # 附件二进制，与 filesDir 下的相对路径一致
 * ```
 *
 * 两条刻意的设计：
 * - **DTO 与 Room 实体同形**，`attachments` / `tool_calls` / `tool_result` 三个 JSON 列
 *   **按原始字符串透传**，不二次解析再序列化——未来版本往这些列里加字段，备份也带得走。
 * - 每个字段都有默认值 + `ignoreUnknownKeys`，所以「旧备份导入新版本」「新备份导入旧版本」
 *   都能降级工作，而不是整包读不出来。
 */
object BackupFormat {

    const val FORMAT = "chatai-backup"

    /** 格式版本：结构不兼容时自增。导入端只拒绝「高得看不懂」的版本时给出提示，不硬拦。 */
    const val VERSION = 1
}

/** 备份包清单。[includesApiKeys] = false 时 [SettingsDto] 里的密钥已被清空。 */
@Serializable
data class BackupManifest(
    val format: String = BackupFormat.FORMAT,
    val formatVersion: Int = BackupFormat.VERSION,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val exportedAt: Long = 0L,
    val includesAttachments: Boolean = false,
    val includesApiKeys: Boolean = false,
    val conversationCount: Int = 0,
    val messageCount: Int = 0,
    val attachmentCount: Int = 0,
    val attachmentBytes: Long = 0L,
)

@Serializable
data class ProviderDto(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val anthropicBaseUrl: String = "",
    val responsesBaseUrl: String = "",
) {
    fun toModel(): ProviderEntry = ProviderEntry(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        anthropicBaseUrl = anthropicBaseUrl,
        responsesBaseUrl = responsesBaseUrl,
    )

    companion object {
        fun of(entry: ProviderEntry, includeApiKey: Boolean): ProviderDto = ProviderDto(
            baseUrl = entry.baseUrl,
            apiKey = if (includeApiKey) entry.apiKey else "",
            model = entry.model,
            anthropicBaseUrl = entry.anthropicBaseUrl,
            responsesBaseUrl = entry.responsesBaseUrl,
        )
    }
}

@Serializable
data class PersonaDto(
    val name: String = "",
    val systemPrompt: String = "",
    val temperature: Double? = null,
    val reasoningEffort: String = ReasoningEffort.FOLLOW_DEFAULT.name,
    val maxTokens: Int? = null,
    val extraParams: String = "",
) {
    fun toModel(): PersonaEntry = PersonaEntry(
        name = name,
        systemPrompt = systemPrompt,
        temperature = temperature,
        reasoningEffort = ReasoningEffort.entries.firstOrNull { it.name == reasoningEffort }
            ?: ReasoningEffort.FOLLOW_DEFAULT,
        maxTokens = maxTokens,
        extraParams = extraParams,
    )

    companion object {
        fun of(entry: PersonaEntry): PersonaDto = PersonaDto(
            name = entry.name,
            systemPrompt = entry.systemPrompt,
            temperature = entry.temperature,
            reasoningEffort = entry.reasoningEffort.name,
            maxTokens = entry.maxTokens,
            extraParams = entry.extraParams,
        )
    }
}

@Serializable
data class SettingsDto(
    val providers: Map<String, ProviderDto> = emptyMap(),
    val activeProviderId: String = "",
    val searchProviderId: String? = null,
    val personas: Map<String, PersonaDto> = emptyMap(),
    val activePersonaId: String = "",
    val imageDetail: String = ImageDetail.FOLLOW_DEFAULT.name,
    val includeUsage: Boolean = true,
    val includeEnvTime: Boolean = true,
    val historyImageTurns: Int = ChatSettings.DEFAULT_HISTORY_IMAGE_TURNS,
    val imageSearchModelsRaw: String = "",
    val themeMode: String = ThemeMode.SYSTEM.name,
    val themeFamily: String = ThemeFamily.CLAUDE.name,
) {
    /** 转回设置模型；表意外为空时回落到默认值，绝不让导入把连接配置变成空的。 */
    fun toModel(): ChatSettings {
        val providers = providers.mapValues { it.value.toModel() }.ifEmpty { ChatSettings.Default.providers }
        val personas = personas.mapValues { it.value.toModel() }.ifEmpty { ChatSettings.Default.personas }
        return ChatSettings(
            providers = providers,
            activeProviderId = activeProviderId.takeIf { it in providers }
                ?: providers.keys.firstOrNull()
                ?: ProviderCatalog.DEEPSEEK,
            searchProviderId = searchProviderId?.takeIf { it.isNotBlank() },
            personas = personas,
            activePersonaId = PersonaConfigCodec.resolveActiveId(personas, activePersonaId),
            imageDetail = enumOrDefault(imageDetail, ImageDetail.FOLLOW_DEFAULT),
            includeUsage = includeUsage,
            includeEnvTime = includeEnvTime,
            historyImageTurns = historyImageTurns,
            imageSearchModelsRaw = imageSearchModelsRaw,
            themeMode = enumOrDefault(themeMode, ThemeMode.SYSTEM),
            themeFamily = enumOrDefault(themeFamily, ThemeFamily.CLAUDE),
        )
    }

    companion object {
        fun of(settings: ChatSettings, includeApiKeys: Boolean): SettingsDto = SettingsDto(
            providers = settings.providers.mapValues { ProviderDto.of(it.value, includeApiKeys) },
            activeProviderId = settings.activeProviderId,
            searchProviderId = settings.searchProviderId,
            personas = settings.personas.mapValues { PersonaDto.of(it.value) },
            activePersonaId = settings.activePersonaId,
            imageDetail = settings.imageDetail.name,
            includeUsage = settings.includeUsage,
            includeEnvTime = settings.includeEnvTime,
            historyImageTurns = settings.historyImageTurns,
            imageSearchModelsRaw = settings.imageSearchModelsRaw,
            themeMode = settings.themeMode.name,
            themeFamily = settings.themeFamily.name,
        )
    }
}

@Serializable
data class ConversationDto(
    val id: String = "",
    val title: String = "",
    val model: String = "",
    val systemPrompt: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastMessagePreview: String = "",
    val messageCount: Int = 0,
    val isPinned: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val providerId: String = "",
    val personaId: String = "",
    val parentConversationId: String = "",
) {
    fun toEntity(): ConversationEntity = ConversationEntity(
        id = id,
        title = title,
        model = model,
        systemPrompt = systemPrompt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessagePreview = lastMessagePreview,
        messageCount = messageCount,
        isPinned = isPinned,
        webSearchEnabled = webSearchEnabled,
        providerId = providerId,
        personaId = personaId,
        parentConversationId = parentConversationId,
    )

    companion object {
        fun of(entity: ConversationEntity): ConversationDto = ConversationDto(
            id = entity.id,
            title = entity.title,
            model = entity.model,
            systemPrompt = entity.systemPrompt,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            lastMessagePreview = entity.lastMessagePreview,
            messageCount = entity.messageCount,
            isPinned = entity.isPinned,
            webSearchEnabled = entity.webSearchEnabled,
            providerId = entity.providerId,
            personaId = entity.personaId,
            parentConversationId = entity.parentConversationId,
        )
    }
}

@Serializable
data class MessageDto(
    val id: String = "",
    val conversationId: String = "",
    val role: String = "USER",
    val content: String = "",
    val status: String = "COMPLETE",
    val errorMessage: String? = null,
    val reasoningContent: String? = null,
    val seq: Long = 0L,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val reasoningMs: Long? = null,
    val attachments: String? = null,
    val toolCalls: String? = null,
    val toolCallId: String? = null,
    val toolResult: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 角色/状态缺失（外部或旧版备份）时补一个合法值；未知值交给 `Mappers` 的兜底。 */
    fun toEntity(): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.ifBlank { "USER" },
        content = content,
        status = status.ifBlank { "COMPLETE" },
        errorMessage = errorMessage,
        reasoningContent = reasoningContent,
        seq = seq,
        model = model,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
        reasoningMs = reasoningMs,
        attachments = attachments,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        toolResult = toolResult,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun of(entity: MessageEntity): MessageDto = MessageDto(
            id = entity.id,
            conversationId = entity.conversationId,
            role = entity.role,
            content = entity.content,
            status = entity.status,
            errorMessage = entity.errorMessage,
            reasoningContent = entity.reasoningContent,
            seq = entity.seq,
            model = entity.model,
            promptTokens = entity.promptTokens,
            completionTokens = entity.completionTokens,
            reasoningTokens = entity.reasoningTokens,
            cachedTokens = entity.cachedTokens,
            reasoningMs = entity.reasoningMs,
            attachments = entity.attachments,
            toolCalls = entity.toolCalls,
            toolCallId = entity.toolCallId,
            toolResult = entity.toolResult,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }
}

object BackupCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val conversationList = ListSerializer(ConversationDto.serializer())
    private val messageList = ListSerializer(MessageDto.serializer())
    private val providerMap = MapSerializer(String.serializer(), ProviderDto.serializer())
    private val personaMap = MapSerializer(String.serializer(), PersonaDto.serializer())

    // ---------- 写 ----------

    fun encodeManifest(manifest: BackupManifest): String =
        json.encodeToString(BackupManifest.serializer(), manifest)

    fun encodeConversations(conversations: List<ConversationEntity>): String =
        json.encodeToString(conversationList, conversations.map { ConversationDto.of(it) })

    fun encodeMessages(messages: List<MessageEntity>): String =
        json.encodeToString(messageList, messages.map { MessageDto.of(it) })

    fun encodeSettings(settings: ChatSettings, includeApiKeys: Boolean): String =
        json.encodeToString(SettingsDto.serializer(), SettingsDto.of(settings, includeApiKeys))

    // ---------- 读（非法一律返回 null，由调用方给出人话） ----------

    /** 解析清单；格式标识不符返回 null（不是本应用的备份）。 */
    fun decodeManifest(raw: String?): BackupManifest? {
        if (raw.isNullOrBlank()) return null
        val manifest = runCatching {
            json.decodeFromString(BackupManifest.serializer(), raw)
        }.getOrNull() ?: return null
        return manifest.takeIf { it.format == BackupFormat.FORMAT }
    }

    /** 解析会话表；丢掉 id 为空的行（损坏条目不该让整包失败）。 */
    fun decodeConversations(raw: String?): List<ConversationEntity>? =
        decode(raw, conversationList)
            ?.map { it.toEntity() }
            ?.filter { it.id.isNotBlank() }

    /** 解析消息表；丢掉 id 或 conversationId 为空的行。 */
    fun decodeMessages(raw: String?): List<MessageEntity>? =
        decode(raw, messageList)
            ?.map { it.toEntity() }
            ?.filter { it.id.isNotBlank() && it.conversationId.isNotBlank() }

    /** 解析设置快照；缺失或非法返回 null（调用方保留本机设置，而不是悄悄重置成默认值）。 */
    fun decodeSettings(raw: String?): ChatSettings? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(SettingsDto.serializer(), raw).toModel()
        }.getOrNull()
    }

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (raw == null) return null
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }

    // ---------- 派生 / 修正 ----------

    data class PruneResult(val attachments: String?, val dropped: Int)

    /**
     * 只保留 [exists] 认可的附件（备份可能不含附件，或含部分附件）。
     * 一条都不丢时**原样返回原始 JSON**，不做解码-再编码的往返。
     */
    fun pruneMissingAttachments(
        raw: String?,
        exists: (Attachment) -> Boolean,
    ): PruneResult {
        val decoded = AttachmentCodec.decode(raw)
        if (decoded.isEmpty()) return PruneResult(raw, 0)
        val kept = decoded.filter(exists)
        if (kept.size == decoded.size) return PruneResult(raw, 0)
        return PruneResult(AttachmentCodec.encode(kept), decoded.size - kept.size)
    }

    /**
     * 用备份里的值补齐设置。
     *
     * - **供应商**：同 id 做字段级合并——本机已填的值胜出，本机为**空**的字段用备份补上。
     *   不能整条「本机优先」：全新安装的预设条目 apiKey 是空占位，那样会把备份里的可用密钥丢掉。
     * - **角色**：同 id 保留本机条目（角色的空提示词、空温度是用户的有意取值，不是占位）。
     * - 全局标量（主题、图片精度、激活供应商…）保持本机不变。
     */
    fun mergeSettings(local: ChatSettings, imported: ChatSettings): ChatSettings = local.copy(
        providers = local.providers + imported.providers.mapValues { (id, incoming) ->
            val mine = local.providers[id] ?: return@mapValues incoming
            // 用 copy 而不是逐字段构造：以后给 ProviderEntry 加字段时，不会在这里静默丢成默认值。
            mine.copy(
                baseUrl = mine.baseUrl.ifBlank { incoming.baseUrl },
                apiKey = mine.apiKey.ifBlank { incoming.apiKey },
                model = mine.model.ifBlank { incoming.model },
                anthropicBaseUrl = mine.anthropicBaseUrl.ifBlank { incoming.anthropicBaseUrl },
                responsesBaseUrl = mine.responsesBaseUrl.ifBlank { incoming.responsesBaseUrl },
            )
        },
        personas = imported.personas.filterKeys { it !in local.personas } + local.personas,
    )
}

/** 枚举名非法/缺失时回落默认值（备份可能来自别的版本）。 */
private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

/** 备份包内的路径规则（纯函数，JVM 单测覆盖）。 */
object BackupPaths {

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val CONVERSATIONS = "conversations.json"
    const val MESSAGES = "messages.json"

    /** 附件目录前缀：与 `AttachmentStore.DIR` 一致（包里就是 filesDir 下的相对路径）。 */
    const val ATTACHMENTS_PREFIX = "attachments/"

    private val FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** `chatai-backup-20260924-1530.zip`；[zone] 只为单测可复现。 */
    fun suggestedFileName(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = Instant.ofEpochMilli(timestampMs).atZone(zone).format(FILE_NAME_FORMAT)
        return "chatai-backup-$stamp.zip"
    }

    /**
     * 条目名是否安全可解压：拒绝绝对路径、`..` 穿越与反斜杠（zip-slip）。
     * 备份是用户从外部选进来的文件，不能假定它善意。
     */
    fun isSafeEntry(name: String): Boolean =
        name.isNotBlank() &&
            !name.startsWith("/") &&
            !name.startsWith("\\") &&
            !name.contains("..") &&
            !name.contains('\\')

    fun isAttachmentEntry(name: String): Boolean =
        name.startsWith(ATTACHMENTS_PREFIX) && name.length > ATTACHMENTS_PREFIX.length

    /**
     * 把附件列里写死的会话目录换成新会话目录（合并导入时用）。
     * 会话 id 是 UUID，只会出现在这个目录段里，所以字符串替换是安全的；
     * 这样能整列原样透传，不必解码再编码（不丢未来版本的字段）。
     */
    fun rewriteConversationDir(raw: String?, oldConversationId: String, newConversationId: String): String? {
        if (raw.isNullOrBlank()) return raw
        if (oldConversationId.isBlank() || oldConversationId == newConversationId) return raw
        return raw.replace(
            "$ATTACHMENTS_PREFIX$oldConversationId/",
            "$ATTACHMENTS_PREFIX$newConversationId/",
        )
    }

    /** 附件相对路径换到新会话目录（同一文件名），供物理搬运时定位目标。 */
    fun inConversationDir(relativePath: String, newConversationId: String): String {
        val name = relativePath.substringAfterLast('/')
        return "$ATTACHMENTS_PREFIX$newConversationId/$name"
    }
}

/**
 * 合并导入时的 id 重分配（纯函数，JVM 单测覆盖）：
 * 换掉会话与消息的 id（避免撞上本机数据）、把消息指向新的会话、重写附件列里的会话目录。
 */
object BackupMerge {

    data class Remapped(
        val conversations: List<ConversationEntity>,
        val messages: List<MessageEntity>,
        /** 旧会话 id → 新会话 id，供调用方重命名附件目录。 */
        val conversationIds: Map<String, String>,
    )

    fun remap(
        conversations: List<ConversationEntity>,
        messages: List<MessageEntity>,
        newId: () -> String,
    ): Remapped {
        val idMap = conversations.associate { it.id to newId() }
        val remappedConversations = conversations.map { conversation ->
            conversation.copy(
                id = idMap.getValue(conversation.id),
                // 父会话也在这一份备份里才保留分支关系，否则独立成根。
                parentConversationId = idMap[conversation.parentConversationId].orEmpty(),
            )
        }
        val remappedMessages = messages
            .filter { it.conversationId in idMap }
            .map { message ->
                val newConversationId = idMap.getValue(message.conversationId)
                message.copy(
                    id = newId(),
                    conversationId = newConversationId,
                    attachments = BackupPaths.rewriteConversationDir(
                        message.attachments,
                        message.conversationId,
                        newConversationId,
                    ),
                )
            }
        return Remapped(remappedConversations, remappedMessages, idMap)
    }
}
