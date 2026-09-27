package com.zcw.chatai.ui.chat

import android.content.Context
import com.zcw.chatai.data.media.GalleryStore
import com.zcw.chatai.data.model.AttachmentKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把预览里的那一张附件写进相册：视频 → `Movies/ChatAI`，图片 → `Pictures/ChatAI`。
 * 生图与视频两个工作区的「保存到相册」共用（同一份 kind 分派，避免把参考图当视频存）。
 */
internal suspend fun saveAttachmentToGallery(context: Context, image: MessageImage): Boolean =
    withContext(Dispatchers.IO) {
        val file = File(image.fullPath)
        if (!file.isFile) return@withContext false
        runCatching {
            if (image.kind == AttachmentKind.VIDEO) {
                val name = GalleryStore.exportFileName(System.currentTimeMillis(), "mp4")
                GalleryStore.saveVideoFile(context, file, name, GalleryStore.videoMimeFor(name))
            } else {
                val name = GalleryStore.exportFileName(System.currentTimeMillis(), attachmentExtension(image))
                GalleryStore.saveBytes(context, file.readBytes(), GalleryStore.mimeFor(name), name)
            }
        }.getOrDefault(false)
    }

/** 图片扩展名：按 MIME 推断，未知回落 png（生成图默认就是 png）。 */
internal fun attachmentExtension(image: MessageImage): String = when (image.mimeType?.lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "png"
}
