package com.zcw.chatai.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.zcw.chatai.data.doc.DocumentLabel
import com.zcw.chatai.data.media.GalleryStore
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
private fun rememberBitmap(path: String?, maxEdge: Int): ImageBitmap? {
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
private fun rememberVideoFrame(fullPath: String, thumbnailPath: String, maxEdge: Int): ImageBitmap? {
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
) {
    val colors = ChatTheme.colors
    // 按实际显示尺寸解码：82dp 的缩略图没必要解 400px 的位图（多图时差别很明显）。
    val maxEdge = with(LocalDensity.current) { (size.value * density).roundToInt() }.coerceAtLeast(96)
    val bitmap = rememberBitmap(if (label == null) path else null, maxEdge = maxEdge)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSoft)
            .clickable(onClick = onClick),
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
                // 文档不可点（尚无预览器）：点透会导致空白预览弹层。
                onClick = { if (image.label == null) onOpen(image) },
                isVideo = image.isVideo,
                durationMs = image.durationMs,
                label = image.label,
            )
        }
    }
}

/** 全屏预览：捏合缩放、拖动、保存到系统相册。 */
@Composable
fun ImagePreviewDialog(
    image: MessageImage,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = rememberBitmap(image.fullPath, maxEdge = 2048)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var saved by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
        ) {
            val current = bitmap
            if (current != null) {
                Image(
                    bitmap = current,
                    contentDescription = "图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (saved) "已保存" else "保存到相册",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable {
                            saved = saveToGallery(context, image.fullPath)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 视频预览：首帧大图 + 时长 + 「用系统播放器打开」（应用私有目录要经 FileProvider 授权）。 */
@Composable
fun VideoPreviewDialog(
    image: MessageImage,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = rememberVideoFrame(image.fullPath, image.thumbnailPath, maxEdge = 1280)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
        ) {
            val current = bitmap
            if (current != null) {
                Image(
                    bitmap = current,
                    contentDescription = "视频首帧",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                image.durationMs?.let { duration ->
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
                Text(
                    text = "用系统播放器打开",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable { openWithSystemPlayer(context, image.fullPath) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun openWithSystemPlayer(context: Context, path: String): Boolean = try {
    val file = File(path)
    if (!file.isFile) {
        false
    } else {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }
} catch (t: Throwable) {
    false
}

private fun saveToGallery(context: Context, path: String): Boolean =
    GalleryStore.saveFile(context, File(path))

/** Composer 里的待发送缩略图，右上角可删除。8 张也不能把输入框撑破，所以同样横向懒加载。 */
@Composable
fun PendingAttachmentStrip(
    pending: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    }
}
