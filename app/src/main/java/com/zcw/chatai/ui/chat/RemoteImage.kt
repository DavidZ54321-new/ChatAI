package com.zcw.chatai.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zcw.chatai.ui.theme.ChatTheme
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 最小的远程图片加载器（本项目没有图像库，沿用 [Attachments] 里手写 BitmapFactory 的风格）。
 *
 * - 只处理 http(s)；内存 LruCache 按「URL + 目标边长」缓存；
 * - 下载有字节上限，解码按显示尺寸采样；任何失败静默返回 null（UI 显示占位底色）。
 */
object RemoteImages {

    private const val MAX_BYTES = 8 * 1024 * 1024

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun peek(url: String, maxEdge: Int): Bitmap? = cache.get(key(url, maxEdge))

    suspend fun load(url: String, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        peek(url, maxEdge)?.let { return@withContext it }
        val bitmap = fetch(url, maxEdge)
        if (bitmap == null) {
            android.util.Log.d("RemoteImages", "load failed: $url")
        }
        if (bitmap == null) return@withContext null
        cache.put(key(url, maxEdge), bitmap)
        bitmap
    }

    private fun key(url: String, maxEdge: Int): String = "$url@$maxEdge"

    private fun fetch(url: String, maxEdge: Int): Bitmap? = try {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "ChatAI/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val declared = response.body.contentLength()
            if (declared > MAX_BYTES) return null
            val bytes = response.body.byteStream().use(::readCapped) ?: return null
            decodeSampled(bytes, maxEdge)
        }
    } catch (t: Throwable) {
        null
    }

    /** 最多读 [MAX_BYTES]；超过上限视为失败（截断的图片解不出来，不如早退）。 */
    private fun readCapped(stream: InputStream): ByteArray? {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    } catch (t: Throwable) {
        null
    }
}

/** 远程图片：加载完成前显示占位底色，失败保持占位（不崩不报错）。 */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxEdge: Int = 1024,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<Bitmap?>(
        initialValue = RemoteImages.peek(url, maxEdge),
        url,
        maxEdge,
    ) {
        value = RemoteImages.load(url, maxEdge)
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        // 失败/加载中：至少留一个有高度的占位（markdown 里 0 高度会像「图丢了」）。
        Box(modifier = modifier.heightIn(min = 96.dp).background(ChatTheme.colors.surfaceSoft))
    }
}

/** 点开图搜结果的全屏预览：捏合缩放 + 拖动，退出即关。 */
@Composable
fun RemoteImagePreviewDialog(
    url: String,
    title: String?,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
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
            RemoteImage(
                url = url,
                contentDescription = title ?: "图片预览",
                maxEdge = 2048,
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
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                )
            }
        }
    }
}
