package com.zcw.chatai.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.doc.DocumentLabel
import com.zcw.chatai.data.media.ImageCodec
import com.zcw.chatai.data.media.VideoMetadata
import com.zcw.chatai.data.model.AttachmentKind
import com.zcw.chatai.ui.theme.ChatTheme
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 按显示尺寸采样解码，避免把 1568px 的图整张塞进列表。 */
@Composable
internal fun rememberBitmap(path: String?, maxEdge: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, maxEdge) {
        value = if (path == null) null else withContext(Dispatchers.IO) { decodeSampled(path, maxEdge) }
    }
    return bitmap
}

private fun decodeSampled(path: String, maxEdge: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }
} catch (t: Throwable) {
    null
}

/** 视频首帧：`MediaMetadataRetriever` 抽真帧（不放大），失败退回 360 缩略图。 */
@Composable
internal fun rememberVideoFrame(fullPath: String, thumbnailPath: String, maxEdge: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, fullPath, thumbnailPath, maxEdge) {
        value = withContext(Dispatchers.IO) {
            decodeVideoFrame(fullPath, maxEdge) ?: decodeSampled(thumbnailPath, maxEdge)
        }
    }
    return bitmap
}

private fun decodeVideoFrame(path: String, maxEdge: Int): ImageBitmap? = try {
    val frame = VideoMetadata.firstFrame(File(path))
    if (frame == null) {
        null
    } else {
        val size = ImageCodec.computeTargetSize(frame.width, frame.height, maxEdge)
        val scaled = if (size.width == frame.width && size.height == frame.height) {
            frame
        } else {
            Bitmap.createScaledBitmap(frame, size.width, size.height, true).also { frame.recycle() }
        }
        scaled.asImageBitmap()
    }
} catch (t: Throwable) {
    null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentThumbnail(
    path: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    durationMs: Long? = null,
    /** 文档类型戳（如 PDF）：非空时不解位图，中央显示大写字母（尺寸/圆角与图片一致）。 */
    label: String? = null,
    /** 长按（图片-only 的用户消息靠它够到消息菜单：没有文字气泡可点）。 */
    onLongClick: (() -> Unit)? = null,
) {
    val colors = ChatTheme.colors
    // 按实际显示尺寸解码：82dp 的缩略图没必要解 400px 的位图（多图时差别很明显）。
    val maxEdge = with(LocalDensity.current) { (size.value * density).roundToInt() }.coerceAtLeast(96)
    val bitmap = rememberBitmap(if (label == null) path else null, maxEdge = maxEdge)
    val gesture = if (onLongClick == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSoft)
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Box
        }
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = if (isVideo) "视频" else "图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            durationMs?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** 时长显示：<1 分钟 → `0:07`，其余 → `1:23`；超过 1 小时 → `1:02:03`。 */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * 消息里的图片：全是「视窗宽度 20%」的正方形缩略图。
 * 少则贴着气泡右边缘排，多则横向懒加载滑动（不会像以前那样只用前 3 张、其余全看不到）。
 */
@Composable
fun MessageImageRow(
    images: List<MessageImage>,
    onOpen: (MessageImage) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val side = ChatMetrics.thumbnailSide(LocalWindowInfo.current.containerDpSize.width)
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        items(items = images, key = { it.id }) { image ->
            AttachmentThumbnail(
                path = image.thumbnailPath,
                size = side,
                // 文档不可点（尚无预览器）：点透会导致空白预览弹层。图片/视频/音频都进预览。
                onClick = { if (image.kind != AttachmentKind.DOCUMENT) onOpen(image) },
                isVideo = image.kind == AttachmentKind.VIDEO,
                durationMs = image.durationMs,
                label = image.label,
                onLongClick = onLongPress,
            )
        }
    }
}

/** Composer 里的待发送缩略图，右上角可删除。8 张也不能把输入框撑破，所以同样横向懒加载。 */
@Composable
fun PendingAttachmentStrip(
    pending: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时在末尾追加一个「＋」方块（编辑弹层用它直达系统选择器）。 */
    onAdd: (() -> Unit)? = null,
) {
    val colors = ChatTheme.colors
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = pending, key = { it.id }) { item ->
            Box {
                AttachmentThumbnail(
                    path = item.thumbnailPath,
                    size = 64.dp,
                    onClick = {},
                    isVideo = item.attachment.kind == AttachmentKind.VIDEO,
                    durationMs = item.attachment.durationMs,
                    label = when (item.attachment.kind) {
                        AttachmentKind.DOCUMENT -> DocumentLabel.of(
                            item.attachment.mimeType,
                            item.attachment.displayName ?: item.attachment.relativePath,
                        )
                        AttachmentKind.AUDIO -> "AUDIO"
                        else -> null
                    },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { onRemove(item.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "移除图片",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (onAdd != null) {
            item(key = "add") {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceSoft)
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "添加附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}
