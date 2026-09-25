package com.zcw.chatai.data.backup

import com.zcw.chatai.data.db.ConversationEntity
import com.zcw.chatai.data.db.MessageEntity
import com.zcw.chatai.data.prefs.ChatSettings
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** 我们的错误文案是给人看的；其余异常由调用方给兜底话术。 */
internal class BackupException(message: String) : IOException(message)

/** 单个 JSON 条目的读入上限：防一个畸形条目把内存吃光。 */
internal const val JSON_ENTRY_MAX_BYTES = 128L * 1024 * 1024

private const val BUFFER_BYTES = 64 * 1024

/**
 * 读当前 zip 条目的全部内容。`ZipInputStream.read` 在**条目末尾**就返回 -1，
 * 所以这里只会读到一个条目，不会把整包读进来（`inspect` 靠这一点只读开头的清单）。
 * 超过 [maxBytes] 直接判为损坏的备份。
 */
internal fun ZipInputStream.readEntryText(maxBytes: Long = JSON_ENTRY_MAX_BYTES): String {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (out.size() + read > maxBytes) {
            throw BackupException("备份里的单个条目过大，已中止（备份可能已损坏）")
        }
        out.write(buffer, 0, read)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/** 流式复制当前条目，但**边写边卡上限**：超了立刻中止，不让一条超大条目把磁盘写满。 */
private fun ZipInputStream.copyBounded(out: java.io.OutputStream, maxBytes: Long): Long {
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw BackupException("备份展开后超出它声明的体积，已中止（备份可能已损坏）")
        }
        out.write(buffer, 0, read)
    }
    return total
}

/**
 * 备份包的解压与校验（纯 `java.io`，JVM 单测覆盖）。
 *
 * 抽出成独立对象有两个理由：
 * - 校验必须在这里做完——`conversations.json` / `messages.json` 缺失或损坏时**宁可失败**，
 *   也不能「还原成 0 条会话 / 0 条消息」还报成功（覆盖式还原已经清掉本机数据了）；
 * - 它不碰 Context / Room，所以这些失败路径能直接用临时目录做 JVM 测试。
 */
internal object BackupStaging {

    /** 附件解压总量相对清单声明值的允许余量（本格式的写方总是两者精确相等）。 */
    private const val ATTACHMENT_SLACK_BYTES = 64L * 1024 * 1024

    /** 解压 + 校验之后的整包内容。 */
    data class Staged(
        val manifest: BackupManifest,
        val settings: ChatSettings?,
        val conversations: List<ConversationEntity>,
        val messages: List<MessageEntity>,
        val attachmentCount: Int,
        val attachmentBytes: Long,
    )

    /**
     * 单趟解压：JSON 条目读进内存，附件条目落进 [stagingRoot]（相对路径与 `filesDir` 一致）。
     * 任何一步不合法都抛 [BackupException]。
     */
    fun stage(
        zip: ZipInputStream,
        stagingRoot: File,
        jsonEntryMaxBytes: Long = JSON_ENTRY_MAX_BYTES,
        attachmentSlackBytes: Long = ATTACHMENT_SLACK_BYTES,
    ): Staged {
        var manifest: BackupManifest? = null
        var settingsRaw: String? = null
        var conversationsRaw: String? = null
        var messagesRaw: String? = null
        var attachmentCount = 0
        var attachmentBytes = 0L

        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            if (!BackupPaths.isSafeEntry(name)) {
                throw BackupException("备份包含不安全的路径：$name")
            }
            if (!entry.isDirectory) {
                when {
                    name == BackupPaths.MANIFEST ->
                        manifest = BackupCodec.decodeManifest(zip.readEntryText(jsonEntryMaxBytes))

                    name == BackupPaths.SETTINGS -> settingsRaw = zip.readEntryText(jsonEntryMaxBytes)

                    name == BackupPaths.CONVERSATIONS ->
                        conversationsRaw = zip.readEntryText(jsonEntryMaxBytes)

                    name == BackupPaths.MESSAGES -> messagesRaw = zip.readEntryText(jsonEntryMaxBytes)

                    BackupPaths.isAttachmentEntry(name) -> {
                        // 清单必须先于附件：解压总量要靠它约束（也是本格式既定的写入顺序）。
                        val declared = manifest
                            ?: throw BackupException("备份格式不合法：附件出现在 manifest.json 之前")
                        val target = File(stagingRoot, name)
                        target.parentFile?.mkdirs()
                        // 边写边卡上限：不能等整条解压完再检查，否则一条超大条目足以先把磁盘写满。
                        val remaining = declared.attachmentBytes + attachmentSlackBytes - attachmentBytes
                        attachmentBytes += target.outputStream().use { out -> zip.copyBounded(out, remaining) }
                        attachmentCount++
                    }

                    // 未来版本新增的条目：忽略，不因此让整包失败。
                    else -> Unit
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }

        val parsedManifest = manifest
            ?: throw BackupException("这不是 ChatAI 的备份文件（缺少 manifest.json）")
        // 会话与消息都是必备条目：缺失/损坏时失败，好过「成功还原了 0 条消息」。
        val conversations = BackupCodec.decodeConversations(conversationsRaw)
            ?: throw BackupException("备份缺少或损坏 conversations.json，无法还原")
        val messages = BackupCodec.decodeMessages(messagesRaw)
            ?: throw BackupException("备份缺少或损坏 messages.json，无法还原")
        // `[]` 是「合法 JSON 但什么都没有」：解析得出来，就直接把本机数据清空还报成功。
        // 清单里本来就写着条数（就是导入前给用户看的那两个数），对一下就能识别出来。
        if (parsedManifest.conversationCount > 0 && conversations.isEmpty()) {
            throw BackupException("备份里的 conversations.json 没有可用会话，已中止（备份可能已损坏）")
        }
        if (parsedManifest.messageCount > 0 && messages.isEmpty()) {
            throw BackupException("备份里的 messages.json 没有可用消息，已中止（备份可能已损坏）")
        }
        return Staged(
            manifest = parsedManifest,
            settings = BackupCodec.decodeSettings(settingsRaw),
            conversations = conversations,
            messages = messages,
            attachmentCount = attachmentCount,
            attachmentBytes = attachmentBytes,
        )
    }
}
