package com.zcw.chatai.ui.chat

import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zcw.chatai.data.model.AttachmentKind
import kotlinx.coroutines.delay

/** 附件预览的纯逻辑：哪些能进预览、点中的那张排第几。 */
object AttachmentPreview {

    /** 可预览的附件：图片/视频/音频；文档暂不预览。 */
    fun previewable(images: List<MessageImage>): List<MessageImage> =
        images.filter { it.kind != AttachmentKind.DOCUMENT }

    /** 点中的那张在可预览列表里的下标（不在其中返回 -1）。 */
    fun indexOf(images: List<MessageImage>, id: String): Int =
        previewable(images).indexOfFirst { it.id == id }
}

/**
 * 用户消息附件的全屏预览：左右滑在这条消息的图片/视频/音频之间切换。
 * 图片可捏合缩放；视频/音频**手点才播**（滑到不自动播）；文档不进预览。
 * 没有「保存到相册」——保存只在远程图预览（文搜图/回答里的图）那一侧。
 */
@Composable
fun AttachmentPreviewDialog(
    images: List<MessageImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    val start = initialIndex.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = start, pageCount = { images.size })
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 停住之后才换页：滑到一半时 currentPage 已经变了，缩放还留在原来那张上。
    var zoomPage by remember { mutableIntStateOf(start) }
    val settled = pagerState.settledPage
    if (zoomPage != settled) {
        zoomPage = settled
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(1f, 6f)
        if (next > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
        scale = next
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 放大后拖动是平移，缩回 1× 才翻页。
                userScrollEnabled = scale <= 1f,
            ) { index ->
                val image = images[index]
                when (image.kind) {
                    AttachmentKind.VIDEO -> VideoPage(image)
                    AttachmentKind.AUDIO -> AudioPage(image)
                    else -> ZoomableLocalImage(
                        image = image,
                        zoomed = index == settled,
                        scale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        transformState = transformState,
                    )
                }
            }
            Text(
                text = "${pagerState.currentPage + 1} / ${images.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 24.dp),
            )
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

/** 本地图片页：按显示尺寸解码 + 捏合缩放/平移（放大时不吃翻页手势）。 */
@Composable
private fun ZoomableLocalImage(
    image: MessageImage,
    zoomed: Boolean,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    transformState: TransformableState,
) {
    val bitmap = rememberBitmap(image.fullPath, maxEdge = 2048)
    val current = bitmap ?: return
    Image(
        bitmap = current,
        contentDescription = "图片预览",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .then(
                if (zoomed) {
                    Modifier
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        )
                        .transformable(state = transformState, canPan = { scale > 1f })
                } else {
                    Modifier
                },
            ),
    )
}

/** 视频页：停在首帧 + 播放键；手点后换成内联 VideoView（系统 MediaController 控制条）。 */
@Composable
private fun VideoPage(image: MessageImage) {
    var playing by remember(image.id) { mutableStateOf(false) }
    if (playing) {
        val context = LocalContext.current
        val videoView = remember(image.id) { VideoView(context) }
        val controller = remember(image.id) { MediaController(context).apply { setAnchorView(videoView) } }
        // 翻页/关闭即离开组合 → 收掉悬浮控制条并停播，别让它飘在别的界面上（也避免留 Activity 引用）。
        DisposableEffect(image.id) {
            onDispose {
                runCatching { controller.hide() }
                runCatching { videoView.setMediaController(null) }
                runCatching { videoView.stopPlayback() }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                videoView.apply {
                    setVideoPath(image.fullPath)
                    setMediaController(controller)
                    setOnPreparedListener { mp ->
                        mp.start()
                        controller.show()
                    }
                }
            },
        )
    } else {
        val frame = rememberVideoFrame(image.fullPath, image.thumbnailPath, maxEdge = 1280)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val current = frame
            if (current != null) {
                Image(
                    bitmap = current,
                    contentDescription = "视频首帧",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
            PlayBadge(onClick = { playing = true })
            image.durationMs?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                )
            }
        }
    }
}

/** 音频页：音符牌 + 文件名 + 进度；手点播放键才出声。 */
@Composable
private fun AudioPage(image: MessageImage) {
    val player = remember(image.id) { MediaPlayer() }
    var prepared by remember(image.id) { mutableStateOf(false) }
    var playing by remember(image.id) { mutableStateOf(false) }
    var position by remember(image.id) { mutableLongStateOf(0L) }
    val totalMs = image.durationMs ?: if (prepared) {
        runCatching { player.duration.toLong() }.getOrDefault(0L)
    } else {
        0L
    }

    DisposableEffect(image.id) {
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            delay(200)
        }
    }
    fun toggle() {
        if (playing) {
            runCatching { player.pause() }
            playing = false
            return
        }
        playing = runCatching {
            if (!prepared) {
                player.setDataSource(image.fullPath)
                player.setOnCompletionListener {
                    playing = false
                    position = 0L
                    runCatching { player.seekTo(0) }
                }
                player.prepare()
                prepared = true
            }
            player.start()
        }.isSuccess
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.22f))
                        .clickable { toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (playing) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "播放",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
            Text(
                text = image.displayName ?: "音频",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatDuration(position)} / ${formatDuration(totalMs)}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PlayBadge(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "播放",
            tint = Color.White,
            modifier = Modifier.size(34.dp),
        )
    }
}
