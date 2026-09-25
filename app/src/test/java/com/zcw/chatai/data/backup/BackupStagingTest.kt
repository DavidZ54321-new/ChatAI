package com.zcw.chatai.data.backup

import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
import com.zcw.chatai.data.prefs.ChatSettings
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `BackupStaging.stage` 的失败路径就是这个功能的要害：`conversations.json` / `messages.json`
 * 缺失或损坏时如果放过去，覆盖式还原会「清空本机 → 还原成 0 条 → 报成功」。
 */
class BackupStagingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val conversation = ConversationEntity(
        id = "c1",
        title = "会话",
        model = "deepseek-flash",
        systemPrompt = null,
        createdAt = 1,
        updatedAt = 2,
        lastMessagePreview = "",
        messageCount = 1,
        isPinned = false,
    )

    private val message = MessageEntity(
        id = "m1",
        conversationId = "c1",
        role = "USER",
        content = "你好",
        status = "COMPLETE",
        errorMessage = null,
        reasoningContent = null,
        seq = 1,
        model = null,
        promptTokens = null,
        completionTokens = null,
        createdAt = 1,
        updatedAt = 2,
    )

    private val attachmentBody = byteArrayOf(1, 2, 3, 4, 5)

    private fun manifest(attachmentBytes: Long) = BackupCodec.encodeManifest(
        BackupManifest(
            exportedAt = 42L,
            includesAttachments = true,
            includesApiKeys = true,
            conversationCount = 1,
            messageCount = 1,
            attachmentCount = 1,
            attachmentBytes = attachmentBytes,
        ),
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ZipInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return ZipInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun text(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun validArchive(vararg extra: Pair<String, ByteArray>): ZipInputStream = zipOf(
        BackupPaths.MANIFEST to text(manifest(attachmentBody.size.toLong())),
        BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
        BackupPaths.MESSAGES to text(BackupCodec.encodeMessages(listOf(message))),
        "attachments/c1/a1.jpg" to attachmentBody,
        *extra,
    )

    private fun stagingRoot(): File = File(temp.newFolder(), "backup-staging")

    private fun assertStagingFails(
        zip: ZipInputStream,
        expectedFragment: String,
        jsonEntryMaxBytes: Long = JSON_ENTRY_MAX_BYTES,
        attachmentSlackBytes: Long = 64L * 1024 * 1024,
    ) {
        try {
            BackupStaging.stage(
                zip = zip,
                stagingRoot = stagingRoot(),
                jsonEntryMaxBytes = jsonEntryMaxBytes,
                attachmentSlackBytes = attachmentSlackBytes,
            )
            fail("应当失败：$expectedFragment")
        } catch (t: IOException) {
            val message = t.message.orEmpty()
            assertTrue("报错文案不含「$expectedFragment」：$message", message.contains(expectedFragment))
        }
    }

    @Test
    fun stagesValidArchiveAndWritesAttachments() {
        val root = stagingRoot()
        val archivedSettings = ChatSettings.Default.copy(
            providers = ChatSettings.Default.providers + (
                ProviderCatalog.QWEN to ProviderEntry(
                    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    apiKey = "sk-qwen",
                    model = "qwen3.8-max",
                )
                ),
            activeProviderId = ProviderCatalog.QWEN,
        )
        val staged = zipOf(
            BackupPaths.MANIFEST to text(manifest(attachmentBody.size.toLong())),
            BackupPaths.SETTINGS to text(BackupCodec.encodeSettings(archivedSettings, includeApiKeys = true)),
            BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
            BackupPaths.MESSAGES to text(BackupCodec.encodeMessages(listOf(message))),
            "attachments/c1/a1.jpg" to attachmentBody,
            // 未来版本新增的条目：忽略即可，不该让整包失败。
            "notes/future.bin" to text("whatever"),
        ).use { BackupStaging.stage(it, root) }

        assertEquals(listOf(conversation), staged.conversations)
        assertEquals(listOf(message), staged.messages)
        assertEquals(1, staged.attachmentCount)
        assertEquals(attachmentBody.size.toLong(), staged.attachmentBytes)
        assertEquals(ProviderCatalog.QWEN, staged.settings?.activeProviderId)
        assertEquals("sk-qwen", staged.settings?.providers?.get(ProviderCatalog.QWEN)?.apiKey)
        assertTrue(staged.manifest.includesAttachments)
        val written = File(root, "attachments/c1/a1.jpg")
        assertTrue("附件没有落盘", written.isFile)
        assertEquals(attachmentBody.toList(), written.readBytes().toList())
    }

    @Test
    fun settingsAreOptional() {
        val staged = validArchive().use { BackupStaging.stage(it, stagingRoot()) }
        assertNull(staged.settings)
        assertNotNull(staged.manifest)
    }

    @Test
    fun missingMessagesJsonFailsInsteadOfRestoringZeroMessages() {
        val zip = zipOf(
            BackupPaths.MANIFEST to text(manifest(0)),
            BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
        )
        assertStagingFails(zip, "messages.json")
    }

    @Test
    fun corruptMessagesJsonFails() {
        val zip = zipOf(
            BackupPaths.MANIFEST to text(manifest(0)),
            BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
            BackupPaths.MESSAGES to text("{\"not\":\"an array\""),
        )
        assertStagingFails(zip, "messages.json")
    }

    @Test
    fun missingOrCorruptConversationsJsonFails() {
        assertStagingFails(
            zipOf(
                BackupPaths.MANIFEST to text(manifest(0)),
                BackupPaths.MESSAGES to text(BackupCodec.encodeMessages(listOf(message))),
            ),
            "conversations.json",
        )
        assertStagingFails(
            zipOf(
                BackupPaths.MANIFEST to text(manifest(0)),
                BackupPaths.CONVERSATIONS to text("nonsense"),
                BackupPaths.MESSAGES to text(BackupCodec.encodeMessages(listOf(message))),
            ),
            "conversations.json",
        )
    }

    @Test
    fun foreignArchiveIsRejected() {
        assertStagingFails(zipOf("manifest.json" to text("""{"format":"other"}""")), "manifest.json")
        assertStagingFails(zipOf("messages.json" to text("[]")), "manifest.json")
    }

    @Test
    fun pathTraversalEntryIsRejected() {
        val zip = zipOf(
            BackupPaths.MANIFEST to text(manifest(0)),
            BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
            BackupPaths.MESSAGES to text(BackupCodec.encodeMessages(listOf(message))),
            "../evil.jpg" to text("x"),
        )
        assertStagingFails(zip, "不安全的路径")
    }

    @Test
    fun attachmentBeforeManifestIsRejected() {
        val zip = zipOf(
            "attachments/c1/a1.jpg" to attachmentBody,
            BackupPaths.MANIFEST to text(manifest(attachmentBody.size.toLong())),
            BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
            BackupPaths.MESSAGES to text(BackupCodec.encodeMessages(listOf(message))),
        )
        assertStagingFails(zip, "manifest.json 之前")
    }

    @Test
    fun attachmentBytesBeyondDeclaredSizeAreRejected() {
        // 清单声明 5 字节，实际给 100 字节，余量只给 10：典型的体积谎报/炸弹。
        val zip = zipOf(
            BackupPaths.MANIFEST to text(manifest(attachmentBody.size.toLong())),
            BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
            BackupPaths.MESSAGES to text(BackupCodec.encodeMessages(listOf(message))),
            "attachments/c1/a1.jpg" to ByteArray(100),
        )
        assertStagingFails(zip, "超出它声明的体积", attachmentSlackBytes = 10)
    }

    @Test
    fun oversizedJsonEntryIsRejected() {
        val zip = zipOf(
            BackupPaths.MANIFEST to text(manifest(0)),
            BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
            BackupPaths.MESSAGES to text(BackupCodec.encodeMessages(listOf(message))),
        )
        assertStagingFails(zip, "单个条目过大", jsonEntryMaxBytes = 16)
    }

    @Test
    fun stagingRootIsCreatedOnDemand() {
        val root = File(temp.newFolder(), "nested/deeper/staging")
        val staged = validArchive().use { BackupStaging.stage(it, root) }
        assertTrue(File(root, "attachments/c1/a1.jpg").isFile)
        assertEquals(1, staged.attachmentCount)
    }

    /**
     * `[]` 是**合法 JSON 但什么都没有**：解析得出来，所以光看「能不能解析」拦不住它，
     * 覆盖式还原会清空本机再还原 0 条还报成功。清单里本来就写着条数，对一下就能识别。
     */
    @Test
    fun emptyButParseableConversationsJsonIsRejectedWhenManifestDeclaresRows() {
        val zip = zipOf(
            BackupPaths.MANIFEST to text(manifest(0)),
            BackupPaths.CONVERSATIONS to text("[]"),
            BackupPaths.MESSAGES to text("[]"),
        )
        assertStagingFails(zip, "conversations.json")
    }

    @Test
    fun emptyButParseableMessagesJsonIsRejected() {
        val zip = zipOf(
            BackupPaths.MANIFEST to text(manifest(0)),
            BackupPaths.CONVERSATIONS to text(BackupCodec.encodeConversations(listOf(conversation))),
            BackupPaths.MESSAGES to text("[]"),
        )
        assertStagingFails(zip, "messages.json")
    }

    /** 真的空备份（清单也声明 0 条）仍然合法：用户可能就是想把本机清成空的。 */
    @Test
    fun genuinelyEmptyArchiveIsAccepted() {
        val zip = zipOf(
            BackupPaths.MANIFEST to text(
                BackupCodec.encodeManifest(BackupManifest(conversationCount = 0, messageCount = 0)),
            ),
            BackupPaths.CONVERSATIONS to text("[]"),
            BackupPaths.MESSAGES to text("[]"),
        )
        val staged = zip.use { BackupStaging.stage(it, stagingRoot()) }
        assertTrue(staged.conversations.isEmpty())
        assertTrue(staged.messages.isEmpty())
    }
}
