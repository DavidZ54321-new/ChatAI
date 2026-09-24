package com.zcw.chatai.ui.chat

import android.net.Uri
import com.zcw.chatai.data.model.Attachment
import com.zcw.chatai.data.model.AttachmentKind

/**
 * 编辑弹层的纯操作：草稿增删附件，以及「取消编辑该删哪些文件」的不变量。
 * 这里只做数据变换，不碰文件系统——真正的删除由调用方（ViewModel）执行。
 */
object MessageEdit {

    fun add(draft: EditDraft, attachment: PendingAttachment): EditDraft =
        draft.copy(
            attachments = draft.attachments + attachment,
            importedIds = draft.importedIds + attachment.id,
        )

    /** 移除附件的结果：新草稿 + 需要立刻删掉的文件（只可能是本次新导入的）。 */
    data class Removal(val draft: EditDraft, val deleteNow: List<Attachment>)

    /**
     * 移除一条附件。本次新导入的附件不属于任何消息，可以立刻删文件；
     * 原有附件仍被消息引用，**现在不能删**（等确认重发后由仓库层删）。
     */
    fun remove(draft: EditDraft, attachmentId: String): Removal {
        val target = draft.attachments.firstOrNull { it.id == attachmentId }
            ?: return Removal(draft, emptyList())
        val imported = attachmentId in draft.importedIds
        return Removal(
            draft = draft.copy(
                attachments = draft.attachments.filterNot { it.id == attachmentId },
                importedIds = if (imported) draft.importedIds - attachmentId else draft.importedIds,
            ),
            deleteNow = if (imported) listOf(target.attachment) else emptyList(),
        )
    }

    /** 放弃草稿时要删的文件：只有本次新导入的；原有附件一律不动（消息还引用着它们）。 */
    fun discardFiles(draft: EditDraft): List<Attachment> =
        draft.attachments.filter { it.id in draft.importedIds }.map { it.attachment }

    /**
     * 这条消息之后还有多少「轮」会被删除。一轮 = 一个 agent 回合（思考 + 工具调用 + 最终回答整组，
     * 见 [MessageGroups]），不再按原始消息行数算 —— 一行一算会把 a+b+1 行的一轮算成 a+b+1。
     * 末尾还没回答的用户消息也算一轮，免得「删了东西却说 0 轮」。找不到该消息时按 0 算。
     */
    fun laterCount(messages: List<ChatMessageItem>, messageId: String): Int {
        val groups = MessageGroups.of(messages)
        val index = groups.indexOfFirst { group -> group.items.any { it.id == messageId } }
        if (index < 0) return 0
        val after = groups.drop(index + 1)
        return after.count { it is MessageGroup.Assistant } +
            if (after.lastOrNull() is MessageGroup.User) 1 else 0
    }

    /**
     * 系统选择器回来的文件按 MIME 归类，MIME 不可用时回落到文件名扩展名。
     * 音频必须按 [AUDIO_MIME_TYPES] 判：`application/ogg` 不以 `audio/` 开头，但它确实是音频。
     * 少数 provider 不实现 `getType`（返回 null/octet-stream），这时靠扩展名兜底，别一律当文档。
     */
    fun kindOfMime(mimeType: String?, fileName: String? = null): AttachmentKind {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        return when {
            mime.startsWith("image/") -> AttachmentKind.IMAGE
            mime.startsWith("video/") -> AttachmentKind.VIDEO
            mime.startsWith("audio/") || mime in AUDIO_MIME_TYPES -> AttachmentKind.AUDIO
            mime.isEmpty() || mime == "application/octet-stream" -> kindOfExtension(fileName)
            else -> AttachmentKind.DOCUMENT
        }
    }

    /** 只在 MIME 不可用时兜底：按扩展名猜 kind。 */
    private fun kindOfExtension(fileName: String?): AttachmentKind =
        when (fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> AttachmentKind.IMAGE
            "mp4", "mov", "avi", "wmv", "mkv", "webm" -> AttachmentKind.VIDEO
            "mp3", "wav", "flac", "m4a", "ogg", "aac" -> AttachmentKind.AUDIO
            else -> AttachmentKind.DOCUMENT
        }
}

/**
 * 系统选择器回来的一个待导入文件：URI + 归类结果。
 * 归类要查 MIME 与展示名，所以由持有 Context 的弹层负责，仓库/VM 只按 kind 导入。
 */
data class PickedAttachment(val uri: Uri, val kind: AttachmentKind)

/**
 * 编辑弹层的回调集合：ChatScreen 的参数已经很多，按功能打包传进去，
 * 避免为了一个弹层再往签名里塞十几个 lambda。
 */
class MessageEditActions(
    val onBegin: (String) -> Unit,
    val onTextChange: (String) -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    /** 系统选择器回来的一批文件（已按 MIME 归类）：逐个导入，额度在导入时逐张判。 */
    val onAddAttachments: (List<PickedAttachment>) -> Unit,
    val onModelChange: (String) -> Unit,
    val onProviderChange: (String) -> Unit,
    val onToggleWebSearch: () -> Unit,
    val onResend: () -> Unit,
    val onDismiss: () -> Unit,
    /** 弹层挡着主界面，重发被拒（超限等）的提示必须在这里也能看到、也能消掉。 */
    val onNoticeShown: () -> Unit,
)
