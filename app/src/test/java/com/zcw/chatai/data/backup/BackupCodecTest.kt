package com.zcw.chatai.data.backup

import com.zcw.chatai.data.db.AttachmentCodec
import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.persona.PersonaConfigCodec
import com.zcw.chatai.data.persona.PersonaEntry
import com.zcw.chatai.data.prefs.ChatSettings
import com.zcw.chatai.data.prefs.ImageDetail
import com.zcw.chatai.data.prefs.ReasoningEffort
import com.zcw.chatai.data.prefs.ThemeFamily
import com.zcw.chatai.data.prefs.ThemeMode
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val conversation = ConversationEntity(
        id = "c1",
        title = "示例会话",
        model = "deepseek-flash",
        systemPrompt = null,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        lastMessagePreview = "预览",
        messageCount = 3,
        isPinned = true,
        webSearchEnabled = true,
        providerId = "qwen",
        personaId = "translator",
        parentConversationId = "c0",
    )

    /** 未知字段（未来版本写的）+ 原始 JSON 列，都要原样带得走。 */
    private val message = MessageEntity(
        id = "m1",
        conversationId = "c1",
        role = "ASSISTANT",
        content = "正文",
        status = "COMPLETE",
        errorMessage = null,
        reasoningContent = "思考",
        seq = 2L,
        model = "deepseek-flash",
        promptTokens = 11,
        completionTokens = 22,
        reasoningTokens = 33,
        cachedTokens = 44,
        reasoningMs = 5_500L,
        attachments = """[{"id":"a1","path":"attachments/c1/a1.jpg","kind":"IMAGE","futureField":{"x":1}}]""",
        toolCalls = """[{"id":"call_1","name":"web_search","arguments":"{}"}]""",
        toolCallId = null,
        toolResult = null,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
    )

    private val settings = ChatSettings.Default.copy(
        providers = mapOf(
            ProviderCatalog.DEEPSEEK to ProviderEntry(
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-secret",
                model = "deepseek-flash",
            ),
            ProviderCatalog.QWEN to ProviderEntry(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "sk-qwen",
                model = "qwen3.8-max",
                anthropicBaseUrl = "https://custom/anthropic",
            ),
        ),
        activeProviderId = ProviderCatalog.QWEN,
        searchProviderId = ProviderCatalog.DEEPSEEK,
        personas = mapOf(
            PersonaConfigCodec.DEFAULT_ID to PersonaEntry(name = "默认"),
            "translator" to PersonaEntry(
                name = "翻译官",
                systemPrompt = "只翻译",
                temperature = 0.2,
                reasoningEffort = ReasoningEffort.OFF,
                maxTokens = 1024,
                extraParams = """{"top_k":20}""",
            ),
        ),
        activePersonaId = "translator",
        imageDetail = ImageDetail.LOW,
        includeUsage = false,
        includeEnvTime = false,
        historyImageTurns = 3,
        imageSearchModelsRaw = "qwen3.8-27b,qwen3.8-max",
        themeMode = ThemeMode.DARK,
        themeFamily = ThemeFamily.CHATGPT,
    )

    private fun attachmentJson(vararg ids: String): String = AttachmentCodec.encode(
        ids.map { id ->
            Attachment(
                id = id,
                kind = AttachmentKind.IMAGE,
                relativePath = "attachments/c1/$id.jpg",
                mimeType = "image/jpeg",
                width = 10,
                height = 10,
                sizeBytes = 100,
            )
        },
    ).orEmpty()

    @Test
    fun manifestRoundTripIsStable() {
        val manifest = BackupManifest(
            appVersionName = "1.0",
            appVersionCode = 1,
            exportedAt = 123L,
            includesAttachments = true,
            includesApiKeys = false,
            conversationCount = 2,
            messageCount = 5,
            attachmentCount = 3,
            attachmentBytes = 4096L,
        )
        assertEquals(manifest, BackupCodec.decodeManifest(BackupCodec.encodeManifest(manifest)))
    }

    @Test
    fun decodeManifestRejectsForeignOrBrokenArchives() {
        assertNull(BackupCodec.decodeManifest(null))
        assertNull(BackupCodec.decodeManifest(""))
        assertNull(BackupCodec.decodeManifest("not json"))
        assertNull(BackupCodec.decodeManifest("""{"format":"other-app"}"""))
    }

    @Test
    fun conversationRoundTripKeepsEveryField() {
        val restored = BackupCodec.decodeConversations(
            BackupCodec.encodeConversations(listOf(conversation)),
        )
        assertEquals(listOf(conversation), restored)
        assertEquals("c0", restored!!.single().parentConversationId)
    }

    /** 关键：原始 JSON 列按字符串透传，不做解码-再编码（未来版本的字段不会丢）。 */
    @Test
    fun rawJsonColumnsArePassedThroughVerbatim() {
        val restored = BackupCodec.decodeMessages(
            BackupCodec.encodeMessages(listOf(message)),
        )!!.single()
        assertEquals(message.attachments, restored.attachments)
        assertEquals(message.toolCalls, restored.toolCalls)
        assertEquals(message, restored)
    }

    @Test
    fun unknownKeysAreIgnoredAndMissingFieldsFallBackToDefaults() {
        val decoded = BackupCodec.decodeConversations(
            """[{"id":"c9","title":"只有一个 id","futureField":123}]""",
        )!!
        assertEquals(1, decoded.size)
        assertEquals("c9", decoded.single().id)
        assertEquals("只有一个 id", decoded.single().title)
        assertEquals("", decoded.single().model)
        assertEquals("", decoded.single().parentConversationId)
    }

    @Test
    fun malformedJsonReturnsNullInsteadOfThrowing() {
        assertNull(BackupCodec.decodeConversations("{"))
        assertNull(BackupCodec.decodeMessages("[{\"id\":"))
        assertNull(BackupCodec.decodeMessages(null))
    }

    @Test
    fun conversationsWithoutIdAndMessagesWithoutConversationAreDropped() {
        val conversations = BackupCodec.decodeConversations("""[{"id":""},{"id":"c1"}]""")
        assertEquals(listOf("c1"), conversations!!.map { it.id })

        val messages = BackupCodec.decodeMessages(
            """[{"id":"m1","conversationId":""},{"id":"","conversationId":"c1"},{"id":"m2","conversationId":"c1"}]""",
        )
        assertEquals(listOf("m2"), messages!!.map { it.id })
    }

    @Test
    fun blankRoleAndStatusGetUsableDefaults() {
        val decoded = BackupCodec.decodeMessages(
            """[{"id":"m1","conversationId":"c1","role":"","status":""}]""",
        )!!
        assertEquals("USER", decoded.single().role)
        assertEquals("COMPLETE", decoded.single().status)
    }

    @Test
    fun settingsRoundTripKeepsEverythingIncludingTheme() {
        val restored = BackupCodec.decodeSettings(BackupCodec.encodeSettings(settings, true))
        assertEquals(settings, restored)
    }

    @Test
    fun encodingWithoutApiKeysBlanksEveryKey() {
        val restored = BackupCodec.decodeSettings(BackupCodec.encodeSettings(settings, false))!!
        assertTrue(restored.providers.values.all { it.apiKey.isEmpty() })
        // 其余字段一个不少。
        assertEquals(settings.providers.keys, restored.providers.keys)
        assertEquals("qwen3.8-max", restored.providers.getValue(ProviderCatalog.QWEN).model)
        assertEquals("https://custom/anthropic", restored.providers.getValue(ProviderCatalog.QWEN).anthropicBaseUrl)
    }

    @Test
    fun decodeSettingsReturnsNullWhenMissingOrBroken() {
        assertNull(BackupCodec.decodeSettings(null))
        assertNull(BackupCodec.decodeSettings("{"))
    }

    @Test
    fun settingsWithoutProvidersFallBackToDefaultsInsteadOfEmpty() {
        val restored = BackupCodec.decodeSettings("""{"activeProviderId":"ghost","providers":{},"personas":{}}""")!!
        assertEquals(ChatSettings.Default.providers.keys, restored.providers.keys)
        assertEquals(ChatSettings.Default.personas.keys, restored.personas.keys)
        assertEquals(restored.providers.keys.first(), restored.activeProviderId)
    }

    @Test
    fun pruneKeepsRawJsonWhenNothingIsMissing() {
        val raw = attachmentJson("a1", "a2")
        val result = BackupCodec.pruneMissingAttachments(raw) { true }
        assertEquals(raw, result.attachments)
        assertEquals(0, result.dropped)
    }

    @Test
    fun pruneDropsOnlyTheMissingOnesAndCountsThem() {
        val result = BackupCodec.pruneMissingAttachments(attachmentJson("a1", "a2", "a3")) {
            it.id != "a2"
        }
        assertEquals(1, result.dropped)
        assertEquals(listOf("a1", "a3"), AttachmentCodec.decode(result.attachments).map { it.id })
    }

    @Test
    fun pruneReturnsNullWhenNothingIsLeft() {
        val result = BackupCodec.pruneMissingAttachments(attachmentJson("a1")) { false }
        assertEquals(1, result.dropped)
        assertNull(result.attachments)
    }

    @Test
    fun mergeSettingsOnlyFillsMissingIdsAndKeepsLocalScalars() {
        val local = ChatSettings.Default.copy(
            providers = mapOf(
                ProviderCatalog.DEEPSEEK to ProviderEntry(baseUrl = "local", apiKey = "local-key", model = "local-model"),
            ),
            personas = mapOf(PersonaConfigCodec.DEFAULT_ID to PersonaEntry(name = "本机默认")),
            themeMode = ThemeMode.LIGHT,
        )
        val merged = BackupCodec.mergeSettings(local, settings)
        // 本机已有的 id 不被覆盖（密钥不能因为导入一份旧备份就被清成空）。
        assertEquals("local-key", merged.providers.getValue(ProviderCatalog.DEEPSEEK).apiKey)
        assertEquals("本机默认", merged.personas.getValue(PersonaConfigCodec.DEFAULT_ID).name)
        // 备份里有、本机没有的条目补进来。
        assertTrue(ProviderCatalog.QWEN in merged.providers)
        assertTrue("translator" in merged.personas)
        // 全局标量保持本机不变。
        assertEquals(ThemeMode.LIGHT, merged.themeMode)
        assertEquals(ChatSettings.Default.activeProviderId, merged.activeProviderId)
    }

    /**
     * 回归：全新安装时预设条目是**空 key 占位**，整条「本机优先」会把备份里的可用密钥丢掉，
     * 合并导入后应用直接不可用且毫无提示。
     */
    @Test
    fun mergeSettingsFillsBlankLocalProviderFieldsFromTheArchive() {
        val freshInstall = ChatSettings.Default
        assertEquals("", freshInstall.providers.getValue(ProviderCatalog.DEEPSEEK).apiKey)

        val merged = BackupCodec.mergeSettings(freshInstall, settings)
        val deepseek = merged.providers.getValue(ProviderCatalog.DEEPSEEK)
        assertEquals("sk-secret", deepseek.apiKey)
        // 本机非空的字段仍然胜出（全新安装的 baseUrl/model 是预设默认值，非空）。
        assertEquals(ChatSettings.DEFAULT_BASE_URL, deepseek.baseUrl)
        assertEquals(ChatSettings.DEFAULT_MODEL, deepseek.model)
    }
}
