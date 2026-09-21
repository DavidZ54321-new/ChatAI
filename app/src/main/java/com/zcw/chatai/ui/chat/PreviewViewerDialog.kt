package com.zcw.chatai.ui.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.zcw.chatai.ui.md.PreviewLanguage
import com.zcw.chatai.ui.md.PreviewSegment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 预览全屏 viewer（独立 Dialog，随开随建、退出即销毁，列表里永远没有 WebView）。
 *
 * - mermaid：asset 壳离线渲染，页内渐进显示（大图转多久都不超时，用户随时可关），支持缩放；
 * - 动效 SVG：在这里播（SMIL/CSS/JS 通吃），支持缩放；
 * - HTML：在这里跑，站外跳转转外部浏览器；
 * - WebView 锁定：JS 开，只有 mermaid 壳页挂了收完成信号的 bridge，`file://` 不给外人用。
 *
 * 导出（PNG/GIF）暂不提供：本机整图截取在真机上不可靠，先封存，等有稳定方案再加。
 */
@Composable
fun PreviewViewerDialog(
    segment: PreviewSegment.Preview,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val isMermaid = segment.language == PreviewLanguage.MERMAID
    var pageReady by remember { mutableStateOf(false) }
    var renderRequested by remember { mutableStateOf(false) }
    // 渲染有结果了（成功/失败/进程被杀）：轮询据此收手，别再盖写成人话之外的结论。
    var renderSettled by remember { mutableStateOf(false) }
    var notice by remember(segment) { mutableStateOf(if (isMermaid) "渲染中…" else null) }
    val title = when (segment.language) {
        PreviewLanguage.MERMAID -> "mermaid 预览"
        PreviewLanguage.SVG -> "SVG 预览"
        PreviewLanguage.HTML -> "HTML 预览"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                // HTML 的「外部打开」是交给浏览器，不是本机转换，保留。
                if (segment.language == PreviewLanguage.HTML) {
                    TextButton(
                        onClick = { notice = openExternally(context, segment.code) },
                    ) { Text("外部打开") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        if (isMermaid) {
                            configureAssetLocked()
                            addJavascriptInterface(
                                ViewerBridge(
                                    onDone = { ok, msg ->
                                        // 错误快路；成功以轮询为准（bridge 成功回执在真机上丢过）。
                                        if (!ok) {
                                            Log.d(TAG, "viewer onDone ok=false msg=${msg.take(120)}")
                                            pageReady = false
                                            renderSettled = true
                                            notice = "渲染失败：$msg"
                                        }
                                    },
                                ),
                                "MermaidViewer",
                            )
                            webViewClient = viewerClient(
                                onPageFinished = {
                                    if (!renderRequested) {
                                        renderRequested = true
                                        evaluateJavascript(
                                            "window.renderIntoPage(" +
                                                JSONObject.quote(segment.code) + "," +
                                                (if (dark) "true" else "false") + ")",
                                            null,
                                        )
                                        // 成功判定不依赖 bridge：轮询 SVG 是否落页，
                                        // 60s 不出图才报超时（大图只管等）。
                                        scope.launch {
                                            val deadline =
                                                android.os.SystemClock.uptimeMillis() + 60_000
                                            while (
                                                !renderSettled &&
                                                android.os.SystemClock.uptimeMillis() < deadline
                                            ) {
                                                kotlinx.coroutines.delay(500)
                                                if (renderSettled) return@launch
                                                if (this@apply.hasSvg()) {
                                                    pageReady = true
                                                    renderSettled = true
                                                    notice = null
                                                    return@launch
                                                }
                                            }
                                            // 只有真的没人给过结论才算超时（别把失败改写成超时）。
                                            if (!renderSettled) {
                                                renderSettled = true
                                                notice = "渲染超时，可关闭重试"
                                            }
                                        }
                                    }
                                },
                                // 渲染进程被杀（低内存 + 3.5MB JS 是高危组合）就是白屏：
                                // 吃掉崩溃、打日志、给人话提示。
                                onRenderGone = {
                                    Log.e(TAG, "webview render process gone")
                                    pageReady = false
                                    renderSettled = true
                                    notice = "渲染进程被系统回收，请关闭重试"
                                },
                            )
                            webChromeClient = viewerChromeClient()
                            loadUrl("file:///android_asset/mermaid/render.html")
                        } else {
                            configureLocked()
                            webViewClient = viewerClient(
                                onPageFinished = { pageReady = true },
                                // SVG/HTML 页没有 mermaid 那套渲染进程可等，直接算"有结果"。
                                onRenderGone = {
                                    renderSettled = true
                                    notice = "渲染进程被系统回收，请关闭重试"
                                },
                            )
                            loadDataWithBaseURL(
                                "https://localhost/",
                                wrapHtml(segment),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    }
                },
                onRelease = { view ->
                    runCatching {
                        view.stopLoading()
                        view.removeJavascriptInterface("MermaidViewer")
                        view.destroy()
                    }
                },
            )
        }
    }
}

/** viewer 页内渲染的完成信号（fire-and-forget，结果已由页面自己展示）。 */
private class ViewerBridge(private val onDone: (Boolean, String) -> Unit) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // bridge 回调在 WebView 的 binder 线程，不在主线程：state 读写必须抛回主线程，
    // 否则直接抛 "Java exception was raised during method invocation"（真机实测）。
    @JavascriptInterface
    fun onDone(ok: Boolean, message: String) {
        main.post { onDone(ok, message) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureLocked() {
    settings.javaScriptEnabled = true
    // 初始缩放钉死 100%：WebView 默认按内容自适应缩放，观感与页面不一致。
    setInitialScale(100)
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.setSupportMultipleWindows(false)
    // 大图/长页可缩放查看（mermaid 知识地图、动效 SVG 都用得上）。
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}

/** mermaid asset 壳专用：只读本包 asset，不访问网络。 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
private fun WebView.configureAssetLocked() {
    settings.javaScriptEnabled = true
    setInitialScale(100)
    settings.allowFileAccess = true
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = true
    settings.allowUniversalAccessFromFileURLs = false
    settings.blockNetworkLoads = true
    settings.domStorageEnabled = true
    settings.setSupportMultipleWindows(false)
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}

private const val TAG = "PreviewViewer"

private fun viewerClient(
    onPageFinished: () -> Unit,
    onRenderGone: () -> Unit = {},
): WebViewClient =
    object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url == "https://localhost/") return false
            // 站外跳转一律转外部浏览器，预览页本身不导航。
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, request.url)
                view.context.startActivity(intent)
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            onPageFinished()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onRenderGone()
            return true
        }
    }

/** JS console 直通 logcat：白屏时先看这里。 */
private fun viewerChromeClient(): WebChromeClient =
    object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "console [${message.messageLevel()}] ${message.message()} @${message.lineNumber()}")
            return true
        }
    }

/** 页里有没有渲染出的 SVG（mermaid 成功轮询，主线程调用）。 */
private suspend fun WebView.hasSvg(): Boolean = withContext(Dispatchers.Main) {
    val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
    evaluateJavascript("String(!!document.querySelector('svg'))") { value ->
        done.complete(value == "\"true\"" || value == "true")
    }
    done.await()
}

/** viewer 装载模板：SVG 居中白底（动效原样播放），HTML 原样装载。 */
private fun wrapHtml(segment: PreviewSegment.Preview): String =
    if (segment.language == PreviewLanguage.SVG) {
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0;background:#fff;}" +
            "body{display:flex;align-items:center;justify-content:center;min-height:100vh;}" +
            "svg{max-width:100%;height:auto;}</style></head><body>" +
            segment.code + "</body></html>"
    } else {
        segment.code
    }

/** HTML 外部打开：落 cache + FileProvider 分享出去；返回提示文案。 */
private fun openExternally(context: android.content.Context, code: String): String = try {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "preview_${System.currentTimeMillis()}.html")
    file.writeText(code)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/html")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "用浏览器打开"))
    "已调起外部浏览器"
} catch (_: Throwable) {
    "打开失败"
}
