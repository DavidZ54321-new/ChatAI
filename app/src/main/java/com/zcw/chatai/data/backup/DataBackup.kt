package com.zcw.chatai.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.zcw.chatai.data.db.AppDatabase
import com.zcw.chatai.data.db.AttachmentCodec
import com.zcw.chatai.data.db.MessageEntity
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.media.ImageCodec
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.data.prefs.SettingsRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackupPhase { IDLE, RUNNING, DONE, FAILED }

/** 导出/导入的一行进度。跑在仓库自己的 scope 上，界面退出再回来仍能看到最终结果。 */
data class BackupProgress(
    val phase: BackupPhase = BackupPhase.IDLE,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
) {
    val running: Boolean get() = phase == BackupPhase.RUNNING
}

data class ExportOptions(
    /** 带上附件二进制（图片/视频/音频/文档）。关掉体积小很多，但历史消息的附件会变成占位。 */
    val includeAttachments: Boolean = true,
    /** 带上 API Key。zip 是明文，关掉便于把备份发给别人。 */
    val includeApiKeys: Boolean = true,
)

/** 选中一个备份包之后的预览信息（不用解压整包，只读开头的 manifest）。 */
data class BackupSummary(
    val conversationCount: Int,
    val messageCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long,
    val includesAttachments: Boolean,
    val includesApiKeys: Boolean,
    val exportedAt: Long,
    val formatVersion: Int,
)

enum class ImportMode {
    /** 先清空本机聊天与配置再还原（换手机的标准用法）。 */
    REPLACE,

    /** 保留本机数据，把备份里的会话追加进来（会话与消息 id 全部重分配）。 */
    MERGE,
}

/**
 * 一次成功的导入。[conversationIds] 是导入之后存在的全部会话 id，
 * 界面层据此判断「当前选中的会话是否还在」（覆盖式还原可能把它换掉）。
 * [sequence] 单调递增，所以连续两次导入也会各自发一次事件。
 */
data class ImportCompletion(val sequence: Int, val conversationIds: Set<String>)

/**
 * 备份与还原：把聊天记录、附件、设置打包成一个 zip，也能从这个 zip 还原。
 *
 * 设计取舍见 `BackupFormat` 的文件头；这里只负责 IO 编排：
 * - 导出：JSON 条目 DEFLATE、附件条目 STORED（媒体再压没意义，视频白烧 CPU）；
 * - 导入：先整包解压到 staging（解压与校验都在 `BackupStaging`，顺带做 zip-slip 校验），
 *   再按模式落库、落文件；
 * - 全程只走 DAO 与 DataStore，**不替换数据库文件**，所以不需要重启进程，Room 的 Flow 会自动刷新。
 */
class DataBackup(
    context: Context,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val attachmentStore: AttachmentStore,
    /** 有回合在跑时拒绝导入：正在写的行会被删掉/覆盖。 */
    private val busy: () -> Boolean = { false },
    /** 导入完成后回调（清远程图缓存，避免还原前的位图留在同路径上）。 */
    private val onImported: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val appContext = context.applicationContext

    private val _progress = MutableStateFlow(BackupProgress())

    val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

    private val _imports = MutableStateFlow<ImportCompletion?>(null)

    /**
     * 导入完成信号：界面层据此处理「脚下的数据被换掉了」的后果——
     * 覆盖式还原可能让当前选中的会话不复存在（见 `ChatViewModel`）。
     */
    val imports: StateFlow<ImportCompletion?> = _imports.asStateFlow()

    /** 用户看过结果之后清掉提示（离屏再回来不会一直挂着上一次的成功/失败）。 */
    fun dismissResult() {
        if (!_progress.value.running) _progress.value = BackupProgress()
    }

    /**
     * 冷启动调用：清掉上次在导入中途被杀留下的解压目录（可能有好几个 GB，而它不在 `attachments/` 下，
     * 孤儿清理扫不到），并把顶替中断留下的中间目录**先恢复再清理**（见 `FileSwap.recoverAndClean`）。
     */
    suspend fun clearStaleStaging() = withContext(Dispatchers.IO) {
        runCatching { File(appContext.filesDir, STAGING_DIR).deleteRecursively() }
        runCatching { FileSwap.recoverAndClean(attachmentStore.rootDir()) }
        Unit
    }

    // ---------- 导出 ----------

    fun export(target: Uri, options: ExportOptions = ExportOptions()) {
        if (_progress.value.running) return
        _progress.value = BackupProgress(BackupPhase.RUNNING, "正在准备…")
        scope.launch {
            runCatching { writeArchive(target, options) }
                .onSuccess { label -> _progress.value = BackupProgress(BackupPhase.DONE, label) }
                .onFailure { t ->
                    _progress.value = BackupProgress(BackupPhase.FAILED, error = readable(t, "导出失败"))
                }
        }
    }

    private suspend fun writeArchive(target: Uri, options: ExportOptions): String =
        withContext(Dispatchers.IO) {
            val conversations = db.conversationDao().getAll()
            val messages = db.messageDao().getAll()
            val settings = settingsRepository.settings.first()
            val files = if (options.includeAttachments) attachmentFiles(messages) else emptyList()
            val attachmentBytes = files.sumOf { it.second.length() }
            val manifest = BackupManifest(
                appVersionName = versionName(),
                appVersionCode = versionCode(),
                exportedAt = nowMs(),
                includesAttachments = options.includeAttachments,
                includesApiKeys = options.includeApiKeys,
                conversationCount = conversations.size,
                messageCount = messages.size,
                attachmentCount = files.size,
                attachmentBytes = attachmentBytes,
            )
            val total = files.size + 4
            val output = appContext.contentResolver.openOutputStream(target)
                ?: throw BackupException("无法写入所选位置，请换一个目录再试")
            output.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    var done = 0
                    fun putJson(name: String, body: String) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(body.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        done++
                        _progress.value = BackupProgress(
                            BackupPhase.RUNNING,
                            "正在写入备份 $done/$total…",
                            done,
                            total,
                        )
                    }
                    putJson(BackupPaths.MANIFEST, BackupCodec.encodeManifest(manifest))
                    putJson(
                        BackupPaths.SETTINGS,
                        BackupCodec.encodeSettings(settings, options.includeApiKeys),
                    )
                    putJson(BackupPaths.CONVERSATIONS, BackupCodec.encodeConversations(conversations))
                    putJson(BackupPaths.MESSAGES, BackupCodec.encodeMessages(messages))
                    for ((relative, file) in files) {
                        putStoredFile(zip, relative, file)
                        done++
                        _progress.value = BackupProgress(
                            BackupPhase.RUNNING,
                            "正在写入附件 $done/$total…",
                            done,
                            total,
                        )
                    }
                }
            }
            buildString {
                append("已导出 ").append(conversations.size).append(" 个会话、")
                append(messages.size).append(" 条消息")
                if (options.includeAttachments) {
                    append("、").append(files.size).append(" 个附件（")
                        .append(formatBytes(attachmentBytes)).append("）")
                } else {
                    append("（不含附件）")
                }
            }
        }

    /** STORED 条目必须在写之前给出 size/csize/crc，所以 CRC 单独扫一遍（大文件也不吃内存）。 */
    private fun putStoredFile(zip: ZipOutputStream, relative: String, file: File) {
        val entry = ZipEntry(relative)
        entry.method = ZipEntry.STORED
        entry.size = file.length()
        entry.compressedSize = entry.size
        entry.crc = crcOf(file)
        zip.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zip, DEFAULT_BUFFER_SIZE) }
        zip.closeEntry()
    }

    /** 导出清单：逐条消息的附件里**实际存在**的文件（主文件 + 缩略图 + 文档 sidecar）。 */
    private fun attachmentFiles(messages: List<MessageEntity>): List<Pair<String, File>> {
        val filesDir = appContext.filesDir
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Pair<String, File>>()
        fun add(relative: String?) {
            if (relative.isNullOrBlank() || !seen.add(relative)) return
            val file = File(filesDir, relative)
            if (file.isFile && file.length() > 0) out += relative to file
        }
        for (message in messages) {
            for (attachment in AttachmentCodec.decode(message.attachments)) {
                add(attachment.relativePath)
                add(ImageCodec.thumbRelativePath(attachment.relativePath))
                add(attachment.extractedPath)
            }
        }
        return out
    }

    // ---------- 预检 ----------

    /** 读清单：只需读开头的 `manifest.json`，不用解压整包。 */
    suspend fun inspect(source: Uri): Result<BackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            var manifest: BackupManifest? = null
            openArchive(source).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!BackupPaths.isSafeEntry(entry.name)) {
                        throw BackupException("备份包含不安全的路径：${entry.name}")
                    }
                    if (!entry.isDirectory && entry.name == BackupPaths.MANIFEST) {
                        manifest = BackupCodec.decodeManifest(zip.readEntryText())
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            val parsed = manifest
                ?: throw BackupException("这不是 ChatAI 的备份文件（缺少 manifest.json）")
            BackupSummary(
                conversationCount = parsed.conversationCount,
                messageCount = parsed.messageCount,
                attachmentCount = parsed.attachmentCount,
                attachmentBytes = parsed.attachmentBytes,
                includesAttachments = parsed.includesAttachments,
                includesApiKeys = parsed.includesApiKeys,
                exportedAt = parsed.exportedAt,
                formatVersion = parsed.formatVersion,
            )
        }
    }

    // ---------- 导入 ----------

    fun import(source: Uri, mode: ImportMode) {
        if (_progress.value.running) return
        if (busy()) {
            _progress.value = BackupProgress(
                BackupPhase.FAILED,
                error = "有正在生成的回答，请先停止再导入",
            )
            return
        }
        _progress.value = BackupProgress(BackupPhase.RUNNING, "正在读取备份…")
        scope.launch {
            runCatching { apply(source, mode) }
                .onSuccess { outcome ->
                    onImported()
                    _imports.value = ImportCompletion(
                        sequence = (_imports.value?.sequence ?: 0) + 1,
                        conversationIds = outcome.conversationIds,
                    )
                    _progress.value = BackupProgress(BackupPhase.DONE, outcome.label)
                }
                .onFailure { t ->
                    _progress.value = BackupProgress(BackupPhase.FAILED, error = readable(t, "导入失败"))
                }
        }
    }

    private data class ImportOutcome(val label: String, val conversationIds: Set<String>)

    private suspend fun apply(source: Uri, mode: ImportMode): ImportOutcome = withContext(Dispatchers.IO) {
        val staging = File(appContext.filesDir, STAGING_DIR)
        staging.deleteRecursively()
        try {
            val payload = openArchive(source).use { zip -> BackupStaging.stage(zip, staging) }
            val conversations = payload.conversations
            // 解压可能跑了几分钟，这期间用户完全可能回到聊天页发起新回合：
            // 上一次比对已经过期，写库前必须再确认一次，否则会把刚写的消息默默删掉。
            if (busy()) throw BackupException("有正在生成的回答，请先停止再导入")
            val localSettings = settingsRepository.settings.first()
            var dropped = 0
            var unplaced = 0
            var settingsError: String? = null

            val label = when (mode) {
                ImportMode.REPLACE -> {
                    _progress.value = BackupProgress(BackupPhase.RUNNING, "正在写入聊天记录…")
                    val conversationIds = conversations.mapTo(HashSet()) { it.id }
                    val messages = payload.messages
                        .filter { it.conversationId in conversationIds }
                        .map { message ->
                            val result = BackupCodec.pruneMissingAttachments(message.attachments) {
                                existsIn(staging, it)
                            }
                            dropped += result.dropped
                            message.copy(attachments = result.attachments)
                        }
                    db.withTransaction {
                        db.messageDao().deleteAll()
                        db.conversationDao().deleteAll()
                        db.conversationDao().insertAll(conversations)
                        db.messageDao().insertAll(messages)
                    }
                    _progress.value = BackupProgress(BackupPhase.RUNNING, "正在还原附件…")
                    // 备份不含附件时 staging 里没有 attachments/：这时不能算失败，
                    // 但要把本机旧文件清掉——新记录里已经没有任何附件引用了。
                    if (File(staging, AttachmentStore.DIR).isDirectory) {
                        if (!attachmentStore.swapIn(staging)) {
                            throw BackupException("附件还原失败，请检查存储空间后重新导入")
                        }
                    } else {
                        attachmentStore.clearAll()
                    }
                    // 设置写在最后：它失败时聊天记录与附件其实已经还原好了，
                    // 报「导入失败」会让用户以为白导一趟、再去导一次（那是破坏性的）。
                    payload.settings?.let { imported ->
                        settingsError = runCatching { settingsRepository.replaceAll(imported) }
                            .exceptionOrNull()
                            ?.let { error -> error.message?.takeIf { it.isNotBlank() } ?: "未知原因" }
                    }
                    buildString {
                        append("已还原 ").append(conversations.size).append(" 个会话、")
                        append(messages.size).append(" 条消息（覆盖了本机原有数据）")
                    }
                }

                ImportMode.MERGE -> {
                    _progress.value = BackupProgress(BackupPhase.RUNNING, "正在追加聊天记录…")
                    // 先按**原始**路径核对附件（此刻文件还在 staging 的旧会话目录里），
                    // 再重映射 id 与路径——顺序反过来就对不上磁盘上的文件了。
                    val pruned = payload.messages.map { message ->
                        val result = BackupCodec.pruneMissingAttachments(message.attachments) {
                            existsIn(staging, it)
                        }
                        dropped += result.dropped
                        message.copy(attachments = result.attachments)
                    }
                    val remapped = BackupMerge.remap(conversations, pruned, newId)
                    db.withTransaction {
                        db.conversationDao().insertAll(remapped.conversations)
                        db.messageDao().insertAll(remapped.messages)
                    }
                    // 附件落地失败不能让用户以为成功了：行已经落库，只能如实报出来。
                    unplaced = relocateStaged(staging, remapped.conversationIds)
                    payload.settings?.let { imported ->
                        settingsError = runCatching {
                            settingsRepository.replaceAll(BackupCodec.mergeSettings(localSettings, imported))
                        }
                            .exceptionOrNull()
                            ?.let { error -> error.message?.takeIf { it.isNotBlank() } ?: "未知原因" }
                    }
                    buildString {
                        append("已追加 ").append(remapped.conversations.size).append(" 个会话、")
                        append(remapped.messages.size).append(" 条消息")
                    }
                }
            }

            buildString {
                append(label)
                if (dropped > 0) append("；").append(dropped).append(" 个附件在备份里缺失，已跳过")
                if (unplaced > 0) {
                    append("；").append(unplaced).append(" 个会话的附件未能落地，")
                    append("请检查存储空间后重新导入")
                }
                settingsError?.let { reason ->
                    append("；设置写入失败（").append(reason).append("），请到设置里检查")
                }
                if (!payload.manifest.includesApiKeys) append("；备份不含 API Key，请到设置里重新填写")
                if (payload.settings == null) append("；备份不含设置，本机连接配置保持不变")
            }.let { summary ->
                ImportOutcome(
                    label = summary,
                    // 导入后仍在的会话 id：界面层用它判断选中的会话有没有被换掉。
                    conversationIds = db.conversationDao().getAll().mapTo(HashSet()) { it.id },
                )
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 合并导入：把 staging 里按旧会话分好的目录改名到新会话目录（同分区 rename，近乎零成本）。 */
    private fun relocateStaged(staging: File, conversationIds: Map<String, String>): Int {
        val root = attachmentStore.rootDir()
        var failed = 0
        for ((oldId, newId) in conversationIds) {
            val from = File(File(staging, AttachmentStore.DIR), oldId)
            if (!from.isDirectory) continue
            val to = File(root, newId)
            to.parentFile?.mkdirs()
            runCatching { to.deleteRecursively() }
            val placed = from.renameTo(to) ||
                runCatching { from.copyRecursively(to, overwrite = true) }.getOrDefault(false)
            // 落地失败的行已经写进库了：如实计数，让汇总文案提示用户重新导入。
            if (!placed) failed++
        }
        return failed
    }

    /** 附件是否真的落到位（文档还要求 sidecar 在）：不在就清掉元数据，避免永久「文件不可用」。 */
    private fun existsIn(baseDir: File, attachment: Attachment): Boolean {
        if (!File(baseDir, attachment.relativePath).isFile) return false
        val sidecar = attachment.extractedPath ?: return true
        return attachment.kind != AttachmentKind.DOCUMENT || File(baseDir, sidecar).isFile
    }

    // ---------- 小工具 ----------

    /** 打开备份包（调用方负责 `use`）：解压逻辑本身在 `BackupStaging`。 */
    private fun openArchive(source: Uri): ZipInputStream {
        val stream = appContext.contentResolver.openInputStream(source)
            ?: throw BackupException("无法读取所选文件")
        return ZipInputStream(BufferedInputStream(stream))
    }

    private fun crcOf(file: File): Long {
        val crc = CRC32()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun versionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun versionCode(): Int = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    private fun readable(t: Throwable, fallback: String): String =
        t.message?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        /** 解压落地目录（在 filesDir 下，但不属于 attachments/，孤儿清理不会碰它）。 */
        const val STAGING_DIR = "backup-staging"

        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}

/** 体积文案（导出完成提示与导入预览共用）。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
