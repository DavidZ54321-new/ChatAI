package com.zcw.chatai.data.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

/** `MediaMetadataRetriever` 的薄封装：读时长/尺寸 + 抽首帧。失败一律返回 null，不抛。 */
object VideoMetadata {

    data class Info(val durationMs: Long, val width: Int, val height: Int)

    fun read(file: File): Info? = withRetriever(file) { retriever ->
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
            Info(duration, width = height, height = width)
        } else {
            Info(duration, width = width, height = height)
        }
    }

    /** 首帧（同步帧），用于列表缩略图。 */
    fun firstFrame(file: File): Bitmap? = withRetriever(file) { retriever ->
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    private inline fun <T> withRetriever(file: File, block: (MediaMetadataRetriever) -> T): T? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            block(retriever)
        } finally {
            runCatching { retriever.release() }
        }
    } catch (t: Throwable) {
        null
    }
}
