package com.zcw.chatai.ui.chat

import android.content.ComponentCallbacks2
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 最小的远程图片加载器（本项目没有图像库，沿用 [Attachments] 里手写 BitmapFactory 的风格）。
 *
 * - 只处理 http(s)；内存 LruCache 按「URL + 目标边长」缓存，容量随设备堆大小伸缩；
 * - [install] 注入磁盘缓存目录后，进程重启也不重新下载（OkHttp Cache）；
 * - 下载/解码全局并发上限 [MAX_CONCURRENT_LOADS]，一屏几十张图不会同时挤满 CPU；
 * - 下载有字节上限，解码按显示尺寸采样；任何失败静默返回 null（UI 显示占位底色）。
 */
object RemoteImages {

    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val DISK_CACHE_BYTES = 48L * 1024 * 1024
    private const val MAX_CONCURRENT_LOADS = 4

    @Volatile
    private var diskCacheDir: java.io.File? = null

    /** Application.onCreate 调用：挂上磁盘缓存；不调用则退回纯内存缓存。 */
    fun install(context: android.content.Context) {
        diskCacheDir = java.io.File(context.cacheDir, "remote-images")
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .apply { diskCacheDir?.let { cache(okhttp3.Cache(it, DISK_CACHE_BYTES)) } }
            .build()
    }

    private val loadPermits = Semaphore(MAX_CONCURRENT_LOADS)

    private val cache = object : LruCache<String, Bitmap>(
        memoryCacheBytes(Runtime.getRuntime().maxMemory()),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 系统内存吃紧时按等级回收（进程还活着但后台/低内存的场景）。 */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                cache.trimToSize(cache.maxSize() / 2)

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                cache.trimToSize(cache.maxSize() * 3 / 4)
        }
    }

    /** 位图缓存预算 = 堆上限的 1/8，钳在 8~64MB（16 图会话不再互相逐出）。 */
    internal fun memoryCacheBytes(maxMemoryBytes: Long): Int =
        (maxMemoryBytes / 8).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt()

    fun peek(url: String, maxEdge: Int): Bitmap? = cache.get(key(url, maxEdge))

    suspend fun load(url: String, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        peek(url, maxEdge)?.let { return@withContext it }
        val bitmap = loadPermits.withPermit { fetch(url, maxEdge) }
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

/** 远程图片的三种状态：加载中 / 已就绪 / 失败（失败占位可点重载）。 */
private sealed interface RemoteImageState {
    data object Loading : RemoteImageState

    data class Loaded(val bitmap: Bitmap) : RemoteImageState

    data object Failed : RemoteImageState
}

/**
 * 远程图片：加载中显示占位底色 + 转圈，失败后占位上出现刷新图标，点一下重新拉取
 * （断网/超时这类临时失败不用退出重进会话）。
 *
 * [onAspect] 在图片就绪后回报宽高比（宽/高），供调用方按原图比例排版。
 */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxEdge: Int = 1024,
    contentScale: ContentScale = ContentScale.Crop,
    onAspect: ((Float) -> Unit)? = null,
) {
    // 重载计数：失败后点占位自增，作为 produceState 的 key 触发重新加载。
    var attempt by remember(url, maxEdge) { mutableIntStateOf(0) }
    val state by produceState<RemoteImageState>(
        initialValue = RemoteImages.peek(url, maxEdge)
            ?.let(RemoteImageState::Loaded)
            ?: RemoteImageState.Loading,
        url,
        maxEdge,
        attempt,
    ) {
        // 重试时先回到 Loading：produceState 换 key 不会重置 value，
        // 不复位的话失败图标会一直挂着，用户以为点击没反应。
        if (value !is RemoteImageState.Loaded) value = RemoteImageState.Loading
        value = RemoteImages.load(url, maxEdge)
            ?.let(RemoteImageState::Loaded)
            ?: RemoteImageState.Failed
    }
    when (val current = state) {
        is RemoteImageState.Loaded -> {
            if (onAspect != null && current.bitmap.height > 0) {
                val aspect = current.bitmap.width.toFloat() / current.bitmap.height
                SideEffect { onAspect(aspect) }
            }
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier,
            )
        }

        is RemoteImageState.Loading -> Box(
            modifier = modifier.heightIn(min = 96.dp).background(ChatTheme.colors.surfaceSoft),
            contentAlignment = Alignment.Center,
        ) {
            // 转圈：重试时用户能看到点击生效（否则灰块 + 图标不变会以为没反应）。
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
            )
        }

        is RemoteImageState.Failed -> Box(
            modifier = modifier.heightIn(min = 96.dp).background(ChatTheme.colors.surfaceSoft),
            contentAlignment = Alignment.Center,
        ) {
            // 整块可点重载；覆盖外层的「点开预览」手势（失败图没有预览意义）。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { attempt++ },
            )
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "重新加载图片",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
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
